package org.nqmgaming.aneko.core.media

import kotlin.math.max
import kotlin.math.min

/** Stateless opacity intent. Callers supply current regions and a margin in the same pixel space. */
object MediaFadePolicy {
    fun shouldFade(
        enabled: Boolean,
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        regions: List<MediaRegion>,
        viewport: MediaRect,
        margin: Float = 8f,
    ): Boolean {
        if (!enabled || !viewport.isValid || !viewport.width.isFinite() || !viewport.height.isFinite() ||
            !center.x.isFinite() || !center.y.isFinite() ||
            !halfWidth.isFinite() || !halfHeight.isFinite() || halfWidth <= 0f || halfHeight <= 0f ||
            !margin.isFinite() || margin < 0f
        ) return false
        val body = MediaRect(center.x - halfWidth, center.y - halfHeight,
            center.x + halfWidth, center.y + halfHeight)
        if (body.intersection(viewport) == null) return false
        val visible = regions.mapNotNull { region ->
            region.bounds.intersection(viewport)?.let { region.copy(bounds = it) }
        }
        if (visible.isEmpty()) return false
        // Explicit fullscreen intent also covers a pet standing in a letterbox or at an edge.
        if (visible.any { it.fullscreen }) return true
        val viewportArea = viewport.width.toDouble() * viewport.height.toDouble()
        if ((unionArea(visible.map { it.bounds }) / viewportArea).toFloat() >=
            MediaNodeClassifier.FULLSCREEN_COVERAGE
        ) return true
        if (!MediaGeometry.overlaps(center, halfWidth, halfHeight, visible)) return false
        // The movement engine only offers straight exits; do not keep an obstructed pet opaque.
        return MediaGeometry.nearestExit(center, halfWidth, halfHeight, visible, viewport, margin) == null
    }

    /** Opacity caps compose with the user's base alpha and never brighten an already faint pet. */
    fun capAlpha(baseAlpha: Float, fade: Boolean, fadeCap: Float = 0.10f): Float {
        val base = if (baseAlpha.isFinite()) baseAlpha.coerceIn(0f, 1f) else 0f
        if (!fade) return base
        val cap = if (fadeCap.isFinite()) fadeCap.coerceIn(0.10f, 0.20f) else 0.10f
        return min(base, cap)
    }

    /** A bounded rectangle sweep avoids counting overlapping media twice. */
    private fun unionArea(bounds: List<MediaRect>): Double {
        val xs = bounds.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        var area = 0.0
        for (index in 0 until xs.lastIndex) {
            val left = xs[index]
            val right = xs[index + 1]
            var height = 0.0
            var bottom = Double.NEGATIVE_INFINITY
            for (rect in bounds.filter { it.left < right && it.right > left }.sortedBy { it.top }) {
                height += max(0.0, rect.bottom.toDouble() - max(rect.top.toDouble(), bottom))
                bottom = max(bottom, rect.bottom.toDouble())
            }
            area += (right.toDouble() - left.toDouble()) * height
        }
        return area
    }
}
