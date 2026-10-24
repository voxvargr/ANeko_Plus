package org.nqmgaming.aneko.core.scroll

import kotlin.math.abs

data class ScrollDelta(val deltaX: Float, val deltaY: Float, val uptimeMs: Long)

/**
 * Resolves actual pixel movement from one validated scrolling source. No item indexes or text.
 * The caller supplies package/window/source identity and clipped bounds in [sourceKey], and
 * calls [clear] on focus/window changes, lock, disconnect or preference disablement.
 */
class ScrollDeltaTracker {
    private data class Sample(
        val source: String,
        val time: Long,
        val explicitX: Int,
        val explicitY: Int,
        val x: Int?,
        val y: Int?,
    )

    private var previous: Sample? = null

    fun observe(
        sourceKey: String,
        explicitX: Int,
        explicitY: Int,
        scrollX: Int,
        scrollY: Int,
        eventUptimeMs: Long,
        nowUptimeMs: Long,
    ): ScrollDelta? {
        if (sourceKey.isBlank() || sourceKey.length > 1_024 || nowUptimeMs < 0 || eventUptimeMs < 0) return null
        val time = if (eventUptimeMs == 0L) nowUptimeMs else eventUptimeMs
        if (time > nowUptimeMs || nowUptimeMs - time > MAX_EVENT_AGE_MS) return null
        val old = previous
        // Late reports from an older source must not erase the current source's baseline.
        if (old != null && time < old.time) return null
        val sample = Sample(sourceKey, time, explicitX, explicitY,
            absolutePosition(scrollX), absolutePosition(scrollY))
        if (sample == old) return null
        previous = sample
        val baseline = old?.takeIf { it.source == sourceKey && time - it.time <= MAX_BASELINE_GAP_MS }
        val dx = explicitDelta(explicitX) ?: absoluteDelta(sample.x, baseline?.x) ?: 0f
        val dy = explicitDelta(explicitY) ?: absoluteDelta(sample.y, baseline?.y) ?: 0f
        return if (dx != 0f || dy != 0f) ScrollDelta(dx, dy, time) else null
    }

    fun clear() { previous = null }

    private fun absolutePosition(value: Int): Int? = value.takeIf { it in 0..MAX_ABSOLUTE_POSITION }

    private fun absoluteDelta(current: Int?, old: Int?): Float? {
        if (current == null || old == null) return null
        val delta = current.toLong() - old.toLong()
        return delta.takeIf { abs(it) <= MAX_DELTA_PX }?.toFloat()
    }

    private fun explicitDelta(value: Int): Float? = value.takeIf {
        // AccessibilityRecord initializes unset deltas to -1. An explicit one-pixel
        // negative delta is ambiguous and can only be recovered from absolute offsets.
        it != 0 && it != -1 && abs(it.toLong()) <= MAX_DELTA_PX
    }?.toFloat()

    companion object {
        private const val MAX_EVENT_AGE_MS = 750L
        private const val MAX_BASELINE_GAP_MS = 500L
        private const val MAX_DELTA_PX = 100_000L
        private const val MAX_ABSOLUTE_POSITION = 100_000_000
    }
}
