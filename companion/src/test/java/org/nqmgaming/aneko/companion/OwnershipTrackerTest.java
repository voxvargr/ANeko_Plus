package org.nqmgaming.aneko.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.nqmgaming.aneko.companion.HandoffStateMachine.Site.PHONE;
import static org.nqmgaming.aneko.companion.HandoffStateMachine.Site.WATCH;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

public class OwnershipTrackerTest {
    private static final class MemoryStore implements OwnershipTracker.Store {
        String durable = "phone";
        String memory = "phone";
        boolean fail;
        int writes;
        @Override public String readOwner() { return memory; }
        @Override public boolean writeOwner(String owner) {
            writes++;
            memory = owner;
            if (fail) return false;
            durable = owner;
            return true;
        }
    }

    @Test public void savedRemoteGrantPreventsPhoneAppearanceAfterRestartEvenWithoutAck() {
        MemoryStore store = new MemoryStore();
        OwnershipTracker firstSession = new OwnershipTracker(store);
        assertTrue(firstSession.record(EnumSet.of(WATCH)));
        OwnershipTracker restarted = new OwnershipTracker(store);
        assertFalse(restarted.phoneMayStart("phone"));
        assertFalse(restarted.phoneMayStart("auto"));
        assertFalse(restarted.phoneMayStart("watch"));
        assertFalse(restarted.phoneMayStart("both"));
    }

    @Test public void formerBothGrantRequiresFreshSynchronizationInSingleDeviceModes() {
        MemoryStore store = new MemoryStore();
        store.durable = "both";
        store.memory = "both";
        OwnershipTracker tracker = new OwnershipTracker(store);
        assertTrue(tracker.phoneMayStart("both"));
        assertFalse(tracker.phoneMayStart("phone"));
        assertFalse(tracker.phoneMayStart("auto"));
        assertFalse(tracker.phoneMayStart("watch"));
        assertTrue(tracker.record(EnumSet.of(PHONE)));
        assertTrue(tracker.phoneMayStart("auto"));
    }

    @Test public void unchangedOwnerDoesNotWriteOnEverySnapshot() {
        MemoryStore store = new MemoryStore();
        OwnershipTracker tracker = new OwnershipTracker(store);
        for (int i = 0; i < 100; i++) assertTrue(tracker.record(EnumSet.of(PHONE)));
        assertEquals(0, store.writes);
        assertTrue(tracker.record(EnumSet.of(WATCH)));
        for (int i = 0; i < 100; i++) assertTrue(tracker.record(EnumSet.of(WATCH)));
        assertEquals(1, store.writes);
    }

    @Test public void failedCommitMemoryValueCannotMasqueradeAsDurableOwnership() {
        MemoryStore store = new MemoryStore();
        OwnershipTracker tracker = new OwnershipTracker(store);
        store.fail = true;
        assertFalse(tracker.record(EnumSet.of(WATCH)));
        assertEquals("watch", store.memory); // SharedPreferences updates memory before disk.
        assertEquals("phone", store.durable);
        assertFalse(tracker.record(EnumSet.of(WATCH)));
        assertEquals(2, store.writes);
        store.fail = false;
        assertTrue(tracker.record(EnumSet.of(PHONE)));
        assertEquals("phone", store.memory);
        assertEquals("phone", store.durable);
    }

    @Test public void failedJournalWriteBlocksActivationUntilDurableRetrySucceeds() {
        MemoryStore store = new MemoryStore();
        OwnershipTracker tracker = new OwnershipTracker(store);
        List<String> effects = new ArrayList<>();
        HandoffStateMachine[] machine = new HandoffStateMachine[1];
        machine[0] = new HandoffStateMachine(new HandoffStateMachine.Host() {
            @Override public void prepare(long id, HandoffStateMachine.Site site) { }
            @Override public void depart(long id, HandoffStateMachine.Site site) { effects.add("depart"); }
            @Override public void activate(long id, HandoffStateMachine.Site site) {
                if (tracker.record(machine[0].getSnapshot().active)) effects.add("activate:" + site);
            }
            @Override public void cancelPrepare(long id, HandoffStateMachine.Site site) { }
            @Override public void changed(HandoffStateMachine.Snapshot snapshot) { tracker.record(snapshot.active); }
        }, HandoffStateMachine.Target.PHONE, 100, 1_000);
        machine[0].requestTarget(HandoffStateMachine.Target.WATCH, 0);
        machine[0].onReady(101, WATCH);
        store.fail = true;
        machine[0].onGone(101, PHONE);
        assertEquals(java.util.Collections.singletonList("depart"), effects);
        assertEquals("phone", store.durable);
        machine[0].retryTick(100);
        assertEquals(1, effects.size());
        store.fail = false;
        machine[0].retryTick(200);
        assertEquals("activate:WATCH", effects.get(1));
        assertEquals("watch", store.durable);
        assertFalse(new OwnershipTracker(store).phoneMayStart("auto"));
    }

    @Test public void unknownOrEmptyOwnershipNeverAuthorizesOptimisticPhoneStartup() {
        MemoryStore store = new MemoryStore();
        store.durable = "corrupt";
        store.memory = "corrupt";
        OwnershipTracker tracker = new OwnershipTracker(store);
        assertFalse(tracker.phoneMayStart("auto"));
        assertTrue(tracker.record(EnumSet.noneOf(HandoffStateMachine.Site.class)));
        assertEquals("none", store.durable);
        assertFalse(tracker.phoneMayStart("phone"));
    }
}
