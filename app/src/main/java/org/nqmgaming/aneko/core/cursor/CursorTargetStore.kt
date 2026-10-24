package org.nqmgaming.aneko.core.cursor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect

/** Only screen geometry and source identity are retained, never editor text. */
data class CursorTarget(
    val caretX: Float,
    val caretY: Float,
    val fieldBounds: MediaRect,
    val observedAtUptimeMs: Long,
    val packageName: String,
    val windowId: Int,
)

object CursorTargetStore {
    const val MAX_AGE_MS = 1_500L
    private val current = MutableStateFlow<CursorTarget?>(null)
    val targets: StateFlow<CursorTarget?> = current.asStateFlow()

    fun update(target: CursorTarget?) {
        current.value = target?.takeIf {
            it.caretX.isFinite() && it.caretY.isFinite() && it.fieldBounds.isValid &&
                it.fieldBounds.contains(MediaPoint(it.caretX, it.caretY)) &&
                it.observedAtUptimeMs >= 0 && it.packageName.isNotBlank() && it.windowId >= 0
        }
    }

    fun clear() { current.value = null }

    fun expire(nowUptimeMs: Long) {
        val target = current.value ?: return
        if (nowUptimeMs < target.observedAtUptimeMs ||
            nowUptimeMs - target.observedAtUptimeMs >= MAX_AGE_MS
        ) current.compareAndSet(target, null)
    }
}
