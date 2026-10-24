package org.nqmgaming.aneko.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaReactionControllerTest {
    private val viewport = MediaRect(0f, 0f, 400f, 600f)
    private val media = listOf(MediaRegion("video", MediaRect(100f, 100f, 300f, 300f)))
    private val controller = MediaReactionController()

    private fun update(
        center: MediaPoint,
        regions: List<MediaRegion> = media,
        allowApproach: Boolean = true,
        dragging: Boolean = false,
    ) = controller.update(center, 10f, 10f, regions, viewport, allowApproach = allowApproach, dragging = dragging)

    @Test
    fun `drop escapes immediately and finishes clearance before watching`() {
        val drop = update(MediaPoint(200f, 200f))!!
        assertEquals(MediaReactionKind.ESCAPE, drop.kind)
        assertEquals(MediaPoint(82f, 200f), drop.target)
        // The body is outside the media now, but still has three pixels of clearance to run.
        val clearing = update(MediaPoint(85f, 200f))!!
        assertEquals(MediaReactionKind.ESCAPE, clearing.kind)
        assertEquals(drop.target, clearing.target)
        val watching = update(drop.target)!!
        assertEquals(MediaReactionKind.WATCH, watching.kind)
        assertEquals(drop.target, watching.target)
        assertEquals(MediaPoint(200f, 200f), watching.lookAt)
    }

    @Test
    fun `chosen escape stays stable even when another edge becomes closer`() {
        val original = update(MediaPoint(200f, 200f))!!
        val next = update(MediaPoint(201f, 200f))!!
        assertEquals(MediaReactionKind.ESCAPE, next.kind)
        assertEquals(original.target, next.target)
    }

    @Test
    fun `moving media recomputes escape instead of keeping old boundary`() {
        update(MediaPoint(200f, 200f))
        val shifted = listOf(MediaRegion("video", MediaRect(180f, 100f, 380f, 300f)))
        val next = update(MediaPoint(200f, 200f), shifted)!!
        assertEquals(MediaReactionKind.ESCAPE, next.kind)
        assertEquals(MediaPoint(162f, 200f), next.target)
    }

    @Test
    fun `media moving beneath a stationary watching pet triggers immediate escape`() {
        val distant = listOf(MediaRegion("video", MediaRect(10f, 10f, 110f, 110f)))
        assertEquals(MediaReactionKind.WATCH, update(MediaPoint(200f, 200f), distant, false)!!.kind)
        val underneath = listOf(MediaRegion("video", MediaRect(150f, 150f, 250f, 250f)))
        val next = update(MediaPoint(200f, 200f), underneath, false)!!
        assertEquals(MediaReactionKind.ESCAPE, next.kind)
        assertEquals(MediaPoint(132f, 200f), next.target)
    }

    @Test
    fun `dragging suppresses reactions and release chooses a fresh escape`() {
        update(MediaPoint(200f, 200f))
        assertNull(update(MediaPoint(280f, 200f), dragging = true))
        val released = update(MediaPoint(280f, 200f))!!
        assertEquals(MediaReactionKind.ESCAPE, released.kind)
        assertEquals(MediaPoint(318f, 200f), released.target)
    }

    @Test
    fun `empty detection and reset clear a previous escape`() {
        update(MediaPoint(200f, 200f))
        assertNull(update(MediaPoint(200f, 200f), emptyList()))
        assertEquals(MediaPoint(318f, 200f), update(MediaPoint(280f, 200f))!!.target)
        controller.reset()
        assertEquals(MediaPoint(82f, 200f), update(MediaPoint(120f, 200f))!!.target)
    }

    @Test
    fun `quiet mode stays put outside but still runs fully clear after a drop`() {
        val center = MediaPoint(50f, 200f)
        val still = update(center, allowApproach = false)!!
        assertEquals(MediaReactionKind.WATCH, still.kind)
        assertEquals(center, still.target)
        val escape = update(MediaPoint(200f, 200f), allowApproach = false)!!
        assertEquals(MediaReactionKind.ESCAPE, escape.kind)
        assertEquals(MediaReactionKind.ESCAPE, update(MediaPoint(85f, 200f), allowApproach = false)!!.kind)
        assertEquals(MediaReactionKind.WATCH, update(escape.target, allowApproach = false)!!.kind)
    }

    @Test
    fun `approach becomes watching when perimeter is reached within one pixel`() {
        val approach = update(MediaPoint(50f, 200f))!!
        assertEquals(MediaReactionKind.APPROACH, approach.kind)
        assertEquals(MediaPoint(82f, 200f), approach.target)
        val watching = update(MediaPoint(81.5f, 200f))!!
        assertEquals(MediaReactionKind.WATCH, watching.kind)
        assertEquals(MediaPoint(81.5f, 200f), watching.target)
    }

    @Test
    fun `region ordering alone does not change a chosen escape`() {
        val regions = media + MediaRegion("other", MediaRect(0f, 450f, 120f, 550f))
        val escape = update(MediaPoint(200f, 200f), regions)!!
        assertEquals(escape.target, update(MediaPoint(201f, 200f), regions.reversed())!!.target)
    }

    @Test
    fun `invalid input and impossible full screen escape produce no reaction`() {
        assertNull(update(MediaPoint(Float.NaN, 200f)))
        assertNull(update(MediaPoint(200f, 200f), listOf(MediaRegion("full", viewport))))
        assertNull(update(MediaPoint(200f, 200f), listOf(MediaRegion("offscreen", MediaRect(500f, 0f, 600f, 100f)))))
    }

    @Test
    fun `viewport change replans an escape when its former edge becomes unavailable`() {
        assertEquals(MediaPoint(82f, 200f), update(MediaPoint(120f, 200f))!!.target)
        val insetViewport = MediaRect(90f, 0f, 400f, 600f)
        val replanned = controller.update(MediaPoint(120f, 200f), 10f, 10f, media, insetViewport)!!
        assertEquals(MediaReactionKind.ESCAPE, replanned.kind)
        assertEquals(MediaPoint(120f, 82f), replanned.target)
    }

    @Test
    fun `body resize and clearance changes invalidate the old escape destination`() {
        assertEquals(MediaPoint(82f, 200f), update(MediaPoint(200f, 200f))!!.target)
        val resized = controller.update(MediaPoint(200f, 200f), 20f, 10f, media, viewport, margin = 20f)!!
        assertEquals(MediaReactionKind.ESCAPE, resized.kind)
        // Horizontal exits need 140px; the resized body's closest vertical exit needs 130px.
        assertEquals(MediaPoint(200f, 70f), resized.target)
    }
}
