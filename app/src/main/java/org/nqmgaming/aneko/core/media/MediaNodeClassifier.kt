package org.nqmgaming.aneko.core.media

internal object MediaSnapshotLimits {
    const val MAX_NODES = 400
    const val MAX_DEPTH = 32
}

/** Only transient hints are retained; accessible text is never persisted or logged. */
internal data class MediaNodeSnapshot(
    val key: String,
    val viewId: String,
    val className: String,
    val label: String,
    val bounds: MediaRect,
    val children: List<MediaNodeSnapshot> = emptyList(),
    val visible: Boolean = true,
)

internal data class MediaNodeClassification(
    val regions: List<MediaRegion>,
    val confirmedContainerKeys: Set<String>,
)

/**
 * Uses explicit player semantics or a container with an overlapping transport control.
 * Facebook's observed anonymous native SurfaceView/TextureView is accepted only in that
 * source app. This is a rendering heuristic, not playback state; bounds stay on the renderer.
 */
internal object MediaNodeClassifier {
    const val FULLSCREEN_COVERAGE = 0.93f
    private val LEGACY_PACKAGES = setOf("com.google.android.youtube", "com.facebook.katana")

    fun isFullscreen(bounds: MediaRect, viewport: MediaRect): Boolean {
        val clipped = bounds.intersection(viewport) ?: return false
        return (area(clipped) / area(viewport)).toFloat() >= FULLSCREEN_COVERAGE
    }

    fun detect(
        root: MediaNodeSnapshot,
        viewport: MediaRect,
        regionPrefix: String,
        minWidth: Float = 120f,
        minHeight: Float = 72f,
        includeFullscreen: Boolean = false,
        fullscreenViewport: MediaRect = viewport,
    ): List<MediaRegion> = classify(root, viewport, regionPrefix, minWidth, minHeight,
        includeFullscreen = includeFullscreen, fullscreenViewport = fullscreenViewport).regions

    fun classify(
        root: MediaNodeSnapshot,
        viewport: MediaRect,
        regionPrefix: String,
        minWidth: Float = 120f,
        minHeight: Float = 72f,
        previouslyConfirmed: Set<String> = emptySet(),
        includeFullscreen: Boolean = false,
        fullscreenViewport: MediaRect = viewport,
    ): MediaNodeClassification {
        if (!viewport.isValid) return MediaNodeClassification(emptyList(), emptySet())
        val sourcePackage = regionPrefix.substringBefore(':')
        val legacySource = sourcePackage in LEGACY_PACKAGES
        val candidates = mutableListOf<MediaRegion>()
        val confirmed = mutableSetOf<String>()
        fun visit(node: MediaNodeSnapshot, parent: MediaNodeSnapshot?) {
            val clipped = node.bounds.intersection(viewport)
            val identity = "${node.key}|${node.viewId}|${node.className}"
            val facebookSurface = sourcePackage == "com.facebook.katana" &&
                (node.className == "android.view.SurfaceView" || node.className == "android.view.TextureView")
            if (node.visible && clipped != null && (isPlayerContainer(node) || facebookSurface) &&
                clipped.width >= minWidth && clipped.height >= minHeight &&
                (includeFullscreen || !isFullscreen(clipped, viewport))
            ) {
                val controls by lazy { hasTransportControl(node, clipped, 0) ||
                    parent != null && hasTransportControl(parent, clipped, 0) }
                val evidence = if (includeFullscreen && !legacySource) {
                    // Across other apps, a renderer alone is insufficient. Typed players
                    // or an obvious video container with verified controls qualify.
                    isTypedPlayer(node) || isObviousVideoContainer(node) &&
                        (identity in previouslyConfirmed || controls)
                } else facebookSurface || identity in previouslyConfirmed ||
                    hasExplicitPlayerSemantics(node, regionPrefix) || controls
                if (evidence) {
                    candidates += MediaRegion("$regionPrefix:${node.key}", clipped,
                        fullscreen = includeFullscreen && isFullscreen(clipped, fullscreenViewport),
                        escapeAllowed = !includeFullscreen || legacySource)
                    // Controls commonly auto-hide. Track this same fresh container.
                    confirmed += identity
                }
            }
            // Some providers expose visible children through an invisible layout wrapper.
            node.children.forEach { visit(it, node) }
        }
        visit(root, null)

        // Nested accessibility wrappers can describe the same player more than once.
        val distinct = mutableListOf<MediaRegion>()
        candidates.sortedBy { area(it.bounds) }.forEach { candidate ->
            if (distinct.none { existing ->
                    val overlap = candidate.bounds.intersection(existing.bounds)
                    overlap != null && area(overlap) >= area(existing.bounds) * 0.85f
                }
            ) distinct += candidate
        }
        return MediaNodeClassification(distinct.take(6), confirmed)
    }

