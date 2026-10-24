package org.nqmgaming.aneko.core.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaRegionStoreTest {
    private val automatic = MediaRegion("auto", MediaRect(0f, 0f, 100f, 100f))
    private val manual = MediaRegion("manual", MediaRect(50f, 50f, 150f, 150f))

    @Before
    @After
    fun reset() {
        MediaRegionStore.clearAll()
        MediaRegionStore.setDetectionConnected(false)
    }

    @Test
    fun `manual region overrides detection and clearing restores current detection`() {
        MediaRegionStore.updateDetected(listOf(automatic))
        MediaRegionStore.setManual(manual)
        assertEquals(listOf(manual), MediaRegionStore.regions.value)
        val moved = automatic.copy(bounds = MediaRect(0f, 100f, 100f, 200f))
        MediaRegionStore.updateDetected(listOf(moved))
        assertEquals(listOf(manual), MediaRegionStore.regions.value)
        MediaRegionStore.setManual(null)
        assertEquals(listOf(moved), MediaRegionStore.regions.value)
    }

    @Test
    fun `disconnect clears automatic regions but preserves a manual selection`() {
        MediaRegionStore.setDetectionConnected(true)
        MediaRegionStore.updateDetected(listOf(automatic))
        MediaRegionStore.setManual(manual)
        MediaRegionStore.setDetectionConnected(false)
        assertFalse(MediaRegionStore.isDetectionConnected.value)
        assertTrue(MediaRegionStore.detectedRegions.value.isEmpty())
        assertEquals(listOf(manual), MediaRegionStore.regions.value)
    }

    @Test
    fun `filters invalid regions and duplicate IDs`() {
        MediaRegionStore.updateDetected(listOf(automatic, automatic,
            MediaRegion("invalid", MediaRect(0f, 0f, Float.NaN, 100f))))
        assertEquals(listOf(automatic), MediaRegionStore.regions.value)
    }
}
