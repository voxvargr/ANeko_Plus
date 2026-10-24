package org.nqmgaming.aneko.core.motion

import org.nqmgaming.aneko.core.media.MediaGeometry
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect
import org.nqmgaming.aneko.core.media.MediaRegion
import kotlin.math.max
import kotlin.math.min

/** Finds a nearby body-safe destination without covering the caret or crossing media. */
object CursorChaseGeometry {
    fun target(
        center: MediaPoint,
        caret: MediaPoint,
        fieldBounds: MediaRect,
        halfWidth: Float,
        halfHeight: Float,
        viewport: MediaRect,
        clearance: Float,
        media: List<MediaRegion> = emptyList(),
    ): MediaPoint? {
        if (!center.x.isFinite() || !center.y.isFinite() || !caret.x.isFinite() || !caret.y.isFinite() ||
            !halfWidth.isFinite() || !halfHeight.isFinite() || halfWidth < 0f || halfHeight < 0f ||
            !clearance.isFinite() || clearance <= 0f || !fieldBounds.contains(caret) || !viewport.contains(caret)
        ) return null
        val dx = halfWidth + clearance
        val dy = halfHeight + clearance
        val candidates = listOf(
            MediaPoint(caret.x + dx, caret.y + dy), MediaPoint(caret.x - dx, caret.y + dy),
            MediaPoint(caret.x + dx, caret.y - dy), MediaPoint(caret.x - dx, caret.y - dy),
            MediaPoint(caret.x, caret.y + dy), MediaPoint(caret.x, caret.y - dy),
            MediaPoint(caret.x + dx, caret.y), MediaPoint(caret.x - dx, caret.y),
        ).filter { point ->
            point.x - halfWidth >= viewport.left && point.x + halfWidth <= viewport.right &&
                point.y - halfHeight >= viewport.top && point.y + halfHeight <= viewport.bottom &&
                !MediaGeometry.overlaps(point, halfWidth, halfHeight, media) &&
                pathAvoidsMedia(center, point, halfWidth, halfHeight, media)
        }
        // When space permits, stay outside the editable field as well as the caret itself.
        return candidates.minWithOrNull(compareBy<MediaPoint> { point ->
            val body = MediaRect(point.x - halfWidth, point.y - halfHeight,
                point.x + halfWidth, point.y + halfHeight)
            if (body.intersects(fieldBounds)) 1 else 0
        }.thenBy { point ->
            val x = point.x.toDouble() - center.x
            val y = point.y.toDouble() - center.y
            x * x + y * y
        })
    }

    fun pathAvoidsMedia(from: MediaPoint, to: MediaPoint, halfWidth: Float, halfHeight: Float,
        media: List<MediaRegion>): Boolean = media.none { region ->
        val bounds = region.bounds
        bounds.isValid && crosses(from, to, MediaRect(bounds.left - halfWidth,
            bounds.top - halfHeight, bounds.right + halfWidth, bounds.bottom + halfHeight))
    }

    private fun crosses(from: MediaPoint, to: MediaPoint, bounds: MediaRect): Boolean {
        var enter = 0.0
        var exit = 1.0
        fun clip(origin: Float, delta: Float, low: Float, high: Float): Boolean {
            if (delta == 0f) return origin > low && origin < high
            val first = (low.toDouble() - origin) / delta
            val second = (high.toDouble() - origin) / delta
            enter = max(enter, min(first, second))
            exit = min(exit, max(first, second))
            return enter < exit
        }
        return clip(from.x, to.x - from.x, bounds.left, bounds.right) &&
            clip(from.y, to.y - from.y, bounds.top, bounds.bottom) && enter < exit
    }
}
