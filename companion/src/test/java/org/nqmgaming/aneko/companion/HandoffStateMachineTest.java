package org.nqmgaming.aneko.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.nqmgaming.aneko.companion.HandoffStateMachine.Phase.*;
import static org.nqmgaming.aneko.companion.HandoffStateMachine.Site.*;
import static org.nqmgaming.aneko.companion.HandoffStateMachine.Target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

public class HandoffStateMachineTest {
    private static class RecordingHost implements HandoffStateMachine.Host {
        final List<String> commands = new ArrayList<>();
        @Override public void prepare(long id, HandoffStateMachine.Site site) { commands.add("prepare:" + id + ":" + site); }
        @Override public void depart(long id, HandoffStateMachine.Site site) { commands.add("depart:" + id + ":" + site); }
        @Override public void activate(long id, HandoffStateMachine.Site site) { commands.add("activate:" + id + ":" + site); }
        @Override public void cancelPrepare(long id, HandoffStateMachine.Site site) { commands.add("cancel:" + id + ":" + site); }
        @Override public void changed(HandoffStateMachine.Snapshot snapshot) { }
    }

    private final RecordingHost host = new RecordingHost();
    private final HandoffStateMachine machine = new HandoffStateMachine(host, Target.PHONE, 100, 1_000);

    @Test public void destinationCannotAppearBeforeSourceReportsGone() {
        machine.requestTarget(Target.WATCH, 0);
        assertEquals(Arrays.asList("prepare:101:WATCH"), host.commands);
        assertEquals(EnumSet.of(PHONE), machine.getSnapshot().active);
        machine.onReady(101, WATCH);
        assertEquals(DEPARTING, machine.getSnapshot().phase);
        assertEquals(Arrays.asList("prepare:101:WATCH", "depart:101:PHONE"), host.commands);
        machine.onAck(101, WATCH); // Even an out-of-order ACK cannot finish the handoff.
        assertEquals(DEPARTING, machine.getSnapshot().phase);
        machine.onGone(101, PHONE);
        assertEquals("activate:101:WATCH", host.commands.get(2));
        assertEquals(EnumSet.of(WATCH), machine.getSnapshot().active);
        machine.onAck(101, WATCH);
        assertEquals(IDLE, machine.getSnapshot().phase);
    }

    @Test public void lostReadyTimesOutWithoutHidingSourceAndRetryUsesFreshEpoch() {
        machine.requestTarget(Target.WATCH, 0);
        machine.retryTick(500);
        assertEquals(Arrays.asList("prepare:101:WATCH", "prepare:101:WATCH"), host.commands);
        machine.retryTick(1_000);
        assertEquals(IDLE, machine.getSnapshot().phase);
        assertEquals(EnumSet.of(PHONE), machine.getSnapshot().active);
        assertEquals("cancel:101:WATCH", host.commands.get(2));
        machine.onReady(101, WATCH);
        assertEquals(3, host.commands.size());
        machine.retryTick(1_001);
        assertEquals("prepare:102:WATCH", host.commands.get(3));
        assertFalse(host.commands.stream().anyMatch(command -> command.startsWith("depart:")));
    }

    @Test public void duplicateAndStaleEventsHaveNoEffects() {
        machine.requestTarget(Target.WATCH, 0);
        machine.onGone(101, PHONE);
        machine.onReady(100, WATCH);
        machine.onReady(101, PHONE);
        assertEquals(1, host.commands.size());
        machine.onReady(101, WATCH);
        machine.onReady(101, WATCH);
        machine.onGone(100, PHONE);
        machine.onGone(101, WATCH);
        assertEquals(2, host.commands.size());
        machine.onGone(101, PHONE);
        machine.onGone(101, PHONE);
        assertEquals(3, host.commands.size());
        machine.onAck(101, WATCH);
        machine.onAck(101, WATCH);
        assertEquals(3, host.commands.size());
    }

    @Test public void reversalBeforeReadyCancelsWithoutDeparting() {
        machine.requestTarget(Target.WATCH, 0);
        machine.requestTarget(Target.PHONE, 10);
        machine.onReady(101, WATCH);
        assertEquals(Arrays.asList("prepare:101:WATCH", "cancel:101:WATCH"), host.commands);
        assertEquals(IDLE, machine.getSnapshot().phase);
        assertEquals(EnumSet.of(PHONE), machine.getSnapshot().active);
    }

    @Test public void reversalDuringDepartureWaitsForGoneAndSkipsObsoleteDestination() {
        machine.requestTarget(Target.WATCH, 0);
        machine.onReady(101, WATCH);
        machine.requestTarget(Target.PHONE, 20);
        assertEquals(2, host.commands.size());
        machine.onGone(101, PHONE);
        assertEquals(Arrays.asList("prepare:101:WATCH", "depart:101:PHONE",
                "cancel:101:WATCH", "prepare:102:PHONE"), host.commands);
        machine.onReady(101, WATCH);
        machine.onGone(101, PHONE);
        assertEquals(PREPARING, machine.getSnapshot().phase);
        machine.onReady(102, PHONE);
        assertEquals("activate:102:PHONE", host.commands.get(4));
        assertFalse(host.commands.contains("activate:101:WATCH"));
        machine.onAck(102, PHONE);
        assertEquals(EnumSet.of(PHONE), machine.getSnapshot().active);
    }

