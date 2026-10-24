package org.nqmgaming.aneko.core.cursor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nqmgaming.aneko.core.media.MediaRect

class CursorTargetStoreTest {
    private val target = CursorTarget(20f, 30f, MediaRect(10f, 20f, 100f, 60f),
        1_000L, "editor.app", 3)

    @After
    fun clear() = CursorTargetStore.clear()

    @Test
    fun `target expires exactly at the freshness boundary`() {
        CursorTargetStore.update(target)
        CursorTargetStore.expire(2_499L)
        assertEquals(target, CursorTargetStore.targets.value)
        CursorTargetStore.expire(2_500L)
        assertNull(CursorTargetStore.targets.value)
    }

    @Test
    fun `earlier expiry callback does not discard a refreshed target`() {
        CursorTargetStore.update(target.copy(observedAtUptimeMs = 2_000L))
        CursorTargetStore.expire(2_500L)
        assertEquals(2_000L, CursorTargetStore.targets.value!!.observedAtUptimeMs)
    }

    @Test
    fun `clock reset and explicit invalidation clear stale geometry`() {
        CursorTargetStore.update(target)
        CursorTargetStore.expire(999L)
        assertNull(CursorTargetStore.targets.value)
        CursorTargetStore.update(target)
        CursorTargetStore.clear()
        assertNull(CursorTargetStore.targets.value)
    }

    @Test
    fun `invalid source and geometry replace any previous target with null`() {
        for (invalid in listOf(target.copy(caretX = Float.NaN), target.copy(caretY = 200f),
            target.copy(fieldBounds = MediaRect(10f, 20f, 10f, 60f)),
            target.copy(packageName = " "), target.copy(windowId = -1),
            target.copy(observedAtUptimeMs = -1L))) {
            CursorTargetStore.update(target)
            CursorTargetStore.update(invalid)
            assertNull(CursorTargetStore.targets.value)
        }
    }
}
