package org.nqmgaming.aneko.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFadePolicyTest {
    private val viewport = MediaRect(0f, 0f, 1000f, 1000f)
    private val center = MediaPoint(500f, 500f)
    private fun region(bounds: MediaRect, fullscreen: Boolean = false, id: String = "video") =
        MediaRegion(id, bounds, fullscreen = fullscreen)

    @Test
    fun `explicit visible fullscreen fades even outside the video body`() {
        val media = listOf(region(MediaRect(200f, 200f, 800f, 800f), fullscreen = true))
        assertTrue(fades(media, MediaPoint(50f, 50f)))
        assertFalse(MediaGeometry.overlaps(MediaPoint(50f, 50f), 10f, 10f, media))
    }

    @Test
    fun `ordinary inline media with a straight exit stays at base opacity`() {
        val media = listOf(region(MediaRect(200f, 200f, 800f, 800f)))
        assertFalse(fades(media))
        assertFalse(fades(media, MediaPoint(50f, 50f)))
    }

    @Test
    fun `coverage threshold is inclusive and fades globally even in a remaining bar`() {
        assertFalse(fades(listOf(region(MediaRect(0f, 0f, 929f, 1000f)))))
        assertTrue(fades(listOf(region(MediaRect(0f, 0f, 930f, 1000f)))))
        assertTrue(fades(listOf(region(MediaRect(0f, 0f, 930f, 1000f))), MediaPoint(980f, 500f)))
    }

    @Test
    fun `coverage measures media union rather than individual rectangles`() {
        val media = listOf(region(MediaRect(0f, 0f, 470f, 1000f), id = "left"),
            region(MediaRect(530f, 0f, 1000f, 1000f), id = "right"))
        val pet = MediaPoint(200f, 500f)
        assertNotNull(MediaGeometry.nearestExit(pet, 10f, 10f, media, viewport))
        assertTrue(fades(media, pet))
    }

    @Test
    fun `overlapping rectangles cannot inflate coverage`() {
        val bounds = MediaRect(100f, 100f, 900f, 900f)
        val media = listOf(region(bounds, id = "first"), region(bounds, id = "second"))
        assertFalse(fades(media))
    }

    @Test
    fun `inline media fades when the body and margin have no available straight exit`() {
        val media = listOf(region(MediaRect(0f, 50f, 1000f, 950f)))
        assertTrue(MediaFadePolicy.shouldFade(true, center, 40f, 40f, media, viewport))
        assertFalse(MediaFadePolicy.shouldFade(true, MediaPoint(500f, 20f), 10f, 10f, media, viewport))
    }

    @Test
    fun `requested comfort margin participates in no room fallback`() {
        val media = listOf(region(MediaRect(0f, 50f, 1000f, 950f)))
        assertFalse(MediaFadePolicy.shouldFade(true, center, 20f, 20f, media, viewport, margin = 8f))
        assertTrue(MediaFadePolicy.shouldFade(true, center, 20f, 20f, media, viewport, margin = 40f))
    }

    @Test
    fun `no straight exit can fade even when a corner remains geometrically free`() {
        val media = listOf(region(MediaRect(0f, 480f, 1000f, 520f), id = "horizontal"),
            region(MediaRect(480f, 0f, 520f, 1000f), id = "vertical"))
        assertNotNull(MediaGeometry.watchPoint(center, 10f, 10f, media, viewport))
        assertTrue(fades(media))
    }

    @Test
    fun `disabled removed invalid and offscreen fullscreen regions restore base intent`() {
        val full = listOf(region(viewport, fullscreen = true))
        assertTrue(fades(full))
        assertFalse(MediaFadePolicy.shouldFade(false, center, 10f, 10f, full, viewport))
        assertFalse(fades(emptyList()))
        assertFalse(fades(listOf(region(MediaRect(Float.NaN, 0f, 1000f, 1000f), fullscreen = true))))
        assertFalse(fades(listOf(region(MediaRect(-1000f, 0f, -100f, 1000f), fullscreen = true))))
    }

    @Test
    fun `offscreen area does not inflate viewport coverage`() {
        val media = listOf(region(MediaRect(-1000f, 0f, 500f, 1000f)))
        assertFalse(fades(media, MediaPoint(200f, 500f)))
    }

    @Test
    fun `invalid body viewport or clearance cannot masquerade as no room`() {
        val media = listOf(region(viewport, fullscreen = true))
        assertFalse(fades(media, MediaPoint(Float.NaN, 500f)))
        assertFalse(MediaFadePolicy.shouldFade(true, center, -1f, 10f, media, viewport))
        assertFalse(MediaFadePolicy.shouldFade(true, center, 0f, 10f, media, viewport))
        assertFalse(MediaFadePolicy.shouldFade(true, center, 10f, 10f, media,
            MediaRect(0f, 0f, 0f, 1000f)))
        assertFalse(MediaFadePolicy.shouldFade(true, center, 10f, 10f, media, viewport, Float.NaN))
        assertFalse(fades(media, MediaPoint(2000f, 2000f)))
    }

    @Test
    fun `default and selected caps never increase lower base alpha`() {
        assertEquals(0.10f, MediaFadePolicy.capAlpha(0.8f, true), 0f)
        assertEquals(0.20f, MediaFadePolicy.capAlpha(0.8f, true, 0.20f), 0f)
        assertEquals(0.04f, MediaFadePolicy.capAlpha(0.04f, true), 0f)
        assertEquals(0.04f, MediaFadePolicy.capAlpha(0.04f, true, 0.20f), 0f)
        assertEquals(0.8f, MediaFadePolicy.capAlpha(0.8f, false), 0f)
        assertEquals(0f, MediaFadePolicy.capAlpha(0f, true), 0f)
    }

    @Test
    fun `invalid cap values cannot make the fade effect completely invisible`() {
        for (invalid in listOf(0f, -1f, Float.NaN)) {
            assertEquals(0.10f, MediaFadePolicy.capAlpha(0.8f, true, invalid), 0f)
            assertEquals(0.04f, MediaFadePolicy.capAlpha(0.04f, true, invalid), 0f)
            assertEquals(0f, MediaFadePolicy.capAlpha(0f, true, invalid), 0f)
        }
        assertEquals(0.20f, MediaFadePolicy.capAlpha(0.8f, true, 1f), 0f)
    }

    private fun fades(media: List<MediaRegion>, pet: MediaPoint = center): Boolean =
        MediaFadePolicy.shouldFade(true, pet, 10f, 10f, media, viewport)
}
