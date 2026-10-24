package org.nqmgaming.aneko

import android.app.UiAutomation
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.nqmgaming.aneko.core.media.MediaAccessibilityService
import org.nqmgaming.aneko.core.media.MediaReactionPreferences
import org.nqmgaming.aneko.core.media.MediaRegion
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.core.service.AnimationService
import org.nqmgaming.aneko.debug.FullscreenMediaTestActivity
import org.nqmgaming.aneko.presentation.ANekoActivity
import kotlin.math.abs
import kotlin.math.min

/** Run with am instrument --no-restart, preserving the user's already bound Accessibility service. */
@RunWith(AndroidJUnit4::class)
class FullscreenMediaFadeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var automation: UiAutomation
    private lateinit var prefs: SharedPreferences
    private var saved = emptyMap<String, Any?>()
    private var originalOpacityPreferences = emptyMap<String, Any?>()
    private var originalManualRegion: MediaRegion? = null
    private var launcher: ActivityScenario<ANekoActivity>? = null
    private var fixture: ActivityScenario<FullscreenMediaTestActivity>? = null
    private var baseline = emptyMap<Int, Float>()
    private var lastDump = ""

    @Before
    fun launchBlankFixtureWithExistingServices() {
        automation = requireNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        val context = instrumentation.targetContext
        assumeTrue("Requires the user's already bound ANeko Accessibility service; use --no-restart.",
            MediaAccessibilityService.isEnabled(context))
        assertTrue("The existing phone overlay permission is required; this test does not grant it.",
            Settings.canDrawOverlays(context))
        prefs = context.getSharedPreferences(context.packageName + "_preferences", 0)
        saved = listOf(MediaReactionPreferences.ENABLED, MediaReactionPreferences.FULLSCREEN_FADE_ENABLED,
            MediaReactionPreferences.FULLSCREEN_FADE_OPACITY).associateWith { prefs.all[it] }
        originalOpacityPreferences = opacityPreferences()
        originalManualRegion = MediaRegionStore.manualRegion.value
        if (!readOverlay().started) launcher = ActivityScenario.launch(ANekoActivity::class.java)
        baseline = awaitOverlay("Start the existing phone companion before running opacity acceptance") {
            it.pets.isNotEmpty()
        }.pets.associate { it.index to it.baseOpacity }
        fixture = ActivityScenario.launch(FullscreenMediaTestActivity::class.java)
        await("Synthetic media fixture did not gain focus") {
            var focused = false
            fixture?.onActivity { focused = it.hasWindowFocus() }
            focused
        }
        // Enable all-app media inspection only after the synthetic fixture has focus.
        MediaRegionStore.setManual(null)
        assertTrue(prefs.edit().putBoolean(MediaReactionPreferences.ENABLED, false)
            .putBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, true)
            .putFloat(MediaReactionPreferences.FULLSCREEN_FADE_OPACITY, 0.10f).commit())
        val normal = awaitOverlay("Normal native button/frame must not fade the companion") {
            it.pets.isNotEmpty() && it.pets.all { pet -> !pet.faded && abs(pet.viewAlpha - 1f) < 0.005f }
        }
        assertBaseUnchanged(normal)
    }

    @After
    fun restoreOriginalPreferences() {
        try {
            if (::prefs.isInitialized) {
                val editor = prefs.edit()
                saved.forEach { (key, value) ->
                    when (value) {
                        null -> editor.remove(key)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        else -> throw AssertionError("Unexpected optional preference type for $key")
                    }
                }
                assertTrue("Could not restore media preferences", editor.commit())
                MediaRegionStore.setManual(originalManualRegion)
            }
        } finally {
            // Return to the prior app only after restoring its original inspection opt-in.
            try { fixture?.close() } finally { launcher?.close() }
        }
    }

    @Test
    fun actualOverlayCapsBothChoicesAndRestoresAfterResizeOrDisable() {
        fixture?.onActivity { it.showFullscreenVideo() }
        awaitFullscreenRegion()
        assertFaded(0.10f)
        assertTrue(prefs.edit().putFloat(MediaReactionPreferences.FULLSCREEN_FADE_OPACITY, 0.20f).commit())
        assertFaded(0.20f)

        fixture?.onActivity { it.showInlineVideo() }
        await("Shrinking the native video did not remove fullscreen intent") { !hasFixtureFullscreenRegion() }
        assertRestored()
        fixture?.onActivity { it.showNormalLayout() }
        assertRestored()

        fixture?.onActivity { it.showFullscreenVideo() }
        awaitFullscreenRegion()
        assertFaded(0.20f)
        assertTrue(prefs.edit().putBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, false).commit())
        assertRestored()
        fixture?.onActivity { it.showNormalLayout() }
        assertEquals("Saved opacity preferences changed", originalOpacityPreferences, opacityPreferences())
    }

    private fun assertFaded(cap: Float) {
        val state = awaitOverlay("Production overlay did not apply the $cap opacity cap") { current ->
            current.pets.size == baseline.size && current.pets.all { pet ->
                val base = baseline[pet.index]
                base != null && pet.faded && abs(pet.effectiveOpacity - min(base, cap)) < 0.005f
            }
        }
        assertBaseUnchanged(state)
        state.pets.forEach { pet ->
            val base = requireNotNull(baseline[pet.index])
            assertEquals(if (base > 0f) min(base, cap) / base else 1f, pet.viewAlpha, 0.005f)
        }
    }

    private fun assertRestored() {
        val state = awaitOverlay("Production overlay did not restore its base opacity") { current ->
            current.pets.size == baseline.size && current.pets.all { pet ->
                !pet.faded && baseline[pet.index]?.let { abs(pet.effectiveOpacity - it) < 0.005f } == true &&
                    abs(pet.viewAlpha - 1f) < 0.005f
            }
        }
        assertBaseUnchanged(state)
    }

    private fun assertBaseUnchanged(state: OverlayState) {
        assertEquals("Pet count changed", baseline.size, state.pets.size)
        state.pets.forEach { pet ->
            assertEquals("Base opacity changed for pet ${pet.index}", requireNotNull(baseline[pet.index]),
                pet.baseOpacity, 0.00001f)
        }
        assertEquals("Saved opacity preferences changed", originalOpacityPreferences, opacityPreferences())
    }

    private fun opacityPreferences(): Map<String, Any?> = prefs.all.filterKeys {
        it == AnimationService.PREF_KEY_TRANSPARENCY || it.startsWith(AnimationService.PREF_KEY_TRANSPARENCY + ".")
    }

    private fun awaitFullscreenRegion() = await("Production detector did not report the blank native fullscreen video") {
        hasFixtureFullscreenRegion()
    }

    private fun hasFixtureFullscreenRegion(): Boolean = MediaRegionStore.detectedRegions.value.any {
        it.fullscreen && it.id.startsWith(instrumentation.targetContext.packageName + ":")
    }

    private fun awaitOverlay(message: String, predicate: (OverlayState) -> Boolean): OverlayState {
        var state = readOverlay()
        await(message) {
            state = readOverlay()
            state.started && state.visible && state.interactive && predicate(state)
        }
        return state
    }

    private fun readOverlay(): OverlayState {
        val descriptor = automation.executeShellCommand(
            "dumpsys activity service org.nqmgaming.aneko/.core.service.AnimationService")
        lastDump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        val header = Regex("started=(true|false) visible=(true|false) interactive=(true|false)").find(lastDump)
        val pets = Regex("pet\\[(\\d+)] mediaFaded=(true|false) baseOpacity=([0-9.Ee+-]+) " +
            "viewAlpha=([0-9.Ee+-]+) effectiveOpacity=([0-9.Ee+-]+)").findAll(lastDump).map { match ->
            PetOpacity(match.groupValues[1].toInt(), match.groupValues[2].toBoolean(),
                match.groupValues[3].toFloat(), match.groupValues[4].toFloat(), match.groupValues[5].toFloat())
        }.toList()
        return OverlayState(header?.groupValues?.get(1) == "true", header?.groupValues?.get(2) == "true",
            header?.groupValues?.get(3) == "true", pets)
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(200)
        }
        assertTrue("$message\nLast production service dump:\n${lastDump.take(4096)}", predicate())
    }

    private data class PetOpacity(val index: Int, val faded: Boolean, val baseOpacity: Float,
        val viewAlpha: Float, val effectiveOpacity: Float)
    private data class OverlayState(val started: Boolean, val visible: Boolean, val interactive: Boolean,
        val pets: List<PetOpacity>)
}
