package org.nqmgaming.aneko.core.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect

class CursorCaretGeometryTest {
    private val field = MediaRect(0f, 0f, 200f, 100f)

    @Test
    fun `insertion moves across actual left to right character edges`() {
        val characters = listOf(character(0, 10f), character(1, 20f), character(2, 30f))
        assertEquals(MediaPoint(10f, 20f), locate(0, 3, characters))
        assertEquals(MediaPoint(20f, 20f), locate(1, 3, characters))
        assertEquals(MediaPoint(30f, 20f), locate(2, 3, characters))
        assertEquals(MediaPoint(40f, 20f), locate(3, 3, characters))
    }

    @Test
    fun `right to left caret follows visual edges in reverse`() {
        val characters = listOf(character(0, 50f, rtl = true), character(1, 40f, rtl = true))
        assertEquals(MediaPoint(60f, 20f), locate(0, 2, characters))
        assertEquals(MediaPoint(50f, 20f), locate(1, 2, characters))
        assertEquals(MediaPoint(40f, 20f), locate(2, 2, characters))
    }

    @Test
    fun `spaces infer their direction from adjacent screen rectangles`() {
        val characters = listOf(character(0, 10f), character(1, 20f, rtl = null))
        assertEquals(MediaPoint(30f, 20f), locate(2, 2, characters))
    }

    @Test
    fun `single strong character still exposes start and end edges`() {
        val character = listOf(character(0, 10f))
        assertEquals(MediaPoint(10f, 20f), locate(0, 1, character))
        assertEquals(MediaPoint(20f, 20f), locate(1, 1, character))
    }

    @Test
    fun `line wrapping uses the leading edge on the next line`() {
        val characters = listOf(character(0, 100f), character(1, 10f, top = 40f))
        assertEquals(MediaPoint(10f, 50f), locate(1, 2, characters))
    }

    @Test
    fun `mixed direction boundary and overlapping glyph positions remain ambiguous`() {
        assertNull(locate(1, 2, listOf(character(0, 10f), character(1, 20f, rtl = true))))
        assertNull(locate(1, 2, listOf(character(0, 10f), character(1, 10f))))
    }

    @Test
    fun `unknown single neutral character does not guess a direction`() {
        assertNull(locate(1, 1, listOf(character(0, 10f, rtl = null))))
    }

    @Test
    fun `missing bounds and empty fields do not substitute field bounds`() {
        assertNull(locate(0, 0, emptyList()))
        assertNull(locate(1, 3, emptyList()))
        assertNull(locate(1, 3, listOf(character(2, 30f))))
    }

    @Test
    fun `noncollapsed selection and invalid offsets produce no target`() {
        val characters = listOf(character(0, 10f), character(1, 20f))
        assertNull(CursorCaretGeometry.locate(0, 1, 2, characters, field))
        assertNull(locate(-1, 2, characters))
        assertNull(locate(3, 2, characters))
    }

    @Test
    fun `offscreen or invalid rectangles cannot produce a visible caret`() {
        assertNull(locate(1, 1, listOf(character(0, 250f))))
        assertNull(locate(1, 1, listOf(CursorCharacterBounds(0, MediaRect(0f, 10f, 0f, 30f), false))))
        assertNull(locate(1, 1, listOf(CursorCharacterBounds(0, MediaRect(Float.NaN, 10f, 20f, 30f), false))))
    }

    private fun locate(cursor: Int, length: Int, characters: List<CursorCharacterBounds>) =
        CursorCaretGeometry.locate(cursor, cursor, length, characters, field)

    private fun character(index: Int, left: Float, top: Float = 10f, rtl: Boolean? = false) =
        CursorCharacterBounds(index, MediaRect(left, top, left + 10f, top + 20f), rtl)
}
