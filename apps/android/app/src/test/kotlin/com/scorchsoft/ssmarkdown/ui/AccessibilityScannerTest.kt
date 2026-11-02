package com.scorchsoft.ssmarkdown.ui

import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.scorchsoft.ssmarkdown.fixtures.AccessibilityScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scanner finds what it is meant to find. [com.scorchsoft.ssmarkdown.AccessibilityScanTest]
 * passing proves nothing if the scan sees no nodes, so this renders a
 * screen that breaks each gated rule and expects each one reported, and
 * shows a suppression covers only the element it names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AccessibilityScannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun badScreen(): View {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            Column(Modifier.background(Color.White).padding(16.dp)) {
                // Clickable and unlabelled: no name.
                Box(Modifier.size(24.dp).background(Color.Black).clickable {})
                // Compose widens a small Compose target's accessibility bounds
                // (and its hit area) towards 48 dp, so the 24 dp target here is
                // a platform button, which keeps its size.
                AndroidView(
                    factory = { context ->
                        Button(context).apply {
                            contentDescription = "Tiny"
                            minWidth = 0
                            minHeight = 0
                            minimumWidth = 0
                            minimumHeight = 0
                        }
                    },
                    modifier = Modifier.padding(top = 32.dp).size(24.dp),
                )
                // Light grey on white: about 1.5:1.
                Text("Faint words", color = Color(0xFFCCCCCC), fontSize = 14.sp)
            }
        }
        rule.waitForIdle()
        return view.rootView
    }

    @Test
    fun `an unlabelled small control and faint text are all reported`() {
        val root = badScreen()
        val errors = rule.runOnIdle { AccessibilityScanner.errors(listOf(root)) }
        val checks = errors.map { it.check }.toSet()
        assertTrue(errors.joinToString("\n"), "SpeakableTextPresentCheck" in checks)
        assertTrue(errors.joinToString("\n"), "TouchTargetSizeCheck" in checks)
        assertTrue(errors.joinToString("\n"), "TextContrastCheck" in checks)
    }

    @Test
    fun `a suppression silences only the check and element it names`() {
        val root = badScreen()
        val smallBox =
            AccessibilityScanner.Suppression(TouchTargetSizeCheck::class.java, "test: the 24 dp box") {
                it.element?.isClickable == true
            }
        val errors = rule.runOnIdle { AccessibilityScanner.errors(listOf(root), listOf(smallBox)) }
        assertEquals(0, errors.count { it.check == "TouchTargetSizeCheck" })
        assertTrue(errors.any { it.check == "SpeakableTextPresentCheck" })
    }
}
