package org.nqmgaming.aneko.core.media

enum class MediaReactionKind { ESCAPE, APPROACH, WATCH }

data class MediaReaction(
    val kind: MediaReactionKind,
    val target: MediaPoint,
    val lookAt: MediaPoint,
)

/** Chooses intent only; the overlay owns movement, animation and the update clock. */
class MediaReactionController {
    private data class Context(
        val regions: List<MediaRegion>,
        val viewport: MediaRect,
        val halfWidth: Float,
        val halfHeight: Float,
        val margin: Float,
    )

    private var context: Context? = null
    private var escapeTarget: MediaPoint? = null
    private var watchTarget: MediaPoint? = null

    fun reset() {
        context = null
        escapeTarget = null
        watchTarget = null
    }

    fun update(
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        regions: List<MediaRegion>,
        viewport: MediaRect,
        margin: Float = 8f,
        allowApproach: Boolean = true,
        dragging: Boolean = false,
    ): MediaReaction? {
        if (!center.x.isFinite() || !center.y.isFinite() || !viewport.isValid ||
            !halfWidth.isFinite() || !halfHeight.isFinite() || halfWidth < 0f || halfHeight < 0f ||
            !margin.isFinite() || margin < 0f ||
            viewport.width < halfWidth * 2f || viewport.height < halfHeight * 2f
        ) {
            reset()
            return null
        }
        val visible = regions.mapNotNull { region ->
            region.bounds.intersection(viewport)?.let { region.copy(bounds = it) }
        }.sortedWith(compareBy<MediaRegion> { it.id }.thenBy { it.bounds.left }
            .thenBy { it.bounds.top }.thenBy { it.bounds.right }.thenBy { it.bounds.bottom })
        if (visible.isEmpty()) {
            reset()
            return null
        }
        val nextContext = Context(visible, viewport, halfWidth, halfHeight, margin)
        if (context != nextContext) {
            escapeTarget = null
            watchTarget = null
            context = nextContext
        }
        if (dragging) {
            // Never fight a finger, and never resume a pre-drag destination on release.
            escapeTarget = null
            watchTarget = null
            return null
        }
        val lookAt = lookAt(center, visible)
        escapeTarget?.let { target ->
            if (!reached(center, target)) return MediaReaction(MediaReactionKind.ESCAPE, target, lookAt)
            escapeTarget = null
            watchTarget = target
        }
        if (MediaGeometry.overlaps(center, halfWidth, halfHeight, visible)) {
            val target = MediaGeometry.nearestExit(center, halfWidth, halfHeight, visible, viewport, margin)
                ?: return null
            escapeTarget = target
            watchTarget = null
            return MediaReaction(MediaReactionKind.ESCAPE, target, lookAt)
        }
        if (!allowApproach) {
            watchTarget = null
            return MediaReaction(MediaReactionKind.WATCH, center, lookAt)
        }
        val target = watchTarget ?: MediaGeometry.watchPoint(
            center, halfWidth, halfHeight, visible, viewport, margin,
        )?.also { watchTarget = it } ?: return null
        return if (reached(center, target)) {
            MediaReaction(MediaReactionKind.WATCH, center, lookAt)
        } else {
            MediaReaction(MediaReactionKind.APPROACH, target, lookAt)
        }
    }

    private fun reached(center: MediaPoint, target: MediaPoint): Boolean {
        val dx = center.x.toDouble() - target.x
        val dy = center.y.toDouble() - target.y
        return dx * dx + dy * dy <= 1.0
    }

    private fun lookAt(center: MediaPoint, regions: List<MediaRegion>): MediaPoint {
        val bounds = regions.minByOrNull { region ->
            val bounds = region.bounds
            val dx = center.x.toDouble() - center.x.coerceIn(bounds.left, bounds.right)
            val dy = center.y.toDouble() - center.y.coerceIn(bounds.top, bounds.bottom)
            dx * dx + dy * dy
        }!!.bounds
        return MediaPoint(
            ((bounds.left.toDouble() + bounds.right) / 2).toFloat(),
            ((bounds.top.toDouble() + bounds.bottom) / 2).toFloat(),
        )
    }
}
