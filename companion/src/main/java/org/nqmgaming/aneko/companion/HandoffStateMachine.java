package org.nqmgaming.aneko.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Phone-authoritative handoff intent. Callers serialize events and make Host commands idempotent. */
public final class HandoffStateMachine {
    public enum Site { PHONE, WATCH }
    public enum Target {
        PHONE, WATCH, BOTH;

        public boolean includes(Site site) {
            return this == BOTH || (this == PHONE && site == Site.PHONE)
                    || (this == WATCH && site == Site.WATCH);
        }
    }
    public enum Phase { IDLE, PREPARING, DEPARTING, ACTIVATING }

    public interface Host {
        /** Allocate/validate the destination, keeping it invisible until activate. */
        void prepare(long id, Site site);
        /** Run the source offscreen, or hide immediately if asleep; then report onGone. */
        void depart(long id, Site site);
        void activate(long id, Site site);
        void cancelPrepare(long id, Site site);
        void changed(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final Phase phase;
        public final long transferId;
        public final Target desired;
        public final Target destination;
        /** Authorized sites; ACTIVATING includes a destination whose ACK is still uncertain. */
        public final Set<Site> active;
        public final Set<Site> preparing;
        public final Set<Site> departing;
        public final Set<Site> activating;

        private Snapshot(HandoffStateMachine machine) {
            phase = machine.phase;
            transferId = machine.transferId;
            desired = machine.desired;
            destination = machine.destination;
            active = immutable(machine.active);
            preparing = immutable(machine.preparing);
            departing = immutable(machine.departing);
            activating = immutable(machine.activating);
        }
    }

    private final Host host;
    private final long readyTimeoutMs;
    private final EnumSet<Site> active = EnumSet.noneOf(Site.class);
    private final EnumSet<Site> preparedSites = EnumSet.noneOf(Site.class);
    private final EnumSet<Site> preparing = EnumSet.noneOf(Site.class);
    private final EnumSet<Site> departing = EnumSet.noneOf(Site.class);
    private final EnumSet<Site> activating = EnumSet.noneOf(Site.class);
    private Phase phase = Phase.IDLE;
    private Target desired;
    private Target destination;
    private long transferId;
    private long lastId;
    private long prepareStartedAt;
    private long now;

    public HandoffStateMachine(Host host, Target initialTarget, long idSeed, long readyTimeoutMs) {
        this.host = Objects.requireNonNull(host, "host");
        this.desired = Objects.requireNonNull(initialTarget, "initialTarget");
        if (idSeed < 0 || idSeed == Long.MAX_VALUE || readyTimeoutMs <= 0) {
            throw new IllegalArgumentException("Invalid transfer seed or timeout");
        }
        this.lastId = idSeed;
        this.readyTimeoutMs = readyTimeoutMs;
        active.addAll(sites(initialTarget));
    }

    public Snapshot getSnapshot() {
        return new Snapshot(this);
    }

    public void requestTarget(Target target, long nowMs) {
        desired = Objects.requireNonNull(target, "target");
        now = nowMs;
        if (phase == Phase.PREPARING && destination != desired) {
            cancelPreparation();
        }
        if (phase == Phase.IDLE) reconcile();
        else publish();
    }

    public void onReady(long id, Site site) {
        if (phase != Phase.PREPARING || id != transferId || !preparing.remove(site)) return;
        if (preparing.isEmpty()) beginDeparture();
        else publish();
    }

    public void onGone(long id, Site site) {
        if (phase != Phase.DEPARTING || id != transferId || !departing.remove(site)) return;
        active.remove(site);
        if (departing.isEmpty()) {
            if (desired != destination) {
                // The old source is now definitely gone. Do not show an obsolete destination.
                cancelPreparation();
                reconcile();
            } else {
                beginActivation();
            }
        } else {
            publish();
        }
    }

    public void onAck(long id, Site site) {
        if (phase != Phase.ACTIVATING || id != transferId || !activating.remove(site)) return;
        if (activating.isEmpty()) finish();
        else publish();
    }

