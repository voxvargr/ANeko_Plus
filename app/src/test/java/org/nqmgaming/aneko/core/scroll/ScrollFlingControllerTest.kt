package org.nqmgaming.aneko.core.scroll

import org.junit.Assert.*
import org.junit.Test
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect
import kotlin.math.hypot

class ScrollFlingControllerTest {
    private val viewport = MediaRect(0f, 0f, 1000f, 1000f)
    private val region = MediaRect(0f, 200f, 1000f, 900f)
    private val center = MediaPoint(500f, 500f)
    private fun event(time: Long, dy: Float = 100f, dx: Float = 0f,
        bounds: MediaRect = region, pkg: String = "org.example.reader", window: Int = 7) =
        ScrollImpulse(bounds, dx, dy, time, pkg, window)
    private fun offer(controller: ScrollFlingController, impulse: ScrollImpulse,
        position: MediaPoint = center, now: Long = impulse.uptimeMs): Boolean =
        controller.onScroll(impulse, position, 20f, 20f, viewport, now)
    private fun activate(controller: ScrollFlingController, position: MediaPoint = center,
        dx: Float = 0f, dy: Float = 100f, bounds: MediaRect = region) {
        assertFalse(offer(controller, event(1000, dy, dx, bounds), position))
        assertTrue(offer(controller, event(1050, dy, dx, bounds), position))
    }

    @Test fun fastVerticalAndHorizontalScrollPushInOppositeDirectionsWithoutTeleporting() {
        for ((dx, dy) in listOf(0f to 100f, 100f to 0f, -100f to 0f, 0f to -100f)) {
            val controller = ScrollFlingController()
            activate(controller, dx = dx, dy = dy)
            val initial = controller.step(center, 20f, 20f, viewport, 1050)!!
            assertEquals(center, initial.target)
            val moved = controller.step(center, 20f, 20f, viewport, 1175)!!
            assertTrue((moved.target.x-center.x)*dx + (moved.target.y-center.y)*dy < 0f)
            assertTrue(hypot(moved.target.x-center.x, moved.target.y-center.y) <= 48.001f)
        }
    }

    @Test fun fixedHeaderAndAdjacentPaneDoNotMoveEvenWhenSpritePartlyOverlaps() {
        for (position in listOf(MediaPoint(500f, 190f), MediaPoint(500f, 200f), MediaPoint(500f, 950f))) {
            val controller = ScrollFlingController()
            assertFalse(offer(controller, event(1000), position))
            assertFalse(offer(controller, event(1050), position))
            assertFalse(controller.isActive)
        }
        val controller = ScrollFlingController()
        val leftPane = MediaRect(0f, 200f, 450f, 900f)
        assertFalse(offer(controller, event(1000, bounds = leftPane)))
        assertFalse(offer(controller, event(1050, bounds = leftPane)))
        assertNull(controller.step(center, 20f, 20f, viewport, 1175))
    }

    @Test fun slowAndMinorScrollNeverAccumulateIntoAFling() {
        val controller = ScrollFlingController()
        for (time in 1000L..2000L step 50) assertFalse(offer(controller, event(time, dy = 2f)))
        assertFalse(controller.isActive)
    }

    @Test fun coalescedSubFrameReportsMatchOneEquivalentDeltaAndDuplicatesDoNotAmplify() {
        val coalesced = ScrollFlingController()
        val batched = ScrollFlingController()
        offer(coalesced, event(1000)); offer(batched, event(1000))
        val small = event(1008, dy = 20f)
        assertFalse(offer(coalesced, small))
        repeat(4) { assertFalse(offer(coalesced, small)) }
        assertTrue(offer(coalesced, event(1032, dy = 25f)))
        assertTrue(offer(batched, event(1032, dy = 45f)))
        val a = coalesced.step(center, 20f, 20f, viewport, 1157)!!
        val b = batched.step(center, 20f, 20f, viewport, 1157)!!
        assertEquals(a.target.y, b.target.y, 0.001f)
        assertEquals(a.velocity.y, b.velocity.y, 0.001f)
    }

