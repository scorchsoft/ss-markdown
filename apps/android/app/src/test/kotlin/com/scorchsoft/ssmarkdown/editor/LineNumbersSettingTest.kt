package com.scorchsoft.ssmarkdown.editor

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.scorchsoft.ssmarkdown.editor.panes.showsLineNumbers
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.nav.codePaneFor
import com.scorchsoft.ssmarkdown.settings.SettingsAction
import com.scorchsoft.ssmarkdown.settings.SettingsReducer
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings → Show line numbers reaches the open document's Code view
 * through the route's own pane ([codePaneFor]): at once, on the same view
 * (no reopen), in Code and in Split's Code pane alike.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LineNumbersSettingTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the Code view follows the setting at once, on the same view, Split included`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("notes.md", (1..200).joinToString("\n") { "line $it" }))
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Code))
        val pane = codePaneFor(f.vm, f.settings) { it.run() }
        rule.setContent { Themed(false) { pane(Modifier) } }
        rule.waitForIdle()
        val view = rule.runOnIdle { checkNotNull(codeView()) { "the Code view never attached" } }
        assertTrue("on by default", view.showLineNumbers)
        val withNumbers = view.textLeft

        f.settings.update { SettingsReducer.reduce(it, SettingsAction.SetLineNumbers(false)) }
        rule.waitForIdle()
        assertSame("the same view, not a reopen", view, rule.runOnIdle { codeView() })
        assertFalse(view.showLineNumbers)
        assertTrue("the text column widens", view.textLeft < withNumbers)

        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        rule.waitForIdle()
        assertFalse("Split's Code pane follows the setting too", view.showLineNumbers)

        f.vm.onAction(EditorAction.SelectMode(ViewMode.Code))
        f.settings.update { SettingsReducer.reduce(it, SettingsAction.SetLineNumbers(true)) }
        rule.waitForIdle()
        assertTrue(view.showLineNumbers)
    }

    private fun codeView(): MarkdownCodeView? = find(rule.activity.window.decorView)

    private fun find(view: View): MarkdownCodeView? =
        when (view) {
            is MarkdownCodeView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }

    @Test
    fun `the setting applies in Code and Split alike`() {
        assertFalse(showsLineNumbers(setting = false, mode = ViewMode.Code))
        assertFalse(showsLineNumbers(setting = false, mode = ViewMode.Split))
        assertTrue(showsLineNumbers(setting = true, mode = ViewMode.Code))
        assertTrue(showsLineNumbers(setting = true, mode = ViewMode.Split))
    }
}
