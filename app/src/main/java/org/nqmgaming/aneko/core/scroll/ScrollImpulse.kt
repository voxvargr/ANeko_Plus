package org.nqmgaming.aneko.core.scroll

import org.nqmgaming.aneko.core.media.MediaRect
import kotlin.math.abs

/** Actual source viewport and scroll displacement, in physical screen pixels. No text is retained. */
data class ScrollImpulse(
    val bounds: MediaRect,
    val deltaX: Float,
    val deltaY: Float,
    val uptimeMs: Long,
    val packageName: String,
    val windowId: Int,
) {
    fun isValid(): Boolean = bounds.isValid &&
        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { abs(it) <= 1_000_000f } &&
        deltaX.isFinite() && deltaY.isFinite() && abs(deltaX) <= 100_000f && abs(deltaY) <= 100_000f &&
        (deltaX != 0f || deltaY != 0f) && uptimeMs >= 0 &&
        packageName.isNotBlank() && packageName.length <= 240 && windowId >= 0
}
