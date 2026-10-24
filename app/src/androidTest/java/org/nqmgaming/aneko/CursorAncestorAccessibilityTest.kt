package org.nqmgaming.aneko

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.nqmgaming.aneko.core.cursor.CursorPreferences
import org.nqmgaming.aneko.core.cursor.CursorTargetExtractor
import org.nqmgaming.aneko.core.cursor.CursorTargetStore
import org.nqmgaming.aneko.core.media.MediaAccessibilityService
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.debug.ReactionTestActivity

/** Real provider ancestry above the old24-node cutoff, using synthetic editor content only. */
@RunWith(AndroidJUnit4::class)
class CursorAncestorAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var automation: UiAutomation
    private lateinit var prefs: SharedPreferences
    private var originalCursorPreference: Boolean? = null
    private var scenario: ActivityScenario<ReactionTestActivity>? = null

    @Before
    fun startWithExistingAccessibilityGrant() {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val context = instrumentation.targetContext
        assumeTrue("Enable the shared ANeko Accessibility service before this device test",
            MediaAccessibilityService.isEnabled(context))
        prefs = context.getSharedPreferences(context.packageName + "_preferences", 0)
        originalCursorPreference = prefs.all[CursorPreferences.ENABLED] as? Boolean
        assertTrue(prefs.edit().putBoolean(CursorPreferences.ENABLED, true).commit())
        scenario = ActivityScenario.launch(ReactionTestActivity::class.java)
        await("Native fixture did not gain focus") { onFixture { it.hasWindowFocus() } }
        await("Existing Accessibility service did not connect") { MediaRegionStore.isDetectionConnected.value }
        CursorTargetStore.clear()
    }

    @After
    fun restoreCursorPreferenceAndCloseFixture() {
        try { scenario?.close() }
        finally {
            if (::prefs.isInitialized) {
                val edit = prefs.edit()
                originalCursorPreference?.let { edit.putBoolean(CursorPreferences.ENABLED, it) }
                    ?: edit.remove(CursorPreferences.ENABLED)
                assertTrue(edit.commit())
            }
            CursorTargetStore.clear()
        }
    }

    @Test
    fun normalEditorBelowFortyExposedAncestorsPublishesRealCaretGeometry() {
        wrapEditor(AncestorKind.NORMAL)
        await("Native editor did not open its keyboard") { onFixture { it.keyboardVisible() } }
        await("Deep editor was not exposed with at least40 provider ancestors") {
            inspectEditor { node ->
                if (ancestorCount(node) < 40) false else {
                    assertNull(CursorTargetExtractor.exclusionReason(node))
                    true
                }
            } == true
        }
        await("Deep normal editor did not publish actual character geometry") {
            CursorTargetStore.targets.value?.packageName == instrumentation.targetContext.packageName
        }
    }

    @Test
    fun sensitiveAncestorAboveTheOldCutoffStillExcludesItsEditor() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        assertDeepExclusion(AncestorKind.SENSITIVE, "sensitive_ancestor")
    }

    @Test
    fun passwordInputTypeAncestorAboveTheOldCutoffStillExcludesItsEditor() {
        assertDeepExclusion(AncestorKind.PASSWORD_INPUT_TYPE, "password_input_type_ancestor")
    }

    private fun assertDeepExclusion(kind: AncestorKind, expected: String) {
        wrapEditor(kind)
        await("Deep editor or ancestor sensitivity was not exposed") {
            inspectEditor { node -> ancestorCount(node) >= 40 &&
                CursorTargetExtractor.exclusionReason(node) == expected } == true
        }
        // More than one detector poll, rather than accepting a transient null.
        val until = SystemClock.uptimeMillis() + 1_250L
        while (SystemClock.uptimeMillis() < until) {
            assertNull("Sensitive deep editor must never publish caret geometry", CursorTargetStore.targets.value)
            SystemClock.sleep(25L)
        }
    }

    private enum class AncestorKind { NORMAL, SENSITIVE, PASSWORD_INPUT_TYPE }

    private fun wrapEditor(kind: AncestorKind) {
        onFixture { activity ->
            val editor = activity.editor
            val parent = editor.parent as ViewGroup
            val index = parent.indexOfChild(editor)
            val layout = editor.layoutParams
            parent.removeView(editor)
            var first: FrameLayout? = null
            var previous: FrameLayout? = null
            repeat(40) { level ->
                val frame = object : FrameLayout(activity) {
                    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(info)
                        // Provider-owned security metadata on the outermost ancestor only;
                        // no text, selection or character geometry is fabricated.
                        if (level == 0) when (kind) {
                            AncestorKind.SENSITIVE -> if (Build.VERSION.SDK_INT >= 34) info.isAccessibilityDataSensitive = true
                            AncestorKind.PASSWORD_INPUT_TYPE -> info.inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_PASSWORD
                            AncestorKind.NORMAL -> Unit
                        }
                    }
                }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                if (first == null) first = frame
                previous?.addView(frame, FrameLayout.LayoutParams(-1, -1))
                previous = frame
            }
            requireNotNull(previous).addView(editor, FrameLayout.LayoutParams(-1, -1))
            parent.addView(requireNotNull(first), index, layout)
            activity.focusEditor(5)
        }
    }

    private fun <T> inspectEditor(action: (AccessibilityNodeInfo) -> T): T? {
        val windows = automation.windows.orEmpty()
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused) continue
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() != instrumentation.targetContext.packageName) continue
                    val editor = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                    try { return action(editor) }
                    finally { recycle(editor) }
                } finally { recycle(root) }
            }
            return null
        } finally { windows.forEach(::recycle) }
    }

    private fun ancestorCount(node: AccessibilityNodeInfo): Int {
        var parent = node.parent
        var count = 0
        while (parent != null && count < 140) {
            val current = parent
            parent = try { current.parent } finally { recycle(current) }
            count++
        }
        parent?.let(::recycle)
        return count
    }

    private fun <T> onFixture(action: (ReactionTestActivity) -> T): T {
        var result: Any? = null
        requireNotNull(scenario).onActivity { result = action(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue(message, condition())
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) { if (Build.VERSION.SDK_INT < 33) node.recycle() }

    @Suppress("DEPRECATION")
    private fun recycle(window: AccessibilityWindowInfo) { if (Build.VERSION.SDK_INT < 33) window.recycle() }
}
