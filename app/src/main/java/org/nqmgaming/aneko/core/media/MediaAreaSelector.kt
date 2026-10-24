package org.nqmgaming.aneko.core.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.nqmgaming.aneko.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** User-started area marking; no screenshots or inspection of other apps. */
class MediaAreaSelector(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlay: View? = null
    private val density = context.resources.displayMetrics.density

    fun showLauncher() {
        dismiss()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                setText(R.string.media_mark_area)
                setOnClickListener { showSelection() }
            })
            addView(Button(context).apply {
                setText(R.string.media_mark_cancel)
                setOnClickListener { dismiss() }
            })
        }
        add(row, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END)
    }

    fun dismiss() {
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
    }

    private fun add(view: View, width: Int, height: Int, gravity: Int) {
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { this.gravity = gravity }
        windowManager.addView(view, params)
        overlay = view
    }

    private fun showSelection() {
        dismiss()
        val content = FrameLayout(context)
        content.addView(SelectionView(context), FrameLayout.LayoutParams(-1, -1))
        content.addView(TextView(context).apply {
            setText(R.string.media_mark_instruction)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xcc202020.toInt())
            setPadding((12*density).toInt(), (12*density).toInt(), (12*density).toInt(), (12*density).toInt())
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { rightMargin = (100*density).toInt() })
        content.addView(Button(context).apply {
            setText(R.string.media_mark_cancel)
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        add(content, -1, -1, Gravity.TOP or Gravity.START)
    }

    private inner class SelectionView(context: Context) : View(context) {
        private val shade = Paint().apply { color = 0x77202020 }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2*density
        }
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var selecting = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!selecting) { canvas.drawPaint(shade); return }
            val left = min(startX, endX)
            val top = min(startY, endY)
            val right = max(startX, endX)
            val bottom = max(startY, endY)
            canvas.drawRect(0f, 0f, width.toFloat(), top, shade)
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, top, left, bottom, shade)
            canvas.drawRect(right, top, width.toFloat(), bottom, shade)
            canvas.drawRect(left, top, right, bottom, outline)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    endX = startX; endY = startY
                    selecting = true
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x.coerceIn(0f, width.toFloat())
                    endY = event.y.coerceIn(0f, height.toFloat())
                }
                MotionEvent.ACTION_UP -> {
                    endX = event.x.coerceIn(0f, width.toFloat())
                    endY = event.y.coerceIn(0f, height.toFloat())
                    if (abs(endX-startX) >= 48*density && abs(endY-startY) >= 48*density) {
                        val origin = IntArray(2).also(::getLocationOnScreen)
                        MediaRegionStore.setManual(MediaRegion("manual", MediaRect(
                            min(startX,endX)+origin[0], min(startY,endY)+origin[1],
                            max(startX,endX)+origin[0], max(startY,endY)+origin[1],
                        )))
                        dismiss()
                    }
                    selecting = false
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> selecting = false
            }
            invalidate()
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
