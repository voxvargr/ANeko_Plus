package org.nqmgaming.aneko.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGeometryTest {
    private val viewport = MediaRect(0f, 0f, 400f, 600f)
    private fun region(left: Float, top: Float, right: Float, bottom: Float, id: String = "video") =
        MediaRegion(id, MediaRect(left, top, right, bottom))

    @Test
    fun `rectangle operations reject invalid bounds and treat contact as nonintersection`() {
        val bounds = MediaRect(20f, 30f, 120f, 80f)
        assertEquals(100f, bounds.width, 0f)
        assertEquals(50f, bounds.height, 0f)
        assertTrue(bounds.contains(MediaPoint(20f, 80f)))
        assertFalse(bounds.intersects(MediaRect(120f, 30f, 130f, 80f)))
        assertEquals(MediaRect(50f, 40f, 120f, 80f), bounds.intersection(MediaRect(50f, 40f, 150f, 90f)))
        assertNull(bounds.intersection(MediaRect(120f, 30f, 130f, 80f)))
        assertFalse(MediaRect(Float.NaN, 0f, 1f, 1f).isValid)
        assertFalse(MediaRect(10f, 0f, 5f, 1f).isValid)
    }

    @Test
    fun `overlap uses body dimensions and ignores edge contact`() {
        val regions = listOf(region(100f, 100f, 300f, 300f))
        assertFalse(MediaGeometry.overlaps(MediaPoint(80f, 180f), 20f, 15f, regions))
        assertTrue(MediaGeometry.overlaps(MediaPoint(81f, 180f), 20f, 15f, regions))
        assertFalse(MediaGeometry.overlaps(MediaPoint(Float.NaN, 180f), 20f, 15f, regions))
    }

    @Test
    fun `nearest exit chooses closest straight edge including body and clearance`() {
        val regions = listOf(region(100f, 100f, 300f, 300f))
        assertEquals(MediaPoint(72f, 180f), MediaGeometry.nearestExit(
            MediaPoint(130f, 180f), 20f, 15f, regions, viewport,
        ))
        assertEquals(MediaPoint(210f, 323f), MediaGeometry.nearestExit(
            MediaPoint(210f, 290f), 20f, 15f, regions, viewport,
        ))
    }

    @Test
    fun `offscreen and blocked edges cannot send the body outside viewport`() {
        val regions = listOf(region(-100f, 100f, 110f, 300f))
        assertEquals(MediaPoint(138f, 200f), MediaGeometry.nearestExit(
            MediaPoint(25f, 200f), 20f, 15f, regions, viewport,
        ))
    }

    @Test
    fun `overlapping media are escaped as a union`() {
        val regions = listOf(
            region(80f, 100f, 210f, 300f, "left"),
            region(180f, 100f, 300f, 300f, "right"),
        )
        val target = MediaGeometry.nearestExit(MediaPoint(195f, 180f), 10f, 10f, regions, viewport)
        assertEquals(MediaPoint(195f, 82f), target)
        assertFalse(MediaGeometry.overlaps(target!!, 10f, 10f, regions))
    }

    @Test
    fun `escape avoids running through unrelated media when a clear route exists`() {
        val regions = listOf(
            region(150f, 100f, 250f, 400f, "current"),
            region(90f, 100f, 140f, 400f, "neighbor"),
        )
        // The left union boundary is closer, but reaching it crosses the neighbor.
        assertEquals(MediaPoint(268f, 250f), MediaGeometry.nearestExit(
            MediaPoint(155f, 250f), 10f, 10f, regions, viewport,
        ))
    }

    @Test
    fun `full screen media and bodies too large for viewport have no safe exit`() {
        val full = listOf(MediaRegion("full", viewport))
        assertNull(MediaGeometry.nearestExit(MediaPoint(200f, 300f), 20f, 20f, full, viewport))
        assertNull(MediaGeometry.watchPoint(MediaPoint(200f, 300f), 20f, 20f, full, viewport))
        assertNull(MediaGeometry.nearestExit(MediaPoint(200f, 300f), 220f, 20f, full, viewport))
    }

    @Test
    fun `watch point approaches nearest perimeter from outside with clearance`() {
        val regions = listOf(region(100f, 100f, 300f, 300f))
        val target = MediaGeometry.watchPoint(MediaPoint(50f, 180f), 20f, 15f, regions, viewport)
        assertEquals(MediaPoint(72f, 180f), target)
        assertFalse(MediaGeometry.overlaps(target!!, 20f, 15f, regions))
        assertNull(MediaGeometry.nearestExit(MediaPoint(50f, 180f), 20f, 15f, regions, viewport))
    }

    @Test
    fun `watch point finds exposed perimeter when nearest projection is covered`() {
        val regions = listOf(
            region(100f, 100f, 300f, 300f, "main"),
            region(30f, 140f, 110f, 260f, "neighbor"),
        )
        val target = MediaGeometry.watchPoint(MediaPoint(65f, 200f), 10f, 10f, regions, viewport)
        assertEquals(MediaPoint(12f, 200f), target)
        assertFalse(MediaGeometry.overlaps(target!!, 10f, 10f, regions))
    }

    @Test
    fun `invalid absent and completely offscreen regions produce no target`() {
        val center = MediaPoint(200f, 300f)
        for (regions in listOf(
            emptyList(),
            listOf(region(300f, 100f, 100f, 300f)),
            listOf(region(-300f, 100f, -100f, 300f)),
        )) {
            assertNull(MediaGeometry.nearestExit(center, 20f, 20f, regions, viewport))
            assertNull(MediaGeometry.watchPoint(center, 20f, 20f, regions, viewport))
        }
        val regions = listOf(region(100f, 100f, 300f, 300f))
        assertNull(MediaGeometry.watchPoint(center, -1f, 20f, regions, viewport))
        assertNull(MediaGeometry.watchPoint(center, 20f, 20f, regions, viewport, Float.NaN))
    }

    @Test
    fun `cross shaped media union has no straight exit even with free corners`() {
        val regions = listOf(
            region(0f, 180f, 400f, 220f, "horizontal"),
            region(180f, 0f, 220f, 600f, "vertical"),
        )
        val center = MediaPoint(200f, 200f)
        assertNull(MediaGeometry.nearestExit(center, 10f, 10f, regions, viewport))
        val watch = MediaGeometry.watchPoint(center, 10f, 10f, regions, viewport)
        assertTrue(watch != null)
        assertFalse(MediaGeometry.overlaps(watch!!, 18f, 18f, regions))
    }
}
