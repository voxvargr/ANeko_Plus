package org.nqmgaming.aneko.core.scroll

import org.junit.Assert.*
import org.junit.Test
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect

class ScrollDeltaTrackerTest {
    private val source = "org.example.reader:7:source42:0,200,1000,900"
    private fun sample(tracker: ScrollDeltaTracker, time: Long, x: Int = 0, y: Int = 0,
        dx: Int = -1, dy: Int = -1, key: String = source, now: Long = time) =
        tracker.observe(key, dx, dy, x, y, time, now)

    @Test fun absoluteOffsetsNeedABaselineAndPreserveRealPixelDirection() {
        val tracker = ScrollDeltaTracker()
        assertNull(sample(tracker, 1000, x = 100, y = 300))
        assertEquals(ScrollDelta(30f, -80f, 1050), sample(tracker, 1050, x = 130, y = 220))
    }

    @Test fun explicitDeltasWinWithoutLeavingAStaleAbsoluteBaseline() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 100)
        assertEquals(ScrollDelta(0f, 35f, 1050), sample(tracker, 1050, y = 200, dy = 35))
        assertEquals(ScrollDelta(0f, 20f, 1100), sample(tracker, 1100, y = 220, dy = 0))
    }

    @Test fun missingAxesFallbackIndependentlyOfAnExplicitOtherAxis() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, x = 100, y = 100)
        assertEquals(ScrollDelta(-40f, 70f, 1050), sample(tracker, 1050, x = 130, y = 170, dx = -40))
    }

    @Test fun zeroAndUndefinedDefaultsCannotManufactureMovement() {
        val tracker = ScrollDeltaTracker()
        repeat(5) { index ->
            assertNull(sample(tracker, 1000 + index * 50L, dx = if (index % 2 == 0) -1 else 0))
        }
        assertNull(sample(tracker, 1300, x = -1, y = -1))
        assertNull(sample(tracker, 1350, x = 100, y = 100))
        assertEquals(ScrollDelta(0f, -2f, 1400), sample(tracker, 1400, x = 100, y = 100, dy = -2))
    }

    @Test fun sourceAndWindowChangesCannotBorrowAnotherPanesAbsolutePosition() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 500)
        assertNull(sample(tracker, 1050, y = 20, key = "org.example.reader:8:source42:0,200,1000,900"))
        assertEquals(ScrollDelta(0f, 30f, 1100), sample(tracker, 1100, y = 50,
            key = "org.example.reader:8:source42:0,200,1000,900"))
        assertNull(sample(tracker, 1150, y = 900, key = "org.example.reader:8:source43:0,200,450,900"))
    }

    @Test fun freshExplicitMovementCanBeUsedWithoutAbsoluteCoordinates() {
        val tracker = ScrollDeltaTracker()
        assertEquals(ScrollDelta(0f, 45f, 1000), sample(tracker, 1000, x = -1, y = -1, dy = 45))
    }

    @Test fun ambiguousNegativeOneDeltaRequiresAbsoluteEvidenceWhileOtherNegativeDeltasRemainValid() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 100)
        assertNull(sample(tracker, 1050, y = 100, dy = -1))
        assertEquals(ScrollDelta(0f, -1f, 1100), sample(tracker, 1100, y = 99, dy = -1))
        assertEquals(ScrollDelta(0f, -30f, 1150), sample(tracker, 1150, y = -1, dy = -30))
    }

    @Test fun oldFutureAndOutOfOrderReportsCannotReplaceAFreshBaseline() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 2000, y = 100)
        assertNull(sample(tracker, 1000, y = 900, now = 2000))
        assertNull(sample(tracker, 2100, y = 900, now = 2000))
        assertNull(sample(tracker, 1990, y = 900, key = "oldsource", now = 2000))
        assertEquals(ScrollDelta(0f, 40f, 2050), sample(tracker, 2050, y = 140))
    }

    @Test fun idleGapAndExplicitResetRequireNewAbsoluteBaselines() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 100)
        assertNull(sample(tracker, 2000, y = 900))
        assertEquals(ScrollDelta(0f, 25f, 2050), sample(tracker, 2050, y = 925))
        tracker.clear()
        assertNull(sample(tracker, 2100, y = 10))
        assertEquals(ScrollDelta(0f, 20f, 2150), sample(tracker, 2150, y = 30))
    }

    @Test fun duplicateReportsDoNotReplayButNewMovementAtSameTimestampIsRetained() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 0)
        assertEquals(ScrollDelta(0f, 30f, 1050), sample(tracker, 1050, y = 30))
        assertNull(sample(tracker, 1050, y = 30))
        assertEquals(ScrollDelta(0f, 20f, 1050), sample(tracker, 1050, y = 50))
        assertEquals(ScrollDelta(0f, 15f, 1100), sample(tracker, 1100, y = 65, dy = 15))
        assertNull(sample(tracker, 1100, y = 65, dy = 15))
        assertEquals(ScrollDelta(0f, 15f, 1150), sample(tracker, 1150, y = 80, dy = 15))
    }

    @Test fun undefinedOrExtremePositionsNeverBecomeHugeFlingDeltas() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 100)
        assertNull(sample(tracker, 1050, x = Int.MIN_VALUE, y = Int.MAX_VALUE))
        assertNull(sample(tracker, 1100, y = 150))
        assertNull(sample(tracker, 1150, y = 500_000))
        assertEquals(ScrollDelta(0f, 50f, 1200), sample(tracker, 1200, y = 500_050))
    }

    @Test fun corruptExplicitValuesCanOnlyFallbackToBoundedObservedOffsets() {
        val tracker = ScrollDeltaTracker()
        sample(tracker, 1000, y = 100)
        assertEquals(ScrollDelta(0f, 20f, 1050), sample(tracker, 1050, y = 120, dy = Int.MIN_VALUE))
        assertNull(sample(tracker, 1100, y = 120, dy = Int.MAX_VALUE))
    }

    @Test fun missingEventTimestampUsesTheCurrentUptimeWithoutAcceptingInvalidSources() {
        val tracker = ScrollDeltaTracker()
        assertEquals(ScrollDelta(0f, 40f, 1000), sample(tracker, 0, dy = 40, now = 1000))
        assertNull(sample(tracker, 1050, dy = 40, key = ""))
        assertNull(sample(tracker, -1, dy = 40, now = 1050))
    }

    @Test fun absoluteOnlyAppReportsReachExistingRegionGatedFlingPhysics() {
        val tracker = ScrollDeltaTracker()
        val controller = ScrollFlingController()
        val bounds = MediaRect(0f, 200f, 1000f, 900f)
        val viewport = MediaRect(0f, 0f, 1000f, 1000f)
        val center = MediaPoint(500f, 500f)
        val header = ScrollFlingController()
        for ((time, y) in listOf(1000L to 0, 1050L to 100, 1100L to 200)) {
            val delta = sample(tracker, time, y = y) ?: continue
            val impulse = ScrollImpulse(bounds, delta.deltaX, delta.deltaY, delta.uptimeMs, "org.example.reader", 7)
            controller.onScroll(impulse, center, 20f, 20f, viewport, time)
            assertFalse(header.onScroll(impulse, MediaPoint(500f, 100f), 20f, 20f, viewport, time))
        }
        assertTrue(controller.isActive)
        assertTrue(controller.step(center, 20f, 20f, viewport, 1225)!!.target.y < center.y)
        assertFalse(header.isActive)
    }
}
