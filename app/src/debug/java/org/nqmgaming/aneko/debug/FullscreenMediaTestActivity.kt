package org.nqmgaming.aneko.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Native blank renderer only: no media URL, decoder, playback, audio or external content. */
@SuppressLint("SetTextI18n")
class FullscreenMediaTestActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var video: VideoView
    private lateinit var normal: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        video = VideoView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Synthetic blank media renderer"
            setBackgroundColor(Color.DKGRAY)
            visibility = View.GONE
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        normal = FrameLayout(this).apply {
            addView(Button(this@FullscreenMediaTestActivity).apply {
                text = "Synthetic normal button and frame"
                setOnClickListener { showFullscreenVideo() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        root.addView(normal, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    fun showFullscreenVideo() {
        normal.visibility = View.GONE
        video.layoutParams = FrameLayout.LayoutParams(-1, -1)
        video.visibility = View.VISIBLE
    }

    fun showInlineVideo() {
        normal.visibility = View.GONE
        video.layoutParams = FrameLayout.LayoutParams(-1, (root.height * 0.30f).toInt().coerceAtLeast(1))
        video.visibility = View.VISIBLE
    }

    fun showNormalLayout() {
        video.visibility = View.GONE
        normal.visibility = View.VISIBLE
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
}
