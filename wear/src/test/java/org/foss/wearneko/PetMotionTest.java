// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PetMotionTest {
    @Test public void allSpriteCornersRemainInsideCircleDuringLongWander() {
        for (int seed = 0; seed < 20; seed++) {
            PetMotion motion = new PetMotion(450f, 450f, 64f, 115.2f, seed);
            for (int frame = 0; frame < 10000; frame++) {
                motion.advance(0.125f);
                float x = motion.getX();
                float y = motion.getY();
                assertInsideCircle(x, y, 225f, 225f, 221f);
                assertInsideCircle(x + 64f, y, 225f, 225f, 221f);
                assertInsideCircle(x, y + 64f, 225f, 225f, 221f);
                assertInsideCircle(x + 64f, y + 64f, 225f, 225f, 221f);
                assertTrue(motion.getDirection() >= 0 && motion.getDirection() < 8);
            }
        }
    }

    @Test public void staleTimeAfterSleepCannotJumpAcrossScreen() {
        PetMotion normal = new PetMotion(450f, 450f, 64f, 115.2f, 17L);
        PetMotion stalled = new PetMotion(450f, 450f, 64f, 115.2f, 17L);
        normal.advance(0.25f);
        stalled.advance(60f * 60f);
        assertEquals(normal.getX(), stalled.getX(), 0.0001f);
        assertEquals(normal.getY(), stalled.getY(), 0.0001f);
        assertEquals(normal.getBehavior(), stalled.getBehavior());
    }

    @Test public void noTimeOrInvalidTimeLeavesEverythingStill() {
        PetMotion motion = new PetMotion(450f, 450f, 64f, 115.2f, 5L);
        float initialX = motion.getX();
        float initialY = motion.getY();
        motion.advance(0f);
        motion.advance(-1f);
        motion.advance(Float.NaN);
        motion.advance(Float.POSITIVE_INFINITY);
        assertEquals(initialX, motion.getX(), 0f);
        assertEquals(initialY, motion.getY(), 0f);
        assertEquals(PetMotion.Behavior.WALK, motion.getBehavior());
    }

    @Test public void tapPausesThenResumesAutonomousWalking() {
        PetMotion motion = new PetMotion(450f, 450f, 64f, 115.2f, 123L);
        motion.advance(0.125f);
        motion.reactToTap();
        float x = motion.getX();
        float y = motion.getY();
        for (int tick = 0; tick < 11; tick++) motion.advance(0.125f);
        assertEquals(PetMotion.Behavior.GROOM, motion.getBehavior());
        assertEquals(x, motion.getX(), 0f);
        assertEquals(y, motion.getY(), 0f);
        motion.advance(0.125f);
        assertEquals(PetMotion.Behavior.WALK, motion.getBehavior());
        motion.advance(0.125f);
        assertTrue(motion.getX() != x || motion.getY() != y);
    }

    @Test public void allAwakeBehaviorsOccurAndResumeWalking() {
        PetMotion motion = new PetMotion(450f, 450f, 64f, 115.2f, 42L);
        boolean[] seen = new boolean[PetMotion.Behavior.values().length];
        int transitionsToWalk = 0;
        PetMotion.Behavior previous = motion.getBehavior();
        for (int frame = 0; frame < 10000; frame++) {
            motion.advance(0.125f);
            seen[motion.getBehavior().ordinal()] = true;
            if (previous != PetMotion.Behavior.WALK && motion.getBehavior() == PetMotion.Behavior.WALK) {
                transitionsToWalk++;
            }
            previous = motion.getBehavior();
        }
        for (boolean stateSeen : seen) assertTrue(stateSeen);
        assertTrue(transitionsToWalk > 10);
    }

    @Test public void boundsChangeResetsPositionInsideNewCircle() {
        PetMotion motion = new PetMotion(450f, 450f, 64f, 115.2f, 1L);
        for (int frame = 0; frame < 100; frame++) motion.advance(0.125f);
        motion.setBounds(320f, 360f, 48f);
        assertEquals(136f, motion.getX(), 0f);
        assertEquals(156f, motion.getY(), 0f);
        for (int frame = 0; frame < 1000; frame++) {
            motion.advance(0.125f);
            assertInsideCircle(motion.getX(), motion.getY(), 160f, 180f, 156f);
            assertInsideCircle(motion.getX() + 48f, motion.getY() + 48f, 160f, 180f, 156f);
        }
    }

    @Test public void tinyDisplayDoesNotProduceNaNOrOutwardDrift() {
        PetMotion motion = new PetMotion(32f, 32f, 32f, 57.6f, 1L);
        for (int frame = 0; frame < 100; frame++) motion.advance(0.125f);
        assertEquals(0f, motion.getX(), 0f);
        assertEquals(0f, motion.getY(), 0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidBounds() {
        new PetMotion(Float.NaN, 450f, 64f, 115.2f, 1L);
    }

    private static void assertInsideCircle(float x, float y, float cx, float cy, float radius) {
        assertTrue("corner outside safe circle", Math.hypot(x - cx, y - cy) <= radius + 0.001f);
    }

    @Test public void syncedPlacementClampsToSafeRoundDisplay() {
        PetMotion motion = new PetMotion(450f, 450f, 32f, 115.2f, 19L);
        for (float[] position : new float[][]{{-400f, -400f}, {900f, 450f}, {225f, 225f}}) {
            motion.placeAt(position[0], position[1]);
            assertInsideCircle(motion.getX(), motion.getY(), 225f, 225f, 221f);
            assertInsideCircle(motion.getX()+32f, motion.getY()+32f, 225f, 225f, 221f);
        }
    }
}