    @Test public void lostActivationAckNeverResurrectsSourceAndRetriesSameActivation() {
        machine.requestTarget(Target.WATCH, 0);
        machine.onReady(101, WATCH);
        machine.onGone(101, PHONE);
        machine.onPeerLost(100);
        machine.retryTick(2_000);
        machine.retryTick(5_000);
        assertEquals(ACTIVATING, machine.getSnapshot().phase);
        assertEquals(EnumSet.of(WATCH), machine.getSnapshot().active);
        assertEquals("activate:101:WATCH", host.commands.get(4));
        assertFalse(host.commands.stream().anyMatch(command -> command.startsWith("activate:") && command.endsWith("PHONE")));
    }

    @Test public void reversalDuringUncertainActivationWaitsForAckThenFreshHandoff() {
        machine.requestTarget(Target.WATCH, 0);
        machine.onReady(101, WATCH);
        machine.onGone(101, PHONE);
        machine.requestTarget(Target.PHONE, 50);
        assertEquals(ACTIVATING, machine.getSnapshot().phase);
        assertEquals(3, host.commands.size());
        machine.onAck(101, WATCH);
        assertEquals("prepare:102:PHONE", host.commands.get(3));
        machine.onReady(102, PHONE);
        assertEquals("depart:102:WATCH", host.commands.get(4));
        assertEquals(EnumSet.of(WATCH), machine.getSnapshot().active);
        machine.onGone(102, WATCH);
        assertEquals("activate:102:PHONE", host.commands.get(5));
    }

    @Test public void bothModeAddsOnlyMissingSiteAndRemovingItNeedsNoNewActivation() {
        machine.requestTarget(Target.BOTH, 0);
        machine.onReady(101, WATCH);
        assertEquals(Arrays.asList("prepare:101:WATCH", "activate:101:WATCH"), host.commands);
        assertEquals(EnumSet.of(PHONE, WATCH), machine.getSnapshot().active);
        machine.onAck(101, WATCH);
        machine.requestTarget(Target.PHONE, 20);
        assertEquals("depart:102:WATCH", host.commands.get(2));
        machine.onGone(102, WATCH);
        assertEquals(IDLE, machine.getSnapshot().phase);
        assertEquals(EnumSet.of(PHONE), machine.getSnapshot().active);
        assertEquals(3, host.commands.size());
    }

    @Test public void peerLossBeforeDepartureKeepsSourceAndInvalidatesOldReady() {
        machine.requestTarget(Target.WATCH, 0);
        machine.onPeerLost(50);
        machine.onReady(101, WATCH);
        assertEquals(IDLE, machine.getSnapshot().phase);
        assertEquals(EnumSet.of(PHONE), machine.getSnapshot().active);
        assertEquals(Arrays.asList("prepare:101:WATCH", "cancel:101:WATCH"), host.commands);
    }

    @Test public void peerLossAfterDepartureDoesNotAssumeSourceGone() {
        HandoffStateMachine watchSource = new HandoffStateMachine(host, Target.WATCH, 200, 1_000);
        watchSource.requestTarget(Target.PHONE, 0);
        watchSource.onReady(201, PHONE);
        watchSource.onPeerLost(20);
        watchSource.retryTick(3_000);
        assertEquals(DEPARTING, watchSource.getSnapshot().phase);
        assertEquals(EnumSet.of(WATCH), watchSource.getSnapshot().active);
        assertEquals(Arrays.asList("prepare:201:PHONE", "depart:201:WATCH", "depart:201:WATCH"), host.commands);
        watchSource.onGone(201, WATCH);
        assertEquals("activate:201:PHONE", host.commands.get(3));
    }

    @Test public void synchronousAsleepCallbacksPreservePrepareGoneActivateOrdering() {
        List<String> effects = new ArrayList<>();
        HandoffStateMachine[] reference = new HandoffStateMachine[1];
        RecordingHost immediate = new RecordingHost() {
            @Override public void prepare(long id, HandoffStateMachine.Site site) {
                effects.add("ready:" + site);
                reference[0].onReady(id, site);
            }
            @Override public void depart(long id, HandoffStateMachine.Site site) {
                effects.add("hide:" + site);
                reference[0].onGone(id, site);
            }
            @Override public void activate(long id, HandoffStateMachine.Site site) {
                effects.add("show:" + site);
                reference[0].onAck(id, site);
            }
        };
        reference[0] = new HandoffStateMachine(immediate, Target.PHONE, 300, 1_000);
        reference[0].requestTarget(Target.WATCH, 0);
        assertEquals(Arrays.asList("ready:WATCH", "hide:PHONE", "show:WATCH"), effects);
        assertEquals(IDLE, reference[0].getSnapshot().phase);
    }

    @Test public void latestQueuedRequestWinsAndBothDestinationsMustBeReady() {
        machine.requestTarget(Target.WATCH, 0);
        machine.onReady(101, WATCH);
        machine.requestTarget(Target.PHONE, 10);
        machine.requestTarget(Target.BOTH, 11);
        machine.onGone(101, PHONE);
        assertEquals(PREPARING, machine.getSnapshot().phase);
        machine.onReady(102, PHONE);
        assertEquals(PREPARING, machine.getSnapshot().phase);
        assertFalse(host.commands.contains("activate:102:PHONE"));
        machine.onReady(102, WATCH);
        assertTrue(host.commands.contains("activate:102:PHONE"));
        assertTrue(host.commands.contains("activate:102:WATCH"));
        machine.onAck(102, PHONE);
        assertEquals(ACTIVATING, machine.getSnapshot().phase);
        machine.onAck(102, WATCH);
        assertEquals(IDLE, machine.getSnapshot().phase);
        assertEquals(EnumSet.of(PHONE, WATCH), machine.getSnapshot().active);
    }
}
