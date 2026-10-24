package org.nqmgaming.aneko

import android.app.UiAutomation
import android.os.Bundle
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nqmgaming.aneko.core.media.MediaNodeClassifier
import org.nqmgaming.aneko.core.media.MediaNodeSnapshot
import org.nqmgaming.aneko.core.media.MediaRect
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.core.media.MediaSnapshotLimits

/** Explicit, metadata-only on-device probe; no idle wait, screenshots or arbitrary text dump. */
@RunWith(AndroidJUnit4::class)
class MediaUiProbeTest {
    @Test
    fun inspectExposedPlayerGeometry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("anekoLiveMedia") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val context = instrumentation.targetContext
        val enabled = context.getSharedPreferences(context.packageName + "_preferences", 0)
            .getBoolean("media.reactions.enabled", false)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("anekoMediaDetectorState", JSONObject()
                .put("connected", MediaRegionStore.isDetectionConnected.value)
                .put("enabled", enabled).put("detected", MediaRegionStore.detectedRegions.value.toString()).toString())
        })
        val result = JSONArray()
        val ready = awaitSupportedRoots(automation)
        try {
        for ((window, root) in ready) {
            val pkg = root.packageName?.toString()
            val windowBounds = Rect().also(window::getBoundsInScreen)
            val nodes = JSONArray()
            val mediaHints = JSONArray()
            var mediaHintCount = 0
            val playerAncestors = linkedMapOf<String, JSONObject>()
            var visited = 0
            var rawChildReads = 0
            fun visit(node: AccessibilityNodeInfo, path: String, depth: Int,
                ancestors: List<JSONObject>): CapturedNode? {
                if (visited >= 1_200 || depth > 40) return null
                val ordinal = ++visited
                val id = node.viewIdResourceName.orEmpty()
                val cls = node.className?.toString().orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                val visible = node.isVisibleToUser
                // Retain only known semantic labels; never emit arbitrary titles/editor text.
                val label = (node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.text?.toString().orEmpty()).trim().lowercase()
                val tokens = semanticTokens(label, id, cls)
                val surface = cls.endsWith("SurfaceView") || cls.endsWith("TextureView")
                val controlLabel = when {
                    label in setOf("play", "pause", "replay", "play video", "pause video", "video player") -> label
                    label.startsWith("play video,") -> "play video"
                    label.startsWith("pause video,") -> "pause video"
                    else -> ""
                }
                val metadata = JSONObject().put("path", path).put("id", id).put("class", cls)
                    .put("bounds", bounds.flattenToString()).put("visible", visible).put("ordinal", ordinal)
                    .put("tokens", JSONArray(tokens))
                val simpleId = id.substringAfterLast('/').lowercase()
                if (simpleId in setOf("player", "watch_player", "player_view", "video_player", "video_player_view",
                        "inline_video_player", "inline_video_container") || controlLabel == "video player" ||
                    cls.endsWith("VideoView") || cls.endsWith("PlayerView") || surface) {
                    (ancestors + metadata).forEach { playerAncestors[it.getString("path")] = it }
                }
                // Small/invisible controls need their own output budget so broad wrappers
                // cannot displace their geometry. Only fixed semantic tokens are emitted.
                if (surface || tokens.isNotEmpty() || controlLabel.isNotEmpty()) {
                    mediaHintCount++
                    if (mediaHints.length() < 120) mediaHints.put(JSONObject(metadata.toString()))
                }
                if (nodes.length() < 60 && visible && (id.contains(Regex("player|video|surface|pause|play|seek")) ||
                    cls.contains(Regex("SurfaceView|TextureView|VideoView|PlayerView")) || controlLabel.isNotEmpty() ||
                    bounds.width() > windowBounds.width() * 0.65f && bounds.height() > 100 &&
                    bounds.height() < windowBounds.height() * 0.8f)) {
                    nodes.put(JSONObject(metadata.toString()).put("control", controlLabel))
                }
                val children = mutableListOf<CapturedNode>()
                if (depth < 40) {
                    for (i in 0 until node.childCount.coerceAtMost(MediaSnapshotLimits.MAX_NODES)) {
                        if (visited >= 1_200 || rawChildReads >= 1_200) break
                        rawChildReads++ // Count IPC attempts even when the provider returns null.
                        val child = node.getChild(i) ?: continue
                        try { visit(child, "$path.$i", depth + 1, ancestors + metadata)?.let(children::add) }
                        finally { recycle(child) }
                    }
                }
                return CapturedNode(MediaNodeSnapshot(path, id, cls, controlLabel,
                    mediaRect(bounds)), visible, children)
            }
            val captured = visit(root, "0", 0, emptyList())
            val rootBounds = Rect().also(root::getBoundsInScreen)
            val display = if (Build.VERSION.SDK_INT >= 30)
                context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds ?: windowBounds
                else windowBounds
            val viewport = mediaRect(rootBounds).intersection(mediaRect(display))
            val budget = Budget()
            val production = captured?.let { snapshot(it, 0, budget, production = true) }
            val unrestricted = captured?.let { snapshot(it, 0, Budget(), production = false) }
            fun classify(tree: MediaNodeSnapshot?) = if (tree == null || viewport == null) "[]" else
                MediaNodeClassifier.classify(tree, viewport, "$pkg:${window.id}",
                    120f * context.resources.displayMetrics.density,
                    72f * context.resources.displayMetrics.density).regions.toString()
            result.put(JSONObject().put("package", pkg).put("window", window.id)
                .put("bounds", windowBounds.flattenToString()).put("focused", window.isFocused)
                .put("active", window.isActive).put("type", window.type).put("nodes", nodes)
                .put("mediaHints", mediaHints).put("mediaHintCount", mediaHintCount)
                .put("playerAncestors", JSONArray(playerAncestors.values.toList()))
                .put("capturedNodes", visited).put("productionVisited", budget.count)
                .put("rawChildReads", rawChildReads).put("productionChildReads", budget.childReads)
                .put("productionBudgetApproximate", true)
                .put("productionMaxDepth", MediaSnapshotLimits.MAX_DEPTH)
                .put("productionMaxNodes", MediaSnapshotLimits.MAX_NODES)
                .put("viewport", viewport.toString()).put("productionRegions", classify(production))
                .put("unrestrictedRegions", classify(unrestricted)))
        }
        } finally {
            ready.forEach { (window, root) -> recycle(root); recycle(window) }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("anekoMediaGeometry", result.toString()) })
        assumeTrue("Leave YouTube or Facebook visible for this manual probe", result.length() > 0)
        if (InstrumentationRegistry.getArguments().getString("anekoAssertDetected") == "true") {
            val deadline = android.os.SystemClock.uptimeMillis() + 12_000L
            while (org.nqmgaming.aneko.core.media.MediaRegionStore.detectedRegions.value.isEmpty() &&
                android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(200)
            val regions = org.nqmgaming.aneko.core.media.MediaRegionStore.detectedRegions.value
            instrumentation.sendStatus(0, Bundle().apply { putString("anekoDetectedRegions", regions.toString()) })
            assertTrue("Enabled detector should expose a bounded player region", regions.isNotEmpty())
        }
    }

    private data class CapturedNode(val snapshot: MediaNodeSnapshot, val visible: Boolean,
        val children: List<CapturedNode>)
    private class Budget(var count: Int = 0, var childReads: Int = 0)

    private fun semanticTokens(label: String, id: String, className: String): List<String> {
        val normalized = (label + " " + id.lowercase()).replace(Regex("full[\\s_-]+screen"), "fullscreen")
        val tokens = Regex("(?<![a-z])(video|play|pause|mute|unmute|fullscreen|seek|elapsed)(?![a-z])")
            .findAll(normalized).map { it.value }.toMutableSet()
        if (className.endsWith("SeekBar")) tokens += "seek"
        // A time label is represented by its role only; no timestamps are retained or emitted.
        if (Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b").containsMatchIn(label)) tokens += "elapsed"
        return tokens.sorted()
    }

    /** Interactive-window flags take effect asynchronously when UiAutomation connects. */
    private fun awaitSupportedRoots(automation: UiAutomation): List<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>> {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (true) {
            val matches = mutableListOf<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>>()
            val windows = try { automation.windows.orEmpty() }
                catch (_: IllegalStateException) { emptyList() }
                catch (_: SecurityException) { emptyList() }
            for (window in windows) {
                var root: AccessibilityNodeInfo? = null
                var retained = false
                try {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    root = window.root ?: continue
                    if (root.packageName?.toString() in setOf("com.google.android.youtube", "com.facebook.katana")) {
                        matches += window to root
                        retained = true
                    }
                } catch (_: IllegalStateException) {
                    // A window can disappear between enumeration and root lookup.
                } catch (_: SecurityException) {
                    // Retry until the new UiAutomation connection can retrieve windows.
                } finally {
                    if (!retained) {
                        root?.let(::recycle)
                        recycle(window)
                    }
                }
            }
            if (matches.isNotEmpty()) return matches
            if (SystemClock.uptimeMillis() >= deadline) return emptyList()
            SystemClock.sleep(100L)
        }
    }

    private fun snapshot(node: CapturedNode, depth: Int, budget: Budget, production: Boolean): MediaNodeSnapshot? {
        if (production && (depth > MediaSnapshotLimits.MAX_DEPTH || budget.count >= MediaSnapshotLimits.MAX_NODES)) return null
        budget.count++
        if (production && !node.visible) return null
        val children = mutableListOf<MediaNodeSnapshot>()
        if (!production || depth < MediaSnapshotLimits.MAX_DEPTH) {
            for (child in node.children) {
                if (production && (budget.count >= MediaSnapshotLimits.MAX_NODES ||
                    budget.childReads >= MediaSnapshotLimits.MAX_NODES)) break
                // Reconstructed tree contains successful reads only; null provider reads
                // are not represented, so this budget remains an approximation.
                budget.childReads++
                snapshot(child, depth + 1, budget, production)?.let(children::add)
            }
        }
        // An invisible ancestor is traversed only to reach its visible descendants.
        return node.snapshot.copy(viewId = if (node.visible) node.snapshot.viewId else "",
            className = if (node.visible) node.snapshot.className else "",
            label = if (node.visible) node.snapshot.label else "", children = children, visible = node.visible)
    }

    private fun mediaRect(rect: Rect) = MediaRect(rect.left.toFloat(), rect.top.toFloat(),
        rect.right.toFloat(), rect.bottom.toFloat())

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) { if (Build.VERSION.SDK_INT < 33) node.recycle() }

    @Suppress("DEPRECATION")
    private fun recycle(window: AccessibilityWindowInfo) { if (Build.VERSION.SDK_INT < 33) window.recycle() }
}
