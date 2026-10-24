package org.nqmgaming.aneko.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNodeClassifierTest {
    private val viewport = MediaRect(0f, 0f, 1_000f, 2_000f)
    private val playerBounds = MediaRect(0f, 200f, 1_000f, 760f)
    private val facebookViewport = MediaRect(0f, 0f, 1080f, 2342f)
    private val facebookSurfaceBounds = MediaRect(0f, 558f, 1080f, 1908f)

    @Test
    fun `requires both a player container and a transport control`() {
        val root = player(children = listOf(pause()))
        assertEquals(listOf(MediaRegion("app:player", playerBounds)), detect(root))
        assertTrue(detect(player()).isEmpty())
    }

    @Test
    fun `does not interpret every surface or large image as media`() {
        for (className in listOf("android.view.SurfaceView", "android.widget.ImageView")) {
            val root = node("plain", className = className, children = listOf(pause()))
            assertTrue(detect(root).isEmpty())
        }
    }

    @Test
    fun `does not interpret a title as a player container`() {
        val title = node("title", id = "video_title_view", children = listOf(pause()))
        assertTrue(detect(title).isEmpty())
    }

    @Test
    fun `recognizes a transport button identified by resource ID`() {
        val control = node("pause", id = "player_control_play_pause_replay_button",
            className = "android.widget.ImageButton", bounds = MediaRect(450f, 400f, 550f, 500f))
        assertEquals(1, detect(player(children = listOf(control))).size)
    }

    @Test
    fun `allows a sibling playback control only if it overlaps the player`() {
        val root = node("root", bounds = viewport, children = listOf(player(), pause()))
        assertEquals(1, detect(root).size)
        val unrelated = pause().copy(bounds = MediaRect(450f, 900f, 550f, 1_000f))
        assertTrue(detect(root.copy(children = listOf(player(), unrelated))).isEmpty())
    }

    @Test
    fun `clips a partially scrolled player to the visible screen`() {
        val root = player(children = listOf(pause())).copy(
            bounds = MediaRect(0f, -200f, 1_000f, 760f),
        )
        assertEquals(MediaRect(0f, 0f, 1_000f, 760f), detect(root).single().bounds)
    }

    @Test
    fun `deduplicates nested player wrappers`() {
        val inner = player(children = listOf(pause()))
        val outer = player(children = listOf(inner)).copy(
            key = "outer", bounds = MediaRect(0f, 180f, 1_000f, 780f),
        )
        assertEquals(listOf(MediaRegion("app:player", playerBounds)), detect(outer))
    }

    @Test
    fun `does not designate the entire screen as a video region`() {
        val fullscreen = player(children = listOf(pause())).copy(bounds = viewport)
        assertTrue(detect(fullscreen).isEmpty())
    }

    @Test
    fun `rejects invalid or too small bounds`() {
        assertTrue(detect(player(children = listOf(pause())).copy(
            bounds = MediaRect(Float.NaN, 0f, 1_000f, 760f),
        )).isEmpty())
        assertTrue(detect(player(children = listOf(pause())).copy(
            bounds = MediaRect(450f, 400f, 550f, 500f),
        )).isEmpty())
    }

    @Test
    fun `tracks fresh container bounds when previously verified controls auto hide`() {
        val initial = MediaNodeClassifier.classify(player(children = listOf(pause())), viewport, "app")
        val scrolled = player().copy(bounds = MediaRect(0f, 300f, 1_000f, 860f))
        val tracked = MediaNodeClassifier.classify(scrolled, viewport, "app",
            previouslyConfirmed = initial.confirmedContainerKeys)
        assertEquals(scrolled.bounds, tracked.regions.single().bounds)
        assertTrue(MediaNodeClassifier.classify(scrolled.copy(viewId = "app:id/new_player_view"),
            viewport, "app", previouslyConfirmed = initial.confirmedContainerKeys).regions.isEmpty())
    }

    @Test
    fun `youtube named player is found on first scan even when controls are hidden`() {
        // Shape recorded from the physical YouTube watch page with hidden controls.
        val observed = MediaRect(0f, 161f, 1080f, 769f)
        val player = player().copy(viewId = "com.google.android.youtube:id/watch_player",
            label = "Video player", bounds = observed)
        assertEquals(observed, MediaNodeClassifier.detect(player,
            MediaRect(0f, 0f, 1080f, 2342f),
            "com.google.android.youtube:7").single().bounds)
    }

    @Test
    fun `facebook inline video does not require visible playback controls`() {
        val player = player().copy(viewId = "com.facebook.katana:id/inline_video_container")
        assertEquals(playerBounds, MediaNodeClassifier.detect(player, viewport,
            "com.facebook.katana:8").single().bounds)
    }

    @Test
    fun `explicit player shortcuts cannot be borrowed from an unrelated package`() {
        val player = player().copy(viewId = "other.app:id/watch_player")
        assertTrue(MediaNodeClassifier.detect(player, viewport, "other.app:7").isEmpty())
        assertTrue(MediaNodeClassifier.detect(player, viewport,
            "com.google.android.youtube:7").isEmpty())
    }

    @Test
    fun `hidden controls do not turn thumbnails into players`() {
        val thumbnail = player().copy(viewId = "com.google.android.youtube:id/video_thumbnail")
        assertTrue(MediaNodeClassifier.detect(thumbnail, viewport,
            "com.google.android.youtube:7").isEmpty())
    }

    @Test
    fun `visible named player through an invisible wrapper is found and clipped`() {
        val named = player().copy(viewId = "com.google.android.youtube:id/watch_player",
            bounds = MediaRect(-50f, 200f, 1_100f, 760f))
        val wrapper = node("wrapper", bounds = viewport, children = listOf(named)).copy(visible = false)

        assertEquals(listOf(MediaRegion("com.google.android.youtube:7:player", playerBounds)),
            MediaNodeClassifier.detect(wrapper, viewport, "com.google.android.youtube:7"))
    }

    @Test
    fun `invisible players cannot reuse explicit semantics or prior confirmation`() {
        val confirmedPlayer = player(children = listOf(pause()))
        val initial = MediaNodeClassifier.classify(confirmedPlayer, viewport, "app")
        assertEquals(1, initial.regions.size)

        val hidden = MediaNodeClassifier.classify(confirmedPlayer.copy(visible = false), viewport, "app",
            previouslyConfirmed = initial.confirmedContainerKeys)
        assertTrue(hidden.regions.isEmpty())
        assertTrue(hidden.confirmedContainerKeys.isEmpty())

        val named = player(children = listOf(pause())).copy(
            viewId = "com.google.android.youtube:id/watch_player", label = "Video player", visible = false)
        assertTrue(MediaNodeClassifier.detect(named, viewport, "com.google.android.youtube:7").isEmpty())
    }

    @Test
    fun `invisible transport control cannot confirm a visible generic player`() {
        val hiddenPause = pause().copy(visible = false)
        assertTrue(detect(player(children = listOf(hiddenPause))).isEmpty())
        val siblings = node("root", bounds = viewport, children = listOf(player(), hiddenPause))
        assertTrue(detect(siblings).isEmpty())
    }

    @Test
    fun `visible transport controls through an invisible wrapper still confirm a player`() {
        val wrapper = node("wrapper", children = listOf(pause())).copy(visible = false)
        assertEquals(listOf(MediaRegion("app:player", playerBounds)), detect(player(children = listOf(wrapper))))
    }

    @Test
    fun `facebook anonymous native surface uses observed bounds instead of enclosing post`() {
        val frame = node("frame", className = "android.widget.FrameLayout", bounds = facebookSurfaceBounds,
            children = listOf(facebookSurface()))
        val post = node("post", bounds = MediaRect(0f, 345f, 1080f, 2021f), children = listOf(frame))

        assertEquals(listOf(MediaRegion("com.facebook.katana:9:surface", facebookSurfaceBounds)),
            detectFacebook(post))
    }

    @Test
    fun `facebook observed texture viewer is detected while transport controls are hidden`() {
        val observed = MediaRect(0f, 109f, 1080f, 2029f)
        val texture = facebookSurface(observed).copy(key = "texture", className = "android.view.TextureView")
        val seek = node("seek", className = "android.widget.SeekBar",
            bounds = MediaRect(0f, 2087f, 1080f, 2189f))
        val viewer = node("viewer", className = "android.widget.Button",
            bounds = MediaRect(0f, 1f, 1080f, 2137f),
            children = listOf(texture, seek, pause().copy(visible = false)))

        assertEquals(listOf(MediaRegion("com.facebook.katana:9:texture", observed)), detectFacebook(viewer))
    }

    @Test
    fun `anonymous native surface fallback is restricted to Facebook source`() {
        for (source in listOf("other.app:9", "com.google.android.youtube:9", "app")) {
            for (className in listOf("android.view.SurfaceView", "android.view.TextureView")) {
                val renderer = facebookSurface().copy(className = className)
                assertTrue(MediaNodeClassifier.detect(renderer, facebookViewport, source).isEmpty())
            }
        }
    }

    @Test
    fun `facebook anonymous fallback does not accept images thumbnail containers or renderer lookalikes`() {
        for (className in listOf("android.widget.ImageView", "android.view.ViewGroup", "custom.TextureView")) {
            val thumbnail = facebookSurface().copy(className = className,
                viewId = "com.facebook.katana:id/video_thumbnail")
            assertTrue(detectFacebook(thumbnail).isEmpty())
        }
    }

    @Test
    fun `facebook anonymous native surface cannot designate the whole screen`() {
        for (className in listOf("android.view.SurfaceView", "android.view.TextureView")) {
            assertTrue(detectFacebook(facebookSurface(facebookViewport).copy(className = className)).isEmpty())
        }
    }

    @Test
    fun `facebook anonymous native surface must remain visible after confirmation`() {
        for (className in listOf("android.view.SurfaceView", "android.view.TextureView")) {
            val surface = facebookSurface().copy(className = className)
            val initial = MediaNodeClassifier.classify(surface, facebookViewport, "com.facebook.katana:9")
            assertEquals(1, initial.regions.size)
            val hidden = MediaNodeClassifier.classify(surface.copy(visible = false), facebookViewport,
                "com.facebook.katana:9", previouslyConfirmed = initial.confirmedContainerKeys)
            assertTrue(hidden.regions.isEmpty())
            assertTrue(hidden.confirmedContainerKeys.isEmpty())
        }
    }

    @Test
    fun `facebook native surface is clipped when partially scrolled off screen`() {
        for (className in listOf("android.view.SurfaceView", "android.view.TextureView")) {
            val scrolled = facebookSurface(MediaRect(0f, -200f, 1080f, 1150f)).copy(className = className)
            assertEquals(MediaRect(0f, 0f, 1080f, 1150f), detectFacebook(scrolled).single().bounds)
            val offscreen = facebookSurface(MediaRect(0f, -1400f, 1080f, -50f)).copy(className = className)
            assertTrue(detectFacebook(offscreen).isEmpty())
        }
    }

    @Test
    fun `facebook anonymous native surface still requires valid minimum geometry`() {
        assertTrue(detectFacebook(facebookSurface(MediaRect(0f, 0f, 50f, 50f))).isEmpty())
        assertTrue(detectFacebook(facebookSurface(MediaRect(Float.NaN, 0f, 1080f, 1350f))).isEmpty())
    }

    @Test
    fun `fullscreen threshold includes exactly93 percent and clips to the display`() {
        assertFalse(MediaNodeClassifier.isFullscreen(MediaRect(0f, 0f, 1_000f, 1_859f), viewport))
        assertTrue(MediaNodeClassifier.isFullscreen(MediaRect(0f, 0f, 1_000f, 1_860f), viewport))
        assertTrue(MediaNodeClassifier.isFullscreen(MediaRect(-100f, -100f, 1_100f, 2_100f), viewport))
        assertFalse(MediaNodeClassifier.isFullscreen(MediaRect(Float.NaN, 0f, 1_000f, 2_000f), viewport))
        assertFalse(MediaNodeClassifier.isFullscreen(MediaRect(2_000f, 0f, 3_000f, 2_000f), viewport))
    }

    @Test
    fun `fullscreen youtube region is retained only when explicitly enabled`() {
        val fullscreen = player().copy(viewId = "com.google.android.youtube:id/watch_player", bounds = viewport)
        assertTrue(MediaNodeClassifier.detect(fullscreen, viewport, "com.google.android.youtube:7").isEmpty())
        val enabled = MediaNodeClassifier.detect(fullscreen, viewport, "com.google.android.youtube:7", includeFullscreen = true)
        assertEquals(listOf(MediaRegion("com.google.android.youtube:7:player", viewport,
            fullscreen = true, escapeAllowed = true)), enabled)
    }

    @Test
    fun `facebook native fullscreen fallback remains scoped to Facebook`() {
        for (name in listOf("android.view.SurfaceView", "android.view.TextureView")) {
            val renderer = facebookSurface(facebookViewport).copy(className = name)
            assertTrue(MediaNodeClassifier.detect(renderer, facebookViewport, "com.facebook.katana:9").isEmpty())
            val accepted = MediaNodeClassifier.detect(renderer, facebookViewport, "com.facebook.katana:9", includeFullscreen = true).single()
            assertTrue(accepted.fullscreen)
            assertTrue(accepted.escapeAllowed)
            for (source in listOf("other.app:9", "com.google.android.youtube:9")) {
                assertTrue(MediaNodeClassifier.detect(renderer.copy(children = listOf(pause())), facebookViewport,
                    source, includeFullscreen = true).isEmpty())
            }
        }
    }

    @Test
    fun `broader fullscreen detection accepts typed players without widening escape scope`() {
        for (name in listOf("android.widget.VideoView", "androidx.media3.ui.PlayerView")) {
            val typed = node("typed", className = name, bounds = viewport)
            assertTrue(MediaNodeClassifier.detect(typed, viewport, "other.app:9").isEmpty())
            val detected = MediaNodeClassifier.detect(typed, viewport, "other.app:9", includeFullscreen = true).single()
            assertTrue(detected.fullscreen)
            assertFalse(detected.escapeAllowed)
        }
    }

    @Test
    fun `broader named video containers require actual overlapping playback controls`() {
        val named = player().copy(viewId = "other.app:id/video_player", bounds = viewport)
        assertTrue(MediaNodeClassifier.detect(named, viewport, "other.app:9", includeFullscreen = true).isEmpty())
        val accepted = MediaNodeClassifier.detect(named.copy(children = listOf(pause())), viewport,
            "other.app:9", includeFullscreen = true).single()
        assertTrue(accepted.fullscreen)
        assertFalse(accepted.escapeAllowed)
        val unrelated = pause().copy(bounds = MediaRect(2_000f, 2_000f, 2_100f, 2_100f))
        assertTrue(MediaNodeClassifier.detect(named.copy(children = listOf(unrelated)), viewport,
            "other.app:9", includeFullscreen = true).isEmpty())
    }

    @Test
    fun `generic player labels titles and images are not enough across arbitrary apps`() {
        for (candidate in listOf(player(children = listOf(pause())).copy(bounds = viewport),
            node("label", label = "Video player", className = "android.widget.TextView", bounds = viewport,
                children = listOf(pause())),
            node("image", id = "video_player", className = "android.widget.ImageView", bounds = viewport,
                children = listOf(pause())))) {
            assertTrue(MediaNodeClassifier.detect(candidate, viewport, "other.app:9", includeFullscreen = true).isEmpty())
        }
    }

    @Test
    fun `split screen player filling its app does not mark the entire physical display fullscreen`() {
        val appViewport = MediaRect(0f, 0f, 1_000f, 1_000f)
        val typed = node("typed", className = "android.widget.VideoView", bounds = appViewport)
        val detected = MediaNodeClassifier.detect(typed, appViewport, "other.app:9", includeFullscreen = true,
            fullscreenViewport = viewport).single()
        assertFalse(detected.fullscreen)
        assertFalse(detected.escapeAllowed)
        assertEquals(appViewport, detected.bounds)
    }

    @Test
    fun `inline legacy geometry and escape permission stay unchanged when fade is enabled`() {
        val named = player().copy(viewId = "com.google.android.youtube:id/watch_player")
        val before = MediaNodeClassifier.detect(named, viewport, "com.google.android.youtube:7")
        val after = MediaNodeClassifier.detect(named, viewport, "com.google.android.youtube:7", includeFullscreen = true)
        assertEquals(before, after)
        assertFalse(after.single().fullscreen)
        assertTrue(after.single().escapeAllowed)
    }

    @Test
    fun `hidden typed fullscreen player cannot be revived by old confirmation`() {
        val typed = node("typed", className = "android.widget.VideoView", bounds = viewport)
        val initial = MediaNodeClassifier.classify(typed, viewport, "other.app:9", includeFullscreen = true)
        assertEquals(1, initial.regions.size)
        val hidden = MediaNodeClassifier.classify(typed.copy(visible = false), viewport, "other.app:9",
            previouslyConfirmed = initial.confirmedContainerKeys, includeFullscreen = true)
        assertTrue(hidden.regions.isEmpty())
        assertTrue(hidden.confirmedContainerKeys.isEmpty())
    }

    private fun facebookSurface(bounds: MediaRect = facebookSurfaceBounds) =
        node("surface", className = "android.view.SurfaceView", bounds = bounds).copy(viewId = "")

    private fun detectFacebook(root: MediaNodeSnapshot) =
        MediaNodeClassifier.detect(root, facebookViewport, "com.facebook.katana:9")

    private fun detect(root: MediaNodeSnapshot) = MediaNodeClassifier.detect(root, viewport, "app")

    private fun player(children: List<MediaNodeSnapshot> = emptyList()) =
        node("player", id = "player_view", children = children)

    private fun pause() = node("pause", label = "Pause video", className = "android.widget.Button",
        bounds = MediaRect(450f, 400f, 550f, 500f))

    private fun node(
        key: String,
        id: String = "",
        className: String = "android.view.View",
        label: String = "",
        bounds: MediaRect = playerBounds,
        children: List<MediaNodeSnapshot> = emptyList(),
    ) = MediaNodeSnapshot(key, "app:id/$id", className, label, bounds, children)
}