    @Test fun invalidStaleFutureAndOutOfOrderReportsCannotStartOrReplaceAnImpulse() {
        val controller = ScrollFlingController()
        offer(controller, event(1000))
        assertFalse(offer(controller, event(1050), now = 2000))
        assertFalse(offer(controller, event(1050), now = 1049))
        assertFalse(offer(controller, event(1050, dy = Float.NaN)))
        assertFalse(offer(controller, event(1050, dy = Float.MAX_VALUE)))
        assertFalse(offer(controller, event(1050, dy = 0f)))
        assertTrue(offer(controller, event(1050)))
        assertFalse(offer(controller, event(1020, pkg = "org.example.oldwindow"), now = 1050))
        assertTrue(controller.isActive)
    }

    @Test fun changingWindowNeedsFreshTimingAndCannotCarryOldMomentumAcrossApps() {
        val controller = ScrollFlingController()
        activate(controller)
        assertFalse(offer(controller, event(1100, window = 8)))
        assertFalse(controller.isActive)
        assertTrue(offer(controller, event(1150, window = 8)))
    }

    @Test fun frictionDecaysAndStopsWithoutLeavingTheScreen() {
        val controller = ScrollFlingController()
        activate(controller)
        var position = center
        var previousSpeed = Float.POSITIVE_INFINITY
        repeat(15) { index ->
            val step = controller.step(position, 20f, 20f, viewport, 1050+(index+1)*125L)
            if (step != null) {
                val speed = hypot(step.velocity.x, step.velocity.y)
                assertTrue(speed <= previousSpeed)
                assertTrue(hypot(step.target.x-position.x, step.target.y-position.y) <= 48.001f)
                assertTrue(step.target.x in 20f..980f && step.target.y in 20f..980f)
                previousSpeed = speed
                position = step.target
            }
        }
        assertFalse(controller.isActive)
        assertNull(controller.step(position, 20f, 20f, viewport, 4000))
    }

    @Test fun continuousFastReportsStillRespectThePerFlingTravelBudget() {
        val controller = ScrollFlingController()
        var position = MediaPoint(500f, 750f)
        activate(controller, position, dy = 200f)
        var distance = 0f
        var time = 1050L
        for (tick in 0 until 20) {
            if (!controller.isActive) break
            time += 125
            assertTrue(offer(controller, event(time, dy = 200f), position))
            val step = controller.step(position, 20f, 20f, viewport, time)!!
            distance += hypot(step.target.x-position.x, step.target.y-position.y)
            position = step.target
            assertTrue("Fling exceeded its distance budget", distance <= 220.01f)
        }
        assertFalse("Continuous input must still exhaust this fling's distance budget", controller.isActive)
        assertEquals(220f, distance, 0.01f)
    }

    @Test fun edgeCollisionAndSchedulingStallStopRatherThanTeleport() {
        val controller = ScrollFlingController()
        val lowRegion = MediaRect(0f, 0f, 1000f, 900f)
        val position = MediaPoint(500f, 35f)
        activate(controller, position, bounds = lowRegion)
        val edge = controller.step(position, 20f, 20f, viewport, 1175)!!
        assertEquals(20f, edge.target.y, 0.001f)
        assertFalse(controller.isActive)
        val stalled = ScrollFlingController()
        activate(stalled)
        assertNull(stalled.step(center, 20f, 20f, viewport, 1500))
        assertFalse(stalled.isActive)
    }

    @Test fun explicitCancellationClearsTimingAsWellAsVelocity() {
        val controller = ScrollFlingController()
        activate(controller)
        controller.cancel()
        assertFalse(controller.isActive)
        assertFalse(offer(controller, event(1100)))
        assertNull(controller.step(center, 20f, 20f, viewport, 1150))
    }

    @Test fun anOverflowingViewportCannotProduceAnInfiniteTarget() {
        val controller = ScrollFlingController()
        val overflow = MediaRect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, 1000f)
        assertFalse(controller.onScroll(event(1000), center, 20f, 20f, overflow, 1000))
        assertFalse(controller.onScroll(event(1050), center, 20f, 20f, overflow, 1050))
        assertNull(controller.step(center, 20f, 20f, overflow, 1175))
    }
}
