package org.nqmgaming.aneko.core.scroll

import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

data class ScrollFlingStep(val target: MediaPoint, val velocity: MediaPoint)

/** Per-pet, framework-free inertia. Positive content scrolling pushes the pet in the opposite direction. */
class ScrollFlingController(density: Float = 1f) {
    private val scale = density.also { require(it.isFinite() && it > 0f && it <= 10f) }
    private data class Source(val packageName: String, val windowId: Int, val bounds: MediaRect)
    private var source: Source? = null
    private var sampleAt = -1L
    private var observedAt = -1L
    private var observedDeltaX = 0f
    private var observedDeltaY = 0f
    private var pendingX = 0f
    private var pendingY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var stepAt = -1L
    private var impulseAt = -1L
    private var travelled = 0f
    var isActive: Boolean = false
        private set

    fun onScroll(
        impulse: ScrollImpulse,
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        viewport: MediaRect,
        nowUptimeMs: Long,
    ): Boolean {
        if (!impulse.isValid() || nowUptimeMs < 0 || impulse.uptimeMs > nowUptimeMs ||
            nowUptimeMs-impulse.uptimeMs > MAX_EVENT_AGE_MS ||
            !validBody(center, halfWidth, halfHeight, viewport)) return false
        val region = impulse.bounds.intersection(viewport) ?: return false
        // An overlapping tail/foot does not make a pet in a fixed header part of the scrolling content.
        if (center.x <= region.left || center.x >= region.right ||
            center.y <= region.top || center.y >= region.bottom) return false
        if (impulse.uptimeMs < observedAt) return false

        val incoming = Source(impulse.packageName, impulse.windowId, impulse.bounds)
        if (source != incoming) {
            cancel()
            source = incoming
            seed(impulse.uptimeMs)
            observedDeltaX = impulse.deltaX
            observedDeltaY = impulse.deltaY
            return false
        }
        if (impulse.uptimeMs == observedAt && impulse.deltaX == observedDeltaX && impulse.deltaY == observedDeltaY) return false
        observedAt = impulse.uptimeMs
        observedDeltaX = impulse.deltaX
        observedDeltaY = impulse.deltaY
        val elapsed = impulse.uptimeMs-sampleAt
        if (elapsed > MAX_SAMPLE_GAP_MS) {
            seed(impulse.uptimeMs)
            return false
        }
        pendingX += impulse.deltaX
        pendingY += impulse.deltaY
        if (!pendingX.isFinite() || !pendingY.isFinite() || hypot(pendingX, pendingY) > 100_000f) {
            seed(impulse.uptimeMs)
            return false
        }
        // Coalesce same-timestamp and sub-frame reports instead of dividing by zero or stacking impulses.
        if (elapsed < MIN_SAMPLE_MS) return false
        val dx = pendingX
        val dy = pendingY
        seed(impulse.uptimeMs)
        val distance = hypot(dx, dy)
        if (distance < 4f*scale || distance*1000f/elapsed < 500f*scale) return false

        var vx = -dx*1000f/elapsed*0.65f
        var vy = -dy*1000f/elapsed*0.65f
        val speed = hypot(vx, vy)
        val limit = 1_800f*scale
        if (speed > limit) { vx *= limit/speed; vy *= limit/speed }
        if (!isActive) {
            travelled = 0f
            stepAt = nowUptimeMs
        }
        velocityX = vx
        velocityY = vy
        impulseAt = nowUptimeMs
        isActive = true
        return true
    }

    fun step(
        center: MediaPoint,
        halfWidth: Float,
        halfHeight: Float,
        viewport: MediaRect,
        nowUptimeMs: Long,
    ): ScrollFlingStep? {
        if (!isActive) return null
        if (!validBody(center, halfWidth, halfHeight, viewport) || nowUptimeMs < stepAt ||
            nowUptimeMs-stepAt > MAX_STEP_GAP_MS || nowUptimeMs-impulseAt > MAX_LIFETIME_MS) {
            stopFling()
            return null
        }
        val elapsed = min(nowUptimeMs-stepAt, 125L)
        stepAt = nowUptimeMs
        if (elapsed == 0L) return ScrollFlingStep(center, MediaPoint(velocityX, velocityY))
        val decay = exp(-6f*elapsed/1000f)
        var dx = velocityX/6f*(1f-decay)
        var dy = velocityY/6f*(1f-decay)
        val distance = hypot(dx, dy)
        val travelLimit = min(220f*scale, min(viewport.width, viewport.height)*0.6f)
        val remaining = (travelLimit-travelled).coerceAtLeast(0f)
        val maximumStep = min(48f*scale, remaining)
        if (distance > maximumStep && distance > 0f) {
            dx *= maximumStep/distance
            dy *= maximumStep/distance
        }
        val x = (center.x+dx).coerceIn(viewport.left+halfWidth, viewport.right-halfWidth)
        val y = (center.y+dy).coerceIn(viewport.top+halfHeight, viewport.bottom-halfHeight)
        velocityX = if (x != center.x+dx) 0f else velocityX*decay
        velocityY = if (y != center.y+dy) 0f else velocityY*decay
        travelled += hypot(x-center.x, y-center.y)
        if (travelled >= travelLimit-0.01f || hypot(velocityX, velocityY) < 40f*scale) stopFling()
        return ScrollFlingStep(MediaPoint(x, y), MediaPoint(velocityX, velocityY))
    }

    fun cancel() {
        stopFling()
        source = null
        sampleAt = -1L
        observedAt = -1L
        observedDeltaX = 0f
        observedDeltaY = 0f
        pendingX = 0f
        pendingY = 0f
    }

    private fun seed(time: Long) {
        sampleAt = time
        observedAt = time
        pendingX = 0f
        pendingY = 0f
    }

    private fun stopFling() {
        isActive = false
        velocityX = 0f
        velocityY = 0f
        stepAt = -1L
        impulseAt = -1L
        travelled = 0f
    }

    private fun validBody(center: MediaPoint, halfWidth: Float, halfHeight: Float, viewport: MediaRect): Boolean =
        viewport.isValid && viewport.width.isFinite() && viewport.height.isFinite() &&
            center.x.isFinite() && center.y.isFinite() && halfWidth.isFinite() &&
            halfHeight.isFinite() && halfWidth > 0f && halfHeight > 0f &&
            viewport.width >= halfWidth*2f && viewport.height >= halfHeight*2f &&
            center.x >= viewport.left+halfWidth && center.x <= viewport.right-halfWidth &&
            center.y >= viewport.top+halfHeight && center.y <= viewport.bottom-halfHeight

    companion object {
        private const val MAX_EVENT_AGE_MS = 250L
        private const val MAX_SAMPLE_GAP_MS = 250L
        private const val MIN_SAMPLE_MS = 16L
        private const val MAX_STEP_GAP_MS = 300L
        private const val MAX_LIFETIME_MS = 1_250L
    }
}
