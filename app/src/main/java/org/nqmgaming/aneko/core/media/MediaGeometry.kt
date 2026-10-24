package org.nqmgaming.aneko.core.media

import kotlin.math.max
import kotlin.math.min

data class MediaPoint(val x: Float, val y: Float)

data class MediaRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top

    fun intersection(other: MediaRect): MediaRect? =
        if (!isValid || !other.isValid) null else MediaRect(
            max(left, other.left), max(top, other.top),
            min(right, other.right), min(bottom, other.bottom),
        ).takeIf { it.isValid }

    fun contains(point: MediaPoint): Boolean = isValid &&
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    /** Touching edges do not count as an intersection. */
    fun intersects(other: MediaRect): Boolean = isValid && other.isValid &&
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class MediaRegion(
    val id: String,
    val bounds: MediaRect,
    val fullscreen: Boolean = false,
    val escapeAllowed: Boolean = true,
)

/** Pure screen-coordinate geometry. Half sizes describe the pet's visible collision body. */
object MediaGeometry {
    fun overlaps(
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        regions: List<MediaRegion>,
    ): Boolean = validBody(center, halfWidth, halfHeight) && regions.any {
        val bounds = it.bounds
        bounds.isValid && center.x + halfWidth > bounds.left &&
            center.x - halfWidth < bounds.right && center.y + halfHeight > bounds.top &&
            center.y - halfHeight < bounds.bottom
    }

    /**
     * Returns the nearest straight, axis-aligned exit from the visible media union.
     * Routes avoiding regions the pet did not start inside are preferred. If every exit
     * crosses another region, the shortest safe exit is used. Returns null if no escape
     * is needed, the inputs are invalid, or no fully visible safe destination exists.
     */
    fun nearestExit(
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        regions: List<MediaRegion>,
        viewport: MediaRect,
        margin: Float = 8f,
    ): MediaPoint? {
        val space = space(center, halfWidth, halfHeight, regions, viewport, margin) ?: return null
        if (!overlaps(center, halfWidth, halfHeight, space.visible)) return null
        val candidates = space.forbidden.flatMap { bounds ->
            listOf(
                MediaPoint(bounds.left, center.y), MediaPoint(bounds.right, center.y),
                MediaPoint(center.x, bounds.top), MediaPoint(center.x, bounds.bottom),
            )
        }.distinct().filter(space::isSafe)
        val unrelated = space.visible.filter { region ->
            !overlaps(center, halfWidth, halfHeight, listOf(region))
        }.map { region ->
            val bounds = region.bounds
            // Route obstruction uses the body, not its comfort margin: a pet already
            // inside a neighbor's margin can still move directly away from that neighbor.
            MediaRect(
                bounds.left - halfWidth, bounds.top - halfHeight,
                bounds.right + halfWidth, bounds.bottom + halfHeight,
            )
        }
        val unobstructed = candidates.filter { target ->
            unrelated.none { segmentCrosses(center, target, it) }
        }
        return (unobstructed.ifEmpty { candidates }).minByOrNull { distanceSquared(center, it) }
    }

    /** Closest safe point on a media perimeter, with body size and margin accounted for. */
    fun watchPoint(
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        regions: List<MediaRegion>,
        viewport: MediaRect,
        margin: Float = 8f,
    ): MediaPoint? {
        val space = space(center, halfWidth, halfHeight, regions, viewport, margin) ?: return null
        val candidates = mutableListOf<MediaPoint>()
        for (bounds in space.forbidden) {
            val minY = max(bounds.top, space.minY)
            val maxY = min(bounds.bottom, space.maxY)
            if (minY <= maxY) {
                // Other rectangle boundaries split a side into available/occupied segments.
                val ys = (listOf(center.y, minY, maxY) + space.forbidden.flatMap {
                    listOf(it.top, it.bottom)
                }).map { it.coerceIn(minY, maxY) }
                for (y in ys) {
                    candidates += MediaPoint(bounds.left, y)
                    candidates += MediaPoint(bounds.right, y)
                }
            }
            val minX = max(bounds.left, space.minX)
            val maxX = min(bounds.right, space.maxX)
            if (minX <= maxX) {
                val xs = (listOf(center.x, minX, maxX) + space.forbidden.flatMap {
                    listOf(it.left, it.right)
                }).map { it.coerceIn(minX, maxX) }
                for (x in xs) {
                    candidates += MediaPoint(x, bounds.top)
                    candidates += MediaPoint(x, bounds.bottom)
                }
            }
        }
        return candidates.distinct().filter(space::isSafe)
            .minByOrNull { distanceSquared(center, it) }
    }

    private data class Space(
        val visible: List<MediaRegion>,
        val forbidden: List<MediaRect>,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    ) {
        fun isSafe(point: MediaPoint): Boolean =
            point.x in minX..maxX && point.y in minY..maxY && forbidden.none {
                // Contact at an expanded boundary gives exactly the requested clearance.
                point.x > it.left && point.x < it.right &&
                    point.y > it.top && point.y < it.bottom
            }
    }

    private fun space(
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        regions: List<MediaRegion>,
        viewport: MediaRect,
        margin: Float,
    ): Space? {
        if (!validBody(center, halfWidth, halfHeight) || !viewport.isValid ||
            !margin.isFinite() || margin < 0f
        ) return null
        val minX = viewport.left + halfWidth
        val maxX = viewport.right - halfWidth
        val minY = viewport.top + halfHeight
        val maxY = viewport.bottom - halfHeight
        if (minX > maxX || minY > maxY) return null
        val visible = regions.mapNotNull { region ->
            region.bounds.intersection(viewport)?.let { region.copy(bounds = it) }
        }
        if (visible.isEmpty()) return null
        val forbidden = visible.map { region ->
            val bounds = region.bounds
            MediaRect(
                bounds.left - halfWidth - margin, bounds.top - halfHeight - margin,
                bounds.right + halfWidth + margin, bounds.bottom + halfHeight + margin,
            )
        }
        if (forbidden.any { !it.isValid }) return null
        return Space(visible, forbidden, minX, minY, maxX, maxY)
    }

    private fun validBody(center: MediaPoint, halfWidth: Float, halfHeight: Float): Boolean =
        center.x.isFinite() && center.y.isFinite() && halfWidth.isFinite() &&
            halfHeight.isFinite() && halfWidth >= 0f && halfHeight >= 0f

    private fun distanceSquared(a: MediaPoint, b: MediaPoint): Double {
        val dx = a.x.toDouble() - b.x
        val dy = a.y.toDouble() - b.y
        return dx * dx + dy * dy
    }

    private fun segmentCrosses(from: MediaPoint, to: MediaPoint, bounds: MediaRect): Boolean =
        if (from.x == to.x) {
            from.x > bounds.left && from.x < bounds.right &&
                max(min(from.y, to.y), bounds.top) < min(max(from.y, to.y), bounds.bottom)
        } else {
            from.y > bounds.top && from.y < bounds.bottom &&
                max(min(from.x, to.x), bounds.left) < min(max(from.x, to.x), bounds.right)
        }
}
