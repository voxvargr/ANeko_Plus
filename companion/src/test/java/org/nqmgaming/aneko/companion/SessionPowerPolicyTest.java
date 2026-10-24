package org.nqmgaming.aneko.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionPowerPolicyTest {
    @Test public void aSleepingWatchNeverSchedulesEvenWithOwnershipOrAnUnsentModeRequest() {
        for (boolean connected : new boolean[] {false, true}) {
            for (boolean authorized : new boolean[] {false, true}) {
                for (boolean pending : new boolean[] {false, true}) {
                    SessionPowerPolicy.Plan plan = SessionPowerPolicy.plan(false, false,
                        connected, true, authorized, pending);
                    assertFalse(plan.runsTimers());
                    assertEquals(SessionPowerPolicy.NO_TIMER, plan.tickDelayMs);
                    assertEquals(SessionPowerPolicy.NO_TIMER, plan.discoverIntervalMs);
                    assertEquals(SessionPowerPolicy.NO_TIMER, plan.peerStaleMs);
                }
            }
        }
    }

    @Test public void thePhoneDoesNotPingOrExpireAKnownSleepingWatchDuringLongIdle() {
        for (boolean phoneAwake : new boolean[] {false, true}) {
            SessionPowerPolicy.Plan plan = SessionPowerPolicy.plan(true, phoneAwake,
                true, false, false, false);
            assertFalse(plan.runsTimers());
            // No tick means no periodic discovery, presence, or 12-second false peer-loss timeout.
            assertEquals(SessionPowerPolicy.NO_TIMER, plan.peerStaleMs);
        }
    }

    @Test public void aPendingPhoneTransferKeepsItsRetriesEvenWhenThePeerIsAsleep() {
        SessionPowerPolicy.Plan transfer = SessionPowerPolicy.plan(true, true,
            true, false, false, true);
        assertTrue(transfer.runsTimers());
        assertEquals(1_000L, transfer.tickDelayMs);
        assertEquals(4_000L, transfer.discoverIntervalMs);
        SessionPowerPolicy.Plan settled = SessionPowerPolicy.plan(true, true,
            true, false, false, false);
        assertFalse(settled.runsTimers());
    }

    @Test public void screenOnResumesPendingWatchRequestsWithoutAStaleSleepBacklog() {
        assertFalse(SessionPowerPolicy.plan(false, false, true, true, false, true).runsTimers());
        SessionPowerPolicy.Plan awake = SessionPowerPolicy.plan(false, true,
            true, true, false, true);
        assertTrue(awake.runsTimers());
        assertEquals(1_000L, awake.tickDelayMs);
        // After delivery, an awake unowned watch uses the slower idle schedule again.
        assertTrue(SessionPowerPolicy.plan(false, true, true, true, false, false).tickDelayMs
            > awake.tickDelayMs);
    }

    @Test public void idlePeerTimeoutAccommodatesSeveralMissedSlowHeartbeats() {
        SessionPowerPolicy.Plan phone = SessionPowerPolicy.plan(true, true, true, true, false, false);
        SessionPowerPolicy.Plan unownedWatch = SessionPowerPolicy.plan(false, true, true, true, false, false);
        assertTrue(phone.tickDelayMs >= 15_000L);
        assertTrue(unownedWatch.tickDelayMs >= 15_000L);
        assertTrue(phone.peerStaleMs >= 3 * unownedWatch.tickDelayMs);
        assertTrue(unownedWatch.peerStaleMs >= 3 * phone.tickDelayMs);
        assertTrue(phone.discoverIntervalMs > phone.tickDelayMs);
    }

    @Test public void phoneScreenOffStillServesAnAwakeWatchAndUnknownPeersCanReconnect() {
        assertTrue(SessionPowerPolicy.plan(true, false, true, true, false, false).runsTimers());
        SessionPowerPolicy.Plan searchingPhone = SessionPowerPolicy.plan(true, false,
            false, false, false, false);
        SessionPowerPolicy.Plan searchingWatch = SessionPowerPolicy.plan(false, true,
            false, false, false, false);
        assertEquals(4_000L, searchingPhone.discoverIntervalMs);
        assertEquals(4_000L, searchingWatch.discoverIntervalMs);
    }

    @Test public void ownershipDoesNotLetTheWatchBypassTheScreenGate() {
        SessionPowerPolicy.Plan visibleWatch = SessionPowerPolicy.plan(false, true,
            true, true, true, false);
        assertTrue(visibleWatch.runsTimers());
        assertTrue(visibleWatch.tickDelayMs >= 4_000L);
        assertFalse(SessionPowerPolicy.plan(false, false, true, true, true, false).runsTimers());
    }
}
