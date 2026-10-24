package org.nqmgaming.aneko

import android.app.UiAutomation
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.nqmgaming.aneko.core.cursor.CursorPreferences
import org.nqmgaming.aneko.core.cursor.CursorTarget
import org.nqmgaming.aneko.core.cursor.CursorTargetStore
import org.nqmgaming.aneko.core.media.MediaAccessibilityService
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.core.scroll.ScrollImpulse
import org.nqmgaming.aneko.core.scroll.ScrollImpulseStore
import org.nqmgaming.aneko.core.scroll.ScrollPreferences
import org.nqmgaming.aneko.core.service.AnimationService
import org.nqmgaming.aneko.debug.ReactionTestActivity
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Uses the user's already enabled service, synthetic views and temporary feature preferences. */
@RunWith(AndroidJUnit4::class)
class ReactionAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var prefs: SharedPreferences
    private var saved = emptyMap<String, Any?>()
    private var scenario: ActivityScenario<ReactionTestActivity>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun launchSyntheticFixtureWithExistingAccessibilityService() {
        // The default UiAutomation mode suppresses the very service this test exercises.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        assumeTrue("Enable ANeko's shared Accessibility service before this device acceptance test; it will not enable it automatically.",
            MediaAccessibilityService.isEnabled(context))
        prefs = context.getSharedPreferences(context.packageName + "_preferences", 0)
        saved = listOf(CursorPreferences.ENABLED, ScrollPreferences.ENABLED).associateWith { prefs.all[it] }
        assertTrue("Could not temporarily enable cursor/scroll preferences", prefs.edit()
            .putBoolean(CursorPreferences.ENABLED, true).putBoolean(ScrollPreferences.ENABLED, true).commit())
        CursorTargetStore.clear()
        ScrollImpulseStore.clear()
        scenario = ActivityScenario.launch(ReactionTestActivity::class.java)
        await("The synthetic fixture did not gain a focused window") { onFixture { it.hasWindowFocus() } }
        await("The enabled Accessibility service did not connect") { MediaRegionStore.isDetectionConnected.value }
    }

    @After
    fun restoreOriginalPreferencesAndCloseFixture() {
        try { scenario?.close() }
        finally {
            scope.cancel()
            if (::prefs.isInitialized) {
                val edit = prefs.edit()
                saved.forEach { (key, value) ->
                    when (value) {
                        null -> edit.remove(key)
                        is Boolean -> edit.putBoolean(key, value)
                        is String -> edit.putString(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Float -> edit.putFloat(key, value)
                        else -> throw AssertionError("Unexpected saved preference type for $key")
                    }
                }
                assertTrue("Could not restore original optional-feature preferences", edit.commit())
            }
            CursorTargetStore.clear()
            ScrollImpulseStore.clear()
        }
    }

    @Test
    fun nativeCaretMovesAcrossSpaceAndSensitiveOrUnfocusedFieldsClearIt() {
        onFixture { it.focusEditor(5) }
        await("Native EditText did not open the input method") { onFixture { it.keyboardVisible() } }
        val first = awaitCursor("No actual character geometry was extracted from the native EditText")
        val editor = onFixture { it.screenBounds(it.editor) }
        assertNearBounds(editor, first)
        assertTrue(first.caretX in first.fieldBounds.left..first.fieldBounds.right)
        assertTrue(first.caretY in first.fieldBounds.top..first.fieldBounds.bottom)

        // Offsets 5 and 6 straddle the synthetic space in "alpha beta".
        val selectionChangedAt = SystemClock.uptimeMillis()
        onFixture { it.editor.setSelection(6) }
        var moved: CursorTarget? = null
        await("Caret geometry did not move when selection crossed the synthetic space") {
            moved = CursorTargetStore.targets.value?.takeIf { target ->
                target.observedAtUptimeMs >= selectionChangedAt && target.windowId == first.windowId &&
                    (abs(target.caretX - first.caretX) > 0.5f || abs(target.caretY - first.caretY) > 0.5f)
            }
            moved != null
        }
        assertNotNull(moved)

        onFixture { it.focusPassword() }
        assertTrue("The synthetic password field did not receive input focus", onFixture { it.password.hasFocus() })
        await("Password input must not publish caret geometry") { CursorTargetStore.targets.value == null }
        assertCursorRemainsAbsent("Password input published caret geometry")
        onFixture { it.focusEditor(6) }
        awaitCursor("Normal editor geometry did not return after leaving the synthetic password field")
        onFixture { it.clearInputFocus() }
        await("Clearing editor focus must clear its cursor target") { CursorTargetStore.targets.value == null }
        assertCursorRemainsAbsent("Unfocused input republished stale caret geometry")
    }

    @Test
    fun scrollEventUsesActualPaneBoundsAndExcludesFixedHeader() {
        onFixture { it.clearInputFocus() }
        await("Input method did not close for the scrolling fixture") { !onFixture { it.keyboardVisible() } }
        val events = CopyOnWriteArrayList<ScrollImpulse>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ScrollImpulseStore.events.collect { if (it != null) events += it }
        }
        val pane = onFixture { it.screenBounds(it.pane) }
        val header = onFixture { it.screenBounds(it.header) }
        onFixture { it.scrollContentBy(96) }
        var captured: ScrollImpulse? = null
        await("No positive scroll displacement was captured from the native ScrollView") {
            captured = events.firstOrNull { it.packageName == instrumentation.targetContext.packageName && it.deltaY > 0f }
            captured != null
        }
        val impulse = requireNotNull(captured)
        assertTrue("The fixed header was included in the scrolling area", impulse.bounds.top >= header.bottom - 2f)
        assertEquals(pane.left.toFloat(), impulse.bounds.left, 2f)
        assertEquals(pane.top.toFloat(), impulse.bounds.top, 2f)
        assertEquals(pane.right.toFloat(), impulse.bounds.right, 2f)
        assertTrue(impulse.bounds.bottom <= pane.bottom + 2f)
        assertTrue("Native scrollBy must produce positive content displacement", impulse.deltaY > 0f)
        assertEquals(0f, impulse.deltaX, 0f)
    }

    @Test
    fun actualPhoneOverlayMovesTowardNativeCaretWithoutChangingMotionPreferences() {
        assertFalse("Quiet Mode intentionally disables cursor chasing; leave it off for motion acceptance.",
            prefs.getBoolean(AnimationService.PREF_KEY_QUIET_MODE, false))
        val originalMotion = prefs.all.filterKeys { it.startsWith("motion.") }
        val originalManual = MediaRegionStore.manualRegion.value
        val automation = requireNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        var lastDump = ""
        fun motions(): List<PetMotion> {
            val descriptor = automation.executeShellCommand(
                "dumpsys activity service org.nqmgaming.aneko/.core.service.AnimationService")
            lastDump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            return Regex("petMotion\\[(\\d+)] center=([0-9.Ee+-]+),([0-9.Ee+-]+) cursorControlled=(true|false)")
                .findAll(lastDump).map { match -> PetMotion(match.groupValues[1].toInt(),
                    match.groupValues[2].toFloat(), match.groupValues[3].toFloat(), match.groupValues[4].toBoolean()) }
                .toList()
        }
        try {
            MediaRegionStore.setManual(null)
            await("Old media geometry did not clear for the native input fixture") { MediaRegionStore.regions.value.isEmpty() }
            onFixture { it.focusEditor(0) }
            await("Native EditText did not open the input method") { onFixture { it.keyboardVisible() } }
            awaitCursor("No native caret geometry was available for overlay motion")
            var controlled: PetMotion? = null
            await("Start a visible phone companion before cursor motion acceptance. Last dump:\n$lastDump") {
                controlled = motions().firstOrNull { it.cursorControlled }
                controlled != null
            }
            val start = requireNotNull(controlled)
            val right = start.x < onFixture { it.editor.width / 2f }
            val requestedAt = SystemClock.uptimeMillis()
            onFixture { it.focusEditorOnSide(right) }
            awaitCursor("Opposite-side native caret geometry did not refresh", requestedAt)
            val threshold = 8f * instrumentation.targetContext.resources.displayMetrics.density
            val direction = if (right) 1f else -1f
            await("Production pet did not visibly chase the native caret. Last dump:\n$lastDump") {
                motions().any { it.index == start.index && it.cursorControlled &&
                    (it.x - start.x) * direction >= threshold }
            }
            assertEquals("Cursor chase changed saved motion preferences", originalMotion,
                prefs.all.filterKeys { it.startsWith("motion.") })
        } finally { MediaRegionStore.setManual(originalManual) }
    }

    private fun awaitCursor(message: String, observedAfter: Long = 0L): CursorTarget {
        var target: CursorTarget? = null
        await(message) {
            target = CursorTargetStore.targets.value?.takeIf { it.packageName == instrumentation.targetContext.packageName &&
                it.observedAtUptimeMs >= observedAfter }
            target != null
        }
        return requireNotNull(target)
    }

    private data class PetMotion(val index: Int, val x: Float, val y: Float, val cursorControlled: Boolean)

    private fun assertNearBounds(expected: Rect, target: CursorTarget) {
        assertEquals(expected.left.toFloat(), target.fieldBounds.left, 2f)
        assertEquals(expected.top.toFloat(), target.fieldBounds.top, 2f)
        assertEquals(expected.right.toFloat(), target.fieldBounds.right, 2f)
        assertEquals(expected.bottom.toFloat(), target.fieldBounds.bottom, 2f)
    }

    private fun assertCursorRemainsAbsent(message: String) {
        // Cover more than one 500 ms detector poll, rather than accepting a transient null.
        val until = SystemClock.uptimeMillis() + 750L
        while (SystemClock.uptimeMillis() < until) {
            assertTrue(message, CursorTargetStore.targets.value == null)
            Thread.sleep(25)
        }
    }

    private fun <T> onFixture(action: (ReactionTestActivity) -> T): T {
        var result: Any? = null
        requireNotNull(scenario).onActivity { result = action(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(message: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }
}
