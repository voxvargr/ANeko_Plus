package org.nqmgaming.aneko.core.media

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import org.nqmgaming.aneko.core.cursor.CursorPreferences
import org.nqmgaming.aneko.core.cursor.CursorTargetExtractor
import org.nqmgaming.aneko.core.cursor.CursorTargetStore
import org.nqmgaming.aneko.core.scroll.ScrollImpulse
import org.nqmgaming.aneko.core.scroll.ScrollImpulseStore
import org.nqmgaming.aneko.core.scroll.ScrollPreferences
import org.nqmgaming.aneko.core.scroll.ScrollDeltaTracker
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Separately opt-in local media, caret and scroll geometry. Never captures pixels, performs
 * actions in another app, or persists/logs/transmits accessible text.
 */
class MediaAccessibilityService : AccessibilityService() {
    companion object {
        private val SUPPORTED_PACKAGES = setOf("com.google.android.youtube", "com.facebook.katana")
        private const val DEBOUNCE_MS = 100L
        private const val POLL_MS = 400L
        private const val IDLE_GENERIC_POLL_MS = 1_500L
        private const val STALE_MS = 1_600L
        private const val MAX_NODES = MediaSnapshotLimits.MAX_NODES
        private const val MAX_DEPTH = MediaSnapshotLimits.MAX_DEPTH

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            val component = ComponentName(context, MediaAccessibilityService::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo.serviceInfo
                    ComponentName(service.packageName, service.name) == component
                }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var connected = false
    private var scanPending = false
    private var foregroundPackage: String? = null
    private var foregroundRootKey: String? = null
    private var confirmedContainers: Set<String> = emptySet()
    private var lastFreshRootAt = 0L
    private var rootRetryUntil = 0L
    private var cursorScanPending = false
    private var cursorRootKey: String? = null
    private var cursorFieldKey: Int? = null
    private var scrollRootKey: String? = null
    private val scrollDeltas = ScrollDeltaTracker()
    private var scrollDiagnostic = "none"
    private var scrollEventAt = 0L
    private var scrollEventCount = 0L
    private var scrollPublishedCount = 0L
    private var screenReceiverRegistered = false
    private val scan = Runnable {
        scanPending = false
        scanVisibleRoot()
    }
    private val poll = Runnable { scanVisibleRoot() }
    private val cursorScan = Runnable {
        cursorScanPending = false
        scanCursor()
    }
    private val cursorPoll = Runnable { scanCursor() }
    private val cursorExpiry = Runnable { CursorTargetStore.expire(SystemClock.uptimeMillis()) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                stopDetection()
                stopCursorDetection()
                clearScrollScope()
            } else {
                if (detectionEnabled()) scheduleScan()
                if (cursorEnabled()) scheduleCursorScan()
            }
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            MediaReactionPreferences.ENABLED, MediaReactionPreferences.FULLSCREEN_FADE_ENABLED -> {
                configureMediaNodeVisibility()
                confirmedContainers = emptySet()
                MediaRegionStore.clearDetected()
                if (detectionEnabled()) scheduleScan() else stopDetection(clearManual = true)
            }
            CursorPreferences.ENABLED ->
                if (cursorEnabled()) scheduleCursorScan() else stopCursorDetection()
            ScrollPreferences.ENABLED -> clearScrollScope()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        configureMediaNodeVisibility()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        MediaRegionStore.setDetectionConnected(true)
        if (detectionEnabled()) scheduleScan()
        if (cursorEnabled()) scheduleCursorScan()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        val windowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (cursorEnabled()) {
            if (windowChange || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
            ) scheduleCursorScan()
        } else stopCursorDetection()
        if (scrollEnabled()) {
            if (windowChange) reconcileScrollScope()
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) captureScroll(event)
        } else clearScrollScope()
        if (!detectionEnabled()) {
            stopDetection(clearManual = true)
            return
        }
        // Broader foreground inspection is separately authorized by the fade toggle.
        val mediaEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val sourcePackage = event.packageName?.toString()
        val fastContentScan = mediaEvent && sourceAllowed(sourcePackage) &&
            (sourcePackage in SUPPORTED_PACKAGES || MediaRegionStore.detectedRegions.value.isNotEmpty())
        if (windowChange || fastContentScan) scheduleScan()
    }

    override fun onInterrupt() {
        stopDetection()
        stopCursorDetection()
        clearScrollScope()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    /** Standard shell service diagnostics: geometry and runtime gates only; never updates detection. */
    protected override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        var root: AccessibilityNodeInfo? = null
        try {
            val info = serviceInfo
            writer.println("connected=$connected mediaEnabled=${reactionsEnabled()} fadeEnabled=${fullscreenFadeEnabled()} awake=${deviceAwake()} " +
                "serviceFlags=${info?.flags} eventTypes=${info?.eventTypes} capabilities=${info?.capabilities} " +
                "accessibilityTool=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info?.isAccessibilityTool else null}")
            writer.println("scanPending=$scanPending trackedPackage=$foregroundPackage " +
                "pollScheduled=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) handler.hasCallbacks(poll) else null}")
            writer.println("actualDetectedBounds=${MediaRegionStore.detectedRegions.value.map { it.bounds }} " +
                "actualFullscreen=${MediaRegionStore.detectedRegions.value.map { it.fullscreen }}")
            writer.println("scrollEvents=$scrollEventCount published=$scrollPublishedCount " +
                "lastScrollAgeMs=${if (scrollEventAt > 0) SystemClock.uptimeMillis() - scrollEventAt else null} " +
                "lastScroll=$scrollDiagnostic")
            val currentCursor = CursorTargetStore.targets.value
            writer.println("cursorEnabled=${cursorEnabled()} scrollEnabled=${scrollEnabled()} " +
                "keyboardVisible=${hasVisibleKeyboard()} cursorScanPending=$cursorScanPending " +
                "cursorPollScheduled=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) handler.hasCallbacks(cursorPoll) else null} " +
                "cursorTargetAgeMs=${currentCursor?.let { SystemClock.uptimeMillis() - it.observedAtUptimeMs }} " +
                "cursorTargetBounds=${currentCursor?.fieldBounds} cursorCaret=${currentCursor?.let { "${it.caretX},${it.caretY}" }}")
            root = findForegroundRoot()
            val selected = root ?: run { writer.println("foregroundRoot=null"); return }
            val rootPackage = selected.packageName?.toString()
            val rootBounds = Rect().also(selected::getBoundsInScreen)
            writer.println("foregroundRoot package=$rootPackage window=${selected.windowId} " +
                "visible=${selected.isVisibleToUser} bounds=${rootBounds.flattenToString()}")
            dumpCursorState(writer, selected)
            if (!sourceAllowed(rootPackage)) return
            val display = displayBounds()
            val viewport = display?.let {
                MediaRect(rootBounds.left.toFloat(), rootBounds.top.toFloat(),
                    rootBounds.right.toFloat(), rootBounds.bottom.toFloat()).intersection(it)
            }
            writer.println("viewport=$viewport")
            if (viewport == null) return
            val budget = NodeBudget()
            val captured = snapshot(selected, "0", 0, budget)
            val rootKey = "$rootPackage:${selected.windowId}"
            val density = resources.displayMetrics.density
            val classification = captured?.let {
                MediaNodeClassifier.classify(it, viewport, rootKey,
                    minWidth = 120f * density, minHeight = 72f * density,
                    previouslyConfirmed = if (rootKey == foregroundRootKey) confirmedContainers else emptySet(),
                    includeFullscreen = fullscreenFadeEnabled(), fullscreenViewport = display ?: viewport)
            }
            writer.println("snapshotRootPresent=${captured != null} visitedNodes=${budget.count}/$MAX_NODES " +
                "childReads=${budget.childReads}/$MAX_NODES " +
                "classifiedBounds=${classification?.regions.orEmpty().map { it.bounds }}")
        } catch (error: RuntimeException) {
            writer.println("diagnosticError=${error.javaClass.simpleName}")
        } finally { root?.let(::recycleNode) }
    }

    /** Reads existing field geometry on explicit dumpsys requests; never publishes a target. */
    private fun dumpCursorState(writer: PrintWriter, root: AccessibilityNodeInfo) {
        if (!cursorEnabled()) { writer.println("cursorExtraction=disabled"); return }
        var field: AccessibilityNodeInfo? = null
        try {
            val window = root.window
            if (window != null) {
                try { writer.println("cursorWindow type=${window.type} focused=${window.isFocused} active=${window.isActive}") }
                finally { recycleWindow(window) }
            }
            val viewport = rootViewport(root)
            writer.println("cursorViewport=$viewport")
            field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editor = field ?: run { writer.println("cursorField=null"); return }
            val bounds = Rect().also(editor::getBoundsInScreen)
            val exclusion = CursorTargetExtractor.exclusionReason(editor)
            writer.println("cursorField class=${editor.className} window=${editor.windowId} " +
                "bounds=${bounds.flattenToString()} editable=${editor.isEditable} focused=${editor.isFocused} " +
                "visible=${editor.isVisibleToUser} password=${editor.isPassword} " +
                "androidSensitive=${if (Build.VERSION.SDK_INT >= 34) editor.isAccessibilityDataSensitive else null} " +
                "exclusion=$exclusion")
            if (exclusion != null) { writer.println("cursorExtraction=$exclusion"); return }
            val start = editor.textSelectionStart
            val end = editor.textSelectionEnd
            writer.println("cursorSelection start=$start end=$end collapsed=${start >= 0 && start == end} " +
                "extraDataKeys=${editor.availableExtraData.take(16).map { it.take(128) }}")
            if (editor.windowId != root.windowId || editor.packageName?.toString() != root.packageName?.toString()) {
                writer.println("cursorExtraction=source_mismatch"); return
            }
            if (!deviceAwake()) { writer.println("cursorExtraction=device_inactive"); return }
            if (viewport == null) { writer.println("cursorExtraction=no_focused_viewport"); return }
            val inspected = CursorTargetExtractor.inspect(editor, viewport, SystemClock.uptimeMillis())
            writer.println("cursorExtraction=${inspected.reason} requested=${inspected.requestedCharacters} " +
                "returnedRectangles=${inspected.returnedRectangles} validRectangles=${inspected.validRectangles} " +
                "extractedCaret=${inspected.target?.let { "${it.caretX},${it.caretY}" }}")
        } catch (error: RuntimeException) {
            writer.println("cursorDiagnosticError=${error.javaClass.simpleName}")
        } finally { field?.let(::recycleNode) }
    }

    private fun disconnect() {
        connected = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        stopDetection()
        stopCursorDetection()
        clearScrollScope()
        MediaRegionStore.setDetectionConnected(false)
    }

    private fun reactionsEnabled(): Boolean =
        prefs.getBoolean(MediaReactionPreferences.ENABLED, false)

    private fun fullscreenFadeEnabled(): Boolean =
        prefs.getBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, false)

    private fun detectionEnabled(): Boolean = reactionsEnabled() || fullscreenFadeEnabled()

    private fun sourceAllowed(sourcePackage: String?): Boolean = !sourcePackage.isNullOrBlank() &&
        (sourcePackage in SUPPORTED_PACKAGES || fullscreenFadeEnabled())

    /** Facebook's native video surfaces are not always marked important for accessibility. */
    private fun configureMediaNodeVisibility() {
        val info = serviceInfo ?: return
        val include = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        val flags = if (detectionEnabled()) info.flags or include else info.flags and include.inv()
        if (flags != info.flags) {
            info.flags = flags
            serviceInfo = info
        }
    }

    private fun cursorEnabled(): Boolean = prefs.getBoolean(CursorPreferences.ENABLED, false)
    private fun scrollEnabled(): Boolean = prefs.getBoolean(ScrollPreferences.ENABLED, false)

    private fun deviceAwake(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive != false &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun scheduleCursorScan() {
        if (!cursorScanPending) {
            cursorScanPending = true
            handler.postDelayed(cursorScan, 50L)
        }
    }

    private fun stopCursorDetection() {
        handler.removeCallbacks(cursorScan)
        handler.removeCallbacks(cursorPoll)
        handler.removeCallbacks(cursorExpiry)
        cursorScanPending = false
        cursorRootKey = null
        cursorFieldKey = null
        CursorTargetStore.clear()
    }

    private fun scanCursor() {
        handler.removeCallbacks(cursorPoll)
        if (!connected || !cursorEnabled() || !deviceAwake() || !hasVisibleKeyboard()) {
            stopCursorDetection()
            return
        }
        val root = findForegroundRoot() ?: run { stopCursorDetection(); return }
        var field: AccessibilityNodeInfo? = null
        var foundEditor = false
        try {
            val viewport = rootViewport(root) ?: run { stopCursorDetection(); return }
            val key = "${root.packageName}:${root.windowId}"
            if (cursorRootKey != key) CursorTargetStore.clear()
            cursorRootKey = key
            field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editor = field
            if (editor == null || editor.windowId != root.windowId ||
                editor.packageName?.toString() != root.packageName?.toString() ||
                !editor.isEditable || !editor.isFocused || !editor.isVisibleToUser ||
                CursorTargetExtractor.isSensitive(editor)
            ) {
                stopCursorDetection()
                return
            }
            foundEditor = true
            if (cursorFieldKey != editor.hashCode()) CursorTargetStore.clear()
            cursorFieldKey = editor.hashCode()
            val target = CursorTargetExtractor.extract(editor, viewport, SystemClock.uptimeMillis())
            CursorTargetStore.update(target)
            handler.removeCallbacks(cursorExpiry)
            if (target != null) handler.postDelayed(cursorExpiry, CursorTargetStore.MAX_AGE_MS)
        } catch (_: IllegalStateException) {
            stopCursorDetection()
        } catch (_: SecurityException) {
            stopCursorDetection()
        } catch (_: IllegalArgumentException) {
            stopCursorDetection()
        } finally {
            field?.let(::recycleNode)
            recycleNode(root)
            if (foundEditor && connected && cursorEnabled()) handler.postDelayed(cursorPoll, 500L)
        }
    }

    private fun hasVisibleKeyboard(): Boolean {
        var available = emptyList<AccessibilityWindowInfo>()
        return try {
            available = windows.orEmpty()
            val display = displayBounds() ?: return false
            available.any { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false
                else Rect().also(window::getBoundsInScreen).let {
                    MediaRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                        .intersection(display) != null
                }
            }
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        } finally { available.forEach(::recycleWindow) }
    }

    private fun clearScrollScope() {
        scrollRootKey = null
        scrollDeltas.clear()
        ScrollImpulseStore.clear()
    }

    private fun reconcileScrollScope() {
        if (!connected || !scrollEnabled() || !deviceAwake()) { clearScrollScope(); return }
        val root = findForegroundRoot() ?: run { clearScrollScope(); return }
        try {
            updateScrollScope(root)
        } catch (_: IllegalStateException) {
            clearScrollScope()
        } catch (_: SecurityException) {
            clearScrollScope()
        } finally { recycleNode(root) }
    }

    private fun updateScrollScope(root: AccessibilityNodeInfo): MediaRect? {
        val viewport = rootViewport(root)
        val key = viewport?.let { "${root.packageName}:${root.windowId}:$it" }
        if (scrollRootKey != key) {
            ScrollImpulseStore.clear()
            scrollDeltas.clear()
            scrollRootKey = key
        }
        return viewport
    }

    private fun captureScroll(event: AccessibilityEvent) {
        scrollEventCount++
        scrollEventAt = SystemClock.uptimeMillis()
        scrollDiagnostic = "package=${event.packageName} delta=${event.scrollDeltaX},${event.scrollDeltaY} " +
            "offset=${event.scrollX},${event.scrollY} reason="
        fun rejected(reason: String) { scrollDiagnostic += reason; scrollDeltas.clear() }
        if (!deviceAwake()) {
            rejected("device_inactive")
            clearScrollScope()
            return
        }
        var source: AccessibilityNodeInfo? = null
        var root: AccessibilityNodeInfo? = null
        var focused: AccessibilityNodeInfo? = null
        try {
            val scrolling = event.source ?: run { rejected("source_missing"); return }
            source = scrolling
            scrollDiagnostic += "[class=${scrolling.className} scrollable=${scrolling.isScrollable}] "
            val exclusion = CursorTargetExtractor.exclusionReason(scrolling)
            if (!scrolling.isScrollable || scrolling.isEditable || !scrolling.isVisibleToUser ||
                event.windowId != scrolling.windowId ||
                event.packageName?.toString() != scrolling.packageName?.toString() ||
                exclusion != null
            ) { rejected(exclusion ?: "source_ineligible"); return }
            val window = scrolling.window ?: run { rejected("window_missing"); return }
            val focusedApplication = try {
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused
            } finally { recycleWindow(window) }
            if (!focusedApplication) { rejected("application_unfocused"); return }
            root = findForegroundRoot() ?: run { rejected("root_missing"); return }
            if (root.windowId != scrolling.windowId ||
                root.packageName?.toString() != scrolling.packageName?.toString()
            ) { rejected("foreground_mismatch"); return }
            val viewport = updateScrollScope(root) ?: run { rejected("viewport_missing"); return }
            val bounds = Rect().also(scrolling::getBoundsInScreen).let {
                MediaRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            }.intersection(viewport) ?: run { rejected("source_outside_viewport"); return }
            scrollDiagnostic += "bounds=$bounds "
            // Editor auto-scroll must not fling a companion. A scroller containing the
            // focused editor is ambiguous, so skip it rather than interpreting typing as a gesture.
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedEditor = focused
            if (focusedEditor?.isEditable == true) {
                val editorBounds = Rect().also(focusedEditor::getBoundsInScreen)
                if (bounds.intersection(MediaRect(editorBounds.left.toFloat(), editorBounds.top.toFloat(),
                        editorBounds.right.toFloat(), editorBounds.bottom.toFloat())) != null) {
                    rejected("contains_focused_editor"); return
                }
            }
            val sourceKey = "${scrolling.packageName}:${scrolling.windowId}:${scrolling.hashCode()}:$bounds"
            val delta = scrollDeltas.observe(sourceKey, event.scrollDeltaX, event.scrollDeltaY,
                event.scrollX, event.scrollY, event.eventTime, SystemClock.uptimeMillis())
            if (delta == null) { scrollDiagnostic += "baseline_or_no_fresh_movement"; return }
            scrollPublishedCount++
            scrollDiagnostic += "published resolved=${delta.deltaX},${delta.deltaY}"
            ScrollImpulseStore.publish(ScrollImpulse(bounds, delta.deltaX, delta.deltaY,
                delta.uptimeMs, scrolling.packageName?.toString().orEmpty(), scrolling.windowId))
        } catch (_: IllegalStateException) {
            clearScrollScope()
        } catch (_: SecurityException) {
            clearScrollScope()
        } catch (_: IllegalArgumentException) {
            clearScrollScope()
        } finally {
            focused?.let(::recycleNode)
            root?.let(::recycleNode)
            source?.let(::recycleNode)
        }
    }

    private fun rootViewport(root: AccessibilityNodeInfo): MediaRect? {
        val display = displayBounds() ?: return null
        val bounds = Rect().also(root::getBoundsInScreen)
        val rootBounds = MediaRect(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat()).intersection(display)
            ?: return null
        val window = root.window ?: return null
        return try {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused) null
            else Rect().also(window::getBoundsInScreen).let {
                rootBounds.intersection(MediaRect(it.left.toFloat(), it.top.toFloat(),
                    it.right.toFloat(), it.bottom.toFloat()))
            }
        } finally { recycleWindow(window) }
    }

    private fun scheduleScan() {
        // Window data can arrive shortly after a connection or service-flag update.
        rootRetryUntil = SystemClock.elapsedRealtime() + STALE_MS
        if (!scanPending) {
            scanPending = true
            handler.postDelayed(scan, DEBOUNCE_MS)
        }
    }

    private fun stopDetection(clearManual: Boolean = false) {
        handler.removeCallbacks(scan)
        handler.removeCallbacks(poll)
        scanPending = false
        foregroundPackage = null
        foregroundRootKey = null
        confirmedContainers = emptySet()
        lastFreshRootAt = 0L
        rootRetryUntil = 0L
        if (clearManual) MediaRegionStore.clearAll() else MediaRegionStore.clearDetected()
    }

    private fun scanVisibleRoot() {
        handler.removeCallbacks(poll)
        if (!connected || !detectionEnabled()) {
            stopDetection()
            return
        }
        if (getSystemService(PowerManager::class.java)?.isInteractive == false ||
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        ) {
            stopDetection()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val root = findForegroundRoot()
        if (root == null) {
            if (lastFreshRootAt == 0L || now - lastFreshRootAt >= STALE_MS) {
                confirmedContainers = emptySet()
                MediaRegionStore.clearDetected()
                if (now < rootRetryUntil) handler.postDelayed(poll, POLL_MS) else stopDetection()
            } else if (sourceAllowed(foregroundPackage)) {
                handler.postDelayed(poll, POLL_MS)
            }
            return
        }

        try {
            val rootPackage = root.packageName?.toString()
            if (!sourceAllowed(rootPackage)) {
                stopDetection()
                return
            }
            val rootKey = "$rootPackage:${root.windowId}"
            if (rootKey != foregroundRootKey) {
                MediaRegionStore.clearDetected()
                confirmedContainers = emptySet()
            }
            foregroundPackage = rootPackage
            foregroundRootKey = rootKey
            lastFreshRootAt = now
            val display = displayBounds() ?: run {
                MediaRegionStore.clearDetected()
                return
            }
            val rootBounds = Rect().also(root::getBoundsInScreen)
            val viewport = MediaRect(rootBounds.left.toFloat(), rootBounds.top.toFloat(),
                rootBounds.right.toFloat(), rootBounds.bottom.toFloat()).intersection(display)
                ?: run {
                    confirmedContainers = emptySet()
                    MediaRegionStore.clearDetected()
                    return
                }
            val snapshot = snapshot(root, "0", 0, NodeBudget())
            val density = resources.displayMetrics.density
            val classification = snapshot?.let {
                MediaNodeClassifier.classify(
                    it, viewport, rootKey,
                    minWidth = 120f * density,
                    minHeight = 72f * density,
                    previouslyConfirmed = confirmedContainers,
                    includeFullscreen = fullscreenFadeEnabled(),
                    fullscreenViewport = display,
                )
            }
            confirmedContainers = classification?.confirmedContainerKeys.orEmpty()
            MediaRegionStore.updateDetected(classification?.regions.orEmpty())
        } catch (_: IllegalStateException) {
            // A window or node may disappear during a scroll/app transition.
            confirmedContainers = emptySet()
            MediaRegionStore.clearDetected()
        } catch (_: SecurityException) {
            confirmedContainers = emptySet()
            MediaRegionStore.clearDetected()
        } finally {
            recycleNode(root)
            if (connected && detectionEnabled() && sourceAllowed(foregroundPackage)) {
                val activeMedia = foregroundPackage in SUPPORTED_PACKAGES ||
                    MediaRegionStore.detectedRegions.value.isNotEmpty()
                handler.postDelayed(poll, if (activeMedia) POLL_MS else IDLE_GENERIC_POLL_MS)
            }
        }
    }

    /** Nonfocusable pet overlays must not replace the underlying application's root. */
    private fun findForegroundRoot(): AccessibilityNodeInfo? {
        try {
            val available = windows.orEmpty()
            try {
                val applications = available
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                        .thenByDescending { it.isActive }.thenByDescending { it.layer })
                for (window in applications) {
                    val root = window.root ?: continue
                    // An actual foreground ANeko activity clears detection; overlay windows
                    // are excluded by TYPE_APPLICATION, so they cannot hide the media root.
                    if (root.packageName != null &&
                        (root.packageName?.toString() != packageName || window.isFocused)
                    ) return root
                    recycleNode(root)
                }
            } finally {
                available.forEach(::recycleWindow)
            }
            val root = rootInActiveWindow ?: return null
            if (root.packageName?.toString() == packageName) {
                recycleNode(root)
                return null
            }
            return root
        } catch (_: IllegalStateException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
    }

    private class NodeBudget(var count: Int = 0, var childReads: Int = 0)

    private fun snapshot(
        node: AccessibilityNodeInfo,
        path: String,
        depth: Int,
        budget: NodeBudget,
    ): MediaNodeSnapshot? {
        if (depth > MAX_DEPTH || budget.count >= MAX_NODES) return null
        budget.count++
        if (!node.isVisibleToUser || excludedMediaSubtree(node, depth)) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        val children = mutableListOf<MediaNodeSnapshot>()
        if (depth < MAX_DEPTH) {
            for (index in 0 until node.childCount.coerceAtMost(MAX_NODES)) {
                if (budget.count >= MAX_NODES || budget.childReads >= MAX_NODES) break
                budget.childReads++
                val child = node.getChild(index) ?: continue
                try {
                    snapshot(child, "$path.$index", depth + 1, budget)?.let(children::add)
                } finally {
                    recycleNode(child)
                }
            }
        }
        return MediaNodeSnapshot(
            key = path,
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            label = (node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: node.text?.toString().orEmpty()).take(128),
            bounds = MediaRect(bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat()),
            children = children,
        )
    }

    private fun excludedMediaSubtree(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (node.isEditable || node.isPassword ||
            Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return true
        // Ancestors above the selected root are checked once. Within this traversal,
        // any sensitive ancestor has already pruned its entire subtree.
        if (depth == 0 && CursorTargetExtractor.isSensitive(node)) return true
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    @Suppress("DEPRECATION")
    private fun displayBounds(): MediaRect? {
        val manager = getSystemService(WindowManager::class.java) ?: return null
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.maximumWindowMetrics.bounds
        } else {
            val size = Point().also { manager.defaultDisplay.getRealSize(it) }
            Rect(0, 0, size.x, size.y)
        }
        return MediaRect(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat()).takeIf { it.isValid }
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    @Suppress("DEPRECATION")
    private fun recycleWindow(window: AccessibilityWindowInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.recycle()
    }
}
