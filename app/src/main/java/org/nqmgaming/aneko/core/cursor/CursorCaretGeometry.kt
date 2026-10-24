package org.nqmgaming.aneko.core.cursor

import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A provider-supplied character rectangle plus transient direction metadata. */
internal data class CursorCharacterBounds(
    val index: Int,
    val bounds: MediaRect,
    val rightToLeft: Boolean? = null,
)

/** Estimates the insertion edge from actual adjacent character rectangles, never field size. */
internal object CursorCaretGeometry {
    fun locate(
        selectionStart: Int,
        selectionEnd: Int,
        textLength: Int,
        characters: List<CursorCharacterBounds>,
        field: MediaRect,
    ): MediaPoint? {
        if (!field.isValid || textLength <= 0 || selectionStart != selectionEnd ||
            selectionStart !in 0..textLength
        ) return null
        val valid = characters.filter { it.index in 0 until textLength && it.bounds.isValid }
        val before = valid.firstOrNull { it.index == selectionStart - 1 }
        val after = valid.firstOrNull { it.index == selectionStart }
        val point = when {
            before != null && after != null && sameLine(before.bounds, after.bounds) -> {
                // Mixed-direction boundaries can have two visual caret positions. Do not guess.
                if (before.rightToLeft != null && after.rightToLeft != null &&
                    before.rightToLeft != after.rightToLeft
                ) return null
                val dx = centerX(after.bounds) - centerX(before.bounds)
                if (abs(dx) < 0.5f) return null
                val x = if (dx > 0) (before.bounds.right + after.bounds.left) / 2f
                    else (before.bounds.left + after.bounds.right) / 2f
                val y = (max(before.bounds.top, after.bounds.top) +
                    min(before.bounds.bottom, after.bounds.bottom)) / 2f
                MediaPoint(x, y)
            }
            after != null -> edge(after, valid, leading = true)
            before != null -> edge(before, valid, leading = false)
            else -> null
        } ?: return null
        return point.takeIf { it.x.isFinite() && it.y.isFinite() && field.contains(it) }
    }

    private fun edge(
        character: CursorCharacterBounds,
        characters: List<CursorCharacterBounds>,
        leading: Boolean,
    ): MediaPoint? {
        val neighbor = characters.firstOrNull {
            abs(it.index - character.index) == 1 && sameLine(it.bounds, character.bounds) &&
                abs(centerX(it.bounds) - centerX(character.bounds)) >= 0.5f
        }
        val rtl = neighbor?.let {
            (centerX(it.bounds) - centerX(character.bounds)) * (it.index - character.index) < 0
        } ?: character.rightToLeft ?: return null
        val leftEdge = leading != rtl
        return MediaPoint(if (leftEdge) character.bounds.left else character.bounds.right,
            (character.bounds.top + character.bounds.bottom) / 2f)
    }

    private fun sameLine(a: MediaRect, b: MediaRect): Boolean =
        min(a.bottom, b.bottom) - max(a.top, b.top) >= min(a.height, b.height) * 0.5f

    private fun centerX(bounds: MediaRect) = (bounds.left + bounds.right) / 2f
}