    private fun isPlayerContainer(node: MediaNodeSnapshot): Boolean {
        val id = node.viewId.substringAfterLast('/').lowercase()
        val className = node.className.substringAfterLast('.').lowercase()
        if (listOf("button", "control", "title", "thumbnail", "badge", "avatar").any(id::contains)) {
            return false
        }
        if (className == "videoview" || className.endsWith("playerview")) return true
        if (id == "player" || id == "video_player" || id == "watch_player") return true
        val mediaId = id.contains("player") || id.contains("video")
        val containerId = listOf("container", "frame", "view", "layout", "surface", "area")
            .any(id::contains)
        val label = node.label.trim().lowercase()
        return mediaId && containerId || label == "video player"
    }

    private fun isTypedPlayer(node: MediaNodeSnapshot): Boolean {
        val name = node.className.substringAfterLast('.').lowercase()
        return name == "videoview" || name.endsWith("playerview")
    }

    private fun isObviousVideoContainer(node: MediaNodeSnapshot): Boolean {
        val name = node.className.substringAfterLast('.').lowercase()
        if (listOf("textview", "imageview", "button").any(name::endsWith)) return false
        val id = node.viewId.substringAfterLast('/').lowercase()
        return isPlayerContainer(node) && (id.contains("video") ||
            node.label.trim().equals("video player", ignoreCase = true))
    }

    private fun hasExplicitPlayerSemantics(node: MediaNodeSnapshot, regionPrefix: String): Boolean {
        val sourcePackage = regionPrefix.substringBefore(':')
        if (sourcePackage !in LEGACY_PACKAGES) return false
        val resourcePackage = node.viewId.substringBefore(":id/", "")
        val id = node.viewId.substringAfterLast('/').lowercase()
        val className = node.className.substringAfterLast('.').lowercase()
        // Controls disappear during ordinary playback. These named player roots are
        // sufficient on their own; requiring a visible Pause button made detection
        // silently fail until the user happened to reveal the controls.
        val knownPlayer = resourcePackage == sourcePackage && id in setOf(
            "player", "watch_player", "player_view", "video_player", "video_player_view",
            "inline_video_player", "inline_video_container",
        )
        return knownPlayer || className == "videoview" || className.endsWith("playerview") ||
            node.label.trim().equals("video player", ignoreCase = true)
    }

    private fun hasTransportControl(node: MediaNodeSnapshot, player: MediaRect, depth: Int): Boolean {
        if (depth > 20) return false
        val overlap = node.bounds.intersection(player)
        if (node.visible && overlap != null && isTransportControl(node)) return true
        return node.children.any { hasTransportControl(it, player, depth + 1) }
    }

    private fun isTransportControl(node: MediaNodeSnapshot): Boolean {
        val id = node.viewId.substringAfterLast('/').lowercase()
        val label = node.label.trim().lowercase()
        val idTokens = id.split('_', '-', '.').toSet()
        val transportId = idTokens.any { it == "play" || it == "pause" || it == "replay" } &&
            (idTokens.any { it == "button" || it == "control" || it == "controls" } ||
                node.className.endsWith("Button"))
        val transportLabel = label in setOf("play", "pause", "replay", "play video", "pause video") ||
            label.startsWith("play video,") || label.startsWith("pause video,")
        return transportId || transportLabel
    }

    private fun area(bounds: MediaRect): Double =
        (bounds.right.toDouble() - bounds.left) * (bounds.bottom.toDouble() - bounds.top)
}
