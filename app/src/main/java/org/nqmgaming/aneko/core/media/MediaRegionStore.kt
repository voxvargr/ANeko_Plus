package org.nqmgaming.aneko.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process regions in physical screen coordinates. A manual region takes precedence. */
object MediaRegionStore {
    private val detected = MutableStateFlow<List<MediaRegion>>(emptyList())
    private val manual = MutableStateFlow<MediaRegion?>(null)
    private val effective = MutableStateFlow<List<MediaRegion>>(emptyList())
    private val connected = MutableStateFlow(false)

    val detectedRegions: StateFlow<List<MediaRegion>> = detected.asStateFlow()
    val manualRegion: StateFlow<MediaRegion?> = manual.asStateFlow()
    val regions: StateFlow<List<MediaRegion>> = effective.asStateFlow()
    val isDetectionConnected: StateFlow<Boolean> = connected.asStateFlow()

    @Synchronized
    fun updateDetected(regions: List<MediaRegion>) {
        detected.value = regions.filter { it.bounds.isValid }.distinctBy { it.id }.take(6)
        publish()
    }

    @Synchronized
    fun setManual(region: MediaRegion?) {
        manual.value = region?.takeIf { it.bounds.isValid }
        publish()
    }

    @Synchronized
    fun clearDetected() {
        detected.value = emptyList()
        publish()
    }

    @Synchronized
    fun clearAll() {
        detected.value = emptyList()
        manual.value = null
        publish()
    }

    fun setDetectionConnected(isConnected: Boolean) {
        connected.value = isConnected
        if (!isConnected) clearDetected()
    }

    private fun publish() {
        effective.value = manual.value?.let(::listOf) ?: detected.value
    }
}
