package org.nqmgaming.aneko.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.nqmgaming.aneko.R

/** Debug-only native views with synthetic content for real accessibility acceptance tests. */
@SuppressLint("SetTextI18n")
class ReactionTestActivity : Activity() {
    lateinit var header: TextView
        private set
    lateinit var editor: EditText
        private set
    lateinit var password: EditText
        private set
    lateinit var pane: ScrollView
        private set
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        header = TextView(this).apply {
            id = R.id.reaction_fixture_header
            text = "REACTION TEST — FIXED HEADER"
            contentDescription = "Synthetic fixed header"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.LTGRAY)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(72)))
        editor = EditText(this).apply {
            id = R.id.reaction_fixture_editor
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            hint = "Synthetic cursor field"
            setText("alpha beta gamma delta")
            setTextColor(Color.BLACK)
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, dp(56)))
        password = EditText(this).apply {
            id = R.id.reaction_fixture_password
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            hint = "Synthetic password field"
            setText("synthetic-only-123")
            setTextColor(Color.BLACK)
        }
        root.addView(password, LinearLayout.LayoutParams(-1, dp(56)))
        pane = ScrollView(this).apply {
            id = R.id.reaction_fixture_scroll
            contentDescription = "Synthetic scrolling pane"
            isFillViewport = true
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { index ->
            rows.addView(TextView(this).apply {
                text = "Synthetic scrolling row ${index + 1}"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(Color.BLACK)
                setBackgroundColor(if (index % 2 == 0) Color.WHITE else 0xffeeeeee.toInt())
            }, LinearLayout.LayoutParams(-1, dp(64)))
        }
        pane.addView(rows)
        root.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestFocus()
    }

    fun focusEditor(selection: Int) {
        editor.requestFocus()
        editor.setSelection(selection.coerceIn(0, editor.length()))
        editor.post { getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun focusEditorOnSide(right: Boolean) {
        editor.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        editor.gravity = (if (right) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        focusEditor(if (right) editor.length() else 0)
    }

    fun focusPassword() {
        password.requestFocus()
        password.setSelection(password.length())
        password.post { getSystemService(InputMethodManager::class.java).showSoftInput(password, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun clearInputFocus() {
        root.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
    }

    fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    fun screenBounds(view: View): Rect {
        val location = IntArray(2).also(view::getLocationOnScreen)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun scrollContentBy(distanceDp: Int) { pane.scrollBy(0, dp(distanceDp)) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
