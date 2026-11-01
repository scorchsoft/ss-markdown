package com.scorchsoft.ssmarkdown.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ShellScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun render(dark: Boolean) {
        rule.setContent {
            SSMarkdownTheme(darkTheme = dark) {
                var mode by remember { mutableStateOf(ViewMode.Code) }
                ShellScreen(mode = mode, onModeSelect = { mode = it })
            }
        }
    }

    @Test
    fun `light shell shows both bars`() {
        render(dark = false)
        assertBars()
    }

    @Test
    fun `dark shell shows both bars`() {
        render(dark = true)
        assertBars()
    }

    @Test
    fun `tapping a segment selects it`() {
        render(dark = false)
        rule.onNodeWithText("Split").performClick()
        rule.onNodeWithText("Split").assertIsSelected()
        rule.onNodeWithText("Code").assertIsNotSelected()
    }

    private fun assertBars() {
        rule.onNodeWithText("Untitled").assertIsDisplayed()
        rule.onNodeWithText("Draft · kept on this phone").assertIsDisplayed()
        rule.onNodeWithText("Start writing…").assertIsDisplayed()
        rule.onNodeWithText("Code").assertIsSelected()
        rule.onNodeWithText("Split").assertIsNotSelected()
        rule.onNodeWithText("Preview").assertIsNotSelected()
    }
}