    /** Losing a peer never grants permission to resurrect a source that may have departed. */
    public void onPeerLost(long nowMs) {
        now = nowMs;
        if (phase == Phase.PREPARING) cancelPreparation();
        else publish();
    }

    public void retryTick(long nowMs) {
        now = nowMs;
        if (phase == Phase.IDLE) {
            reconcile();
            return;
        }
        if (phase == Phase.PREPARING && nowMs - prepareStartedAt >= readyTimeoutMs) {
            cancelPreparation();
            return;
        }
        long id = transferId;
        Phase expected = phase;
        Set<Site> pending = phase == Phase.PREPARING ? immutable(preparing)
                : phase == Phase.DEPARTING ? immutable(departing) : immutable(activating);
        for (Site site : pending) {
            if (id != transferId || phase != expected) break;
            if (expected == Phase.PREPARING && preparing.contains(site)) host.prepare(id, site);
            else if (expected == Phase.DEPARTING && departing.contains(site)) host.depart(id, site);
            else if (expected == Phase.ACTIVATING && activating.contains(site)) host.activate(id, site);
        }
    }

    private void reconcile() {
        EnumSet<Site> targetSites = sites(desired);
        if (active.equals(targetSites)) {
            publish();
            return;
        }
        if (lastId == Long.MAX_VALUE) throw new IllegalStateException("Transfer IDs exhausted");
        transferId = ++lastId;
        destination = desired;
        preparedSites.clear();
        preparedSites.addAll(targetSites);
        preparedSites.removeAll(active);
        preparing.clear();
        preparing.addAll(preparedSites);
        departing.clear();
        activating.clear();
        prepareStartedAt = now;
        phase = Phase.PREPARING;
        if (preparing.isEmpty()) {
            beginDeparture();
            return;
        }
        long id = transferId;
        List<Site> commands = new ArrayList<>(preparing);
        publish();
        for (Site site : commands) {
            if (phase != Phase.PREPARING || transferId != id) break;
            if (preparing.contains(site)) host.prepare(id, site);
        }
    }

    private void beginDeparture() {
        departing.clear();
        departing.addAll(active);
        departing.removeAll(sites(destination));
        phase = Phase.DEPARTING;
        if (departing.isEmpty()) {
            beginActivation();
            return;
        }
        long id = transferId;
        List<Site> commands = new ArrayList<>(departing);
        publish();
        for (Site site : commands) {
            if (phase != Phase.DEPARTING || transferId != id) break;
            if (departing.contains(site)) host.depart(id, site);
        }
    }

    private void beginActivation() {
        activating.clear();
        activating.addAll(sites(destination));
        activating.removeAll(active);
        // Sending ACTIVATE commits ownership, even if the remote ACK never arrives.
        active.addAll(activating);
        phase = Phase.ACTIVATING;
        if (activating.isEmpty()) {
            finish();
            return;
        }
        long id = transferId;
        List<Site> commands = new ArrayList<>(activating);
        publish();
        for (Site site : commands) {
            if (phase != Phase.ACTIVATING || transferId != id) break;
            if (activating.contains(site)) host.activate(id, site);
        }
    }

    private void finish() {
        phase = Phase.IDLE;
        destination = null;
        preparedSites.clear();
        preparing.clear();
        departing.clear();
        activating.clear();
        reconcile();
    }

    private void cancelPreparation() {
        long id = transferId;
        List<Site> commands = new ArrayList<>(preparedSites);
        phase = Phase.IDLE;
        destination = null;
        preparedSites.clear();
        preparing.clear();
        departing.clear();
        activating.clear();
        publish();
        for (Site site : commands) host.cancelPrepare(id, site);
    }

    private void publish() {
        host.changed(new Snapshot(this));
    }

    private static EnumSet<Site> sites(Target target) {
        EnumSet<Site> result = EnumSet.noneOf(Site.class);
        for (Site site : Site.values()) if (target.includes(site)) result.add(site);
        return result;
    }

    private static Set<Site> immutable(Set<Site> input) {
        EnumSet<Site> copy = EnumSet.noneOf(Site.class);
        copy.addAll(input);
        return Collections.unmodifiableSet(copy);
    }
}
