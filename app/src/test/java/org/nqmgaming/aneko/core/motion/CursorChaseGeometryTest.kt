package org.nqmgaming.aneko.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect
import org.nqmgaming.aneko.core.media.MediaRegion

class CursorChaseGeometryTest {
    private val viewport = MediaRect(0f, 0f, 400f, 600f)

    @Test
    fun `pet follows near the caret while staying outside a short editable field`() {
        val field = MediaRect(100f, 260f, 300f, 280f)
        val target = CursorChaseGeometry.target(MediaPoint(200f, 100f), MediaPoint(200f, 270f),
            field, 20f, 20f, viewport, 16f)
        assertEquals(MediaPoint(200f, 234f), target)
        assertFalse(MediaRect(target!!.x - 20f, target.y - 20f, target.x + 20f, target.y + 20f).intersects(field))
    }

    @Test
    fun `caret near viewport corner keeps the complete body visible without covering it`() {
        val caret = MediaPoint(10f, 10f)
        val target = CursorChaseGeometry.target(MediaPoint(100f, 100f), caret,
            MediaRect(0f, 0f, 100f, 40f), 20f, 20f, viewport, 16f)!!
        assertEquals(MediaPoint(46f, 46f), target)
        val body = MediaRect(target.x - 20f, target.y - 20f, target.x + 20f, target.y + 20f)
        assertFalse(body.contains(caret))
        assertTrue(viewport.contains(MediaPoint(body.left, body.top)))
        assertTrue(viewport.contains(MediaPoint(body.right, body.bottom)))
    }

    @Test
    fun `chase does not take a direct route through a media area`() {
        val media = listOf(MediaRegion("video", MediaRect(180f, 0f, 220f, 600f)))
        assertNull(CursorChaseGeometry.target(MediaPoint(50f, 300f), MediaPoint(350f, 300f),
            MediaRect(300f, 280f, 390f, 320f), 10f, 10f, viewport, 16f, media))
    }

    @Test
    fun `a fast movement cannot skip across a thin media strip between updates`() {
        val media = listOf(MediaRegion("thin-video", MediaRect(190f, 90f, 191f, 110f)))
        assertFalse(CursorChaseGeometry.pathAvoidsMedia(MediaPoint(50f, 100f), MediaPoint(350f, 100f),
            10f, 10f, media))
        assertTrue(CursorChaseGeometry.pathAvoidsMedia(MediaPoint(50f, 50f), MediaPoint(350f, 50f),
            10f, 10f, media))
    }

    @Test
    fun `space or selection movement shifts destination without changing the pet position`() {
        val center = MediaPoint(200f, 100f)
        val field = MediaRect(100f, 200f, 350f, 240f)
        val before = CursorChaseGeometry.target(center, MediaPoint(200f, 220f), field, 10f, 10f, viewport, 24f)!!
        val after = CursorChaseGeometry.target(center, MediaPoint(210f, 220f), field, 10f, 10f, viewport, 24f)!!
        assertEquals(10f, after.x - before.x, 0f)
        assertEquals(before.y, after.y, 0f)
        assertEquals(MediaPoint(200f, 100f), center)
    }

    @Test
    fun `invalid or offscreen caret produces no chase destination`() {
        val field = MediaRect(100f, 200f, 300f, 240f)
        assertNull(CursorChaseGeometry.target(MediaPoint(100f, 100f), MediaPoint(350f, 220f),
            field, 20f, 20f, viewport, 16f))
        assertNull(CursorChaseGeometry.target(MediaPoint(100f, 100f), MediaPoint(200f, 220f),
            field, 250f, 20f, viewport, 16f))
    }
}
