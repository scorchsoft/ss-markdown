package com.scorchsoft.ssmarkdown.nav

import android.app.Activity
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewRootImpl

/**
 * edge-cases "Strip ¶ put the keyboard away": the Link sheet is the one
 * strip control that takes the keyboard, for its fields. Closing it gives
 * the keyboard back to the focused text, and to nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardReturnTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val imm = activity.getSystemService(InputMethodManager::class.java)

    private fun root(): ShadowViewRootImpl = Shadow.extract(activity.window.decorView.parent)

    @Test
    fun `the keyboard comes back for the focused text`() {
        val text = EditText(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(text) })
        text.requestFocus()
        // The sheet still has window focus: nothing yet.
        root().callWindowFocusChanged(false)
        imm.hideSoftInputFromWindow(text.windowToken, 0)
        KeyboardReturn.toFocusedText(text)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(imm).isSoftInputVisible)
        // The sheet has gone and the editor's window has focus again: the keyboard comes back.
        root().callWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(imm).isSoftInputVisible)
    }

    @Test
    fun `nothing opens when what has focus does not take text`() {
        val label = TextView(activity).apply { isFocusableInTouchMode = true }
        activity.setContentView(FrameLayout(activity).apply { addView(label) })
        label.requestFocus()
        root().callWindowFocusChanged(true)
        KeyboardReturn.toFocusedText(label)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }
}
