// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AttentionCycleTest {
    @Test public void continuousAwakeFramesLandExactlyOnce() {
        AttentionCycle cycle = new AttentionCycle();
        cycle.start(1000);
        for (long now=1050; now<1450; now+=50) {
            assertEquals(AttentionCycle.Update.ACTIVE, cycle.advance(now));
        }
        assertEquals(AttentionCycle.Update.LANDED, cycle.advance(1450));
        assertFalse(cycle.isRunning());
        assertEquals(AttentionCycle.Update.IDLE, cycle.advance(1500));
    }

    @Test public void sleepOrOwnershipCancellationCannotReplayOnWake() {
        AttentionCycle cycle = new AttentionCycle();
        cycle.start(1000);
        assertEquals(AttentionCycle.Update.ACTIVE, cycle.advance(1100));
        cycle.cancel();
        assertEquals(AttentionCycle.Update.IDLE, cycle.advance(60_000));
        assertFalse(cycle.isRunning());
    }

    @Test public void overdueCallbackAfterSchedulingStallCancelsInsteadOfLanding() {
        AttentionCycle cycle = new AttentionCycle();
        cycle.start(1000);
        assertEquals(AttentionCycle.Update.ACTIVE, cycle.advance(1100));
        assertEquals(AttentionCycle.Update.CANCELLED, cycle.advance(9000));
        assertEquals(AttentionCycle.Update.IDLE, cycle.advance(9050));
    }

    @Test public void freshAttentionAfterWakeStartsFromNewTime() {
        AttentionCycle cycle = new AttentionCycle();
        cycle.start(1000);
        cycle.cancel();
        cycle.start(50_000);
        assertEquals(AttentionCycle.Update.ACTIVE, cycle.advance(50_050));
        assertEquals(50f/450f, cycle.progress(50_050), 0.0001f);
    }

    @Test public void clockRegressionCancelsRatherThanAdvancingEffect() {
        AttentionCycle cycle = new AttentionCycle();
        cycle.start(1000);
        cycle.advance(1100);
        assertEquals(AttentionCycle.Update.CANCELLED, cycle.advance(900));
    }
}
