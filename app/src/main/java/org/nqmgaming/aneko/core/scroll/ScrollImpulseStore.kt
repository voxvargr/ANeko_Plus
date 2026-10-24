package org.nqmgaming.aneko.core.scroll

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Transient input only: a null event invalidates current inertia; nothing replays to a new observer. */
object ScrollImpulseStore {
    private val mutableEvents = MutableSharedFlow<ScrollImpulse?>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ScrollImpulse?> = mutableEvents.asSharedFlow()

    fun publish(impulse: ScrollImpulse): Boolean = impulse.isValid() && mutableEvents.tryEmit(impulse)

    fun clear() {
        mutableEvents.tryEmit(null)
    }
}
