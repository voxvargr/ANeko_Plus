package org.nqmgaming.aneko.core.cursor

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.nqmgaming.aneko.core.media.MediaRect

/** Reads at most four adjacent character locations. No editor text escapes this call. */
internal object CursorTargetExtractor {
    private const val MAX_ANCESTORS = 128
    data class Extraction(
        val target: CursorTarget?,
        val reason: String,
        val requestedCharacters: Int = 0,
        val returnedRectangles: Int = 0,
        val validRectangles: Int = 0,
    )

    fun extract(node: AccessibilityNodeInfo, viewport: MediaRect, nowUptimeMs: Long): CursorTarget? =
        inspect(node, viewport, nowUptimeMs).target

    /** Geometry/counts and fixed reason codes only; no text is retained in this diagnostic. */
    fun inspect(node: AccessibilityNodeInfo, viewport: MediaRect, nowUptimeMs: Long): Extraction {
        fun unavailable(reason: String, requested: Int = 0, returned: Int = 0, valid: Int = 0) =
            Extraction(null, reason, requested, returned, valid)
        if (!node.refresh()) return unavailable("stale_node")
        if (!eligible(node)) return unavailable("field_not_editable_focused_visible")
        exclusionReason(node)?.let { return unavailable(it) }
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0) return unavailable("selection_unavailable")
        if (start != end) return unavailable("selection_not_collapsed")
        // Metadata is used transiently to validate offsets and choose insertion-edge direction.
        // It is never copied to a String, retained in the target, logged, or transmitted.
        val length = node.text?.length ?: return unavailable("text_metadata_unavailable")
        if (length <= 0) return unavailable("empty_editor")
        if (start > length) return unavailable("invalid_selection")
        if (AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY !in node.availableExtraData)
            return unavailable("character_locations_not_advertised")
        val first = (start - 2).coerceAtLeast(0)
        val count = (start + 2).coerceAtMost(length) - first
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, first)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count)
        }
        if (count <= 0 || !node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments)
        ) return unavailable("character_location_refresh_failed", count)
        if (!eligible(node)) return unavailable("field_changed_during_refresh", count)
        exclusionReason(node)?.let { return unavailable(it, count) }
        if (node.textSelectionStart != start || node.textSelectionEnd != end ||
            node.text?.length != length
        ) return unavailable("selection_changed_during_refresh", count)
        val field = Rect().also(node::getBoundsInScreen).let {
            MediaRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }.intersection(viewport) ?: return unavailable("field_outside_viewport", count)
        val rectangles = characterRectangles(node.extras)
            ?: return unavailable("character_locations_not_returned", count)
        val characters = rectangles.take(count).mapIndexedNotNull { offset, value ->
            val rect = value ?: return@mapIndexedNotNull null
            val index = first + offset
            val direction = node.text?.getOrNull(index)?.let(::rightToLeft)
            CursorCharacterBounds(index, MediaRect(rect.left, rect.top, rect.right, rect.bottom), direction)
        }
        val valid = characters.count { it.bounds.isValid }
        if (valid == 0) return unavailable("no_valid_character_rectangles", count, rectangles.size, valid)
        val caret = CursorCaretGeometry.locate(start, end, length, characters, field)
            ?: return unavailable("caret_ambiguous_or_outside_field", count, rectangles.size, valid)
        return Extraction(CursorTarget(caret.x, caret.y, field, nowUptimeMs,
            node.packageName?.toString().orEmpty(), node.windowId), "available", count, rectangles.size, valid)
    }

    private fun eligible(node: AccessibilityNodeInfo): Boolean {
        return node.isEditable && node.isFocused && node.isVisibleToUser
    }

    fun isSensitive(node: AccessibilityNodeInfo): Boolean = exclusionReason(node) != null

    fun exclusionReason(node: AccessibilityNodeInfo): String? {
        return ownExclusionReason(node) ?: sensitiveAncestorReason(node)
    }

    private fun ownExclusionReason(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return "password_node"
        if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return "sensitive_node"
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        if (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            return "password_input_type"
        return null
    }

    @Suppress("DEPRECATION")
    private fun sensitiveAncestorReason(node: AccessibilityNodeInfo): String? {
        var ancestor = node.parent
        // Keep handles until traversal finishes: recycling pooled pre-33 nodes during
        // traversal would invalidate equality/hash state used to detect provider cycles.
        val visited = ArrayList<AccessibilityNodeInfo>(MAX_ANCESTORS)
        try {
            while (ancestor != null) {
                val current = ancestor
                if (current == node || visited.any { it == current }) return "ancestor_cycle"
                if (visited.size >= MAX_ANCESTORS) return "ancestor_limit"
                visited += current
                when (ownExclusionReason(current)) {
                    "password_node" -> return "password_ancestor"
                    "password_input_type" -> return "password_input_type_ancestor"
                    "sensitive_node" -> return "sensitive_ancestor"
                }
                ancestor = current.parent
            }
            return null
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                // The next/cyclic node was obtained but may not have entered visited.
                val pending = ancestor
                if (pending != null && pending !== node && visited.none { it === pending }) pending.recycle()
                visited.forEach { it.recycle() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun characterRectangles(extras: Bundle): List<RectF?>? {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        return if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(key, RectF::class.java)?.toList()
        } else extras.getParcelableArray(key)?.map { it as? RectF }
    }

    private fun rightToLeft(character: Char): Boolean? = when (Character.getDirectionality(character)) {
        Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_EUROPEAN_NUMBER,
        Character.DIRECTIONALITY_ARABIC_NUMBER -> false
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
        else -> null
    }
}
