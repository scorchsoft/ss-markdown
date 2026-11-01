package com.scorchsoft.ssmarkdown.fixtures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import com.scorchsoft.ssmarkdown.editor.EditorScreen
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.editor.sheets.ThemeControlsPlaceholder
import com.scorchsoft.ssmarkdown.settings.Appearance

/**
 * The editor screen driven by a real [EditorViewModel][com.scorchsoft.ssmarkdown.editor.EditorViewModel]
 * over real files ([EditorFixture]): every tap goes through the same
 * `onAction` the app wires, and the screen redraws from the view model's
 * state. Only the panes are stand-ins (no MarkdownCodeView, no WebView).
 */
@Composable
fun LiveEditor(
    fixture: EditorFixture,
    themeControls: @Composable () -> Unit = { ThemeControlsPlaceholder() },
) {
    val state by fixture.vm.uiState.collectAsState()
    EditorScreen(
        state = state,
        onAction = fixture.vm::onAction,
        codePane = { FakeCodePane(it) },
        previewPane = { PreviewPlaceholder(it) },
        previewState = Samples.previewState,
        appearance = Appearance.System,
        themeControls = themeControls,
    )
}

/** A control a finger can tap whose TalkBack name or text is exactly [label]. */
fun tappable(label: String): SemanticsMatcher =
    hasClickAction() and
        SemanticsMatcher("named \"$label\"") { node ->
            val names =
                node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty() +
                    node.config
                        .getOrElseNullable(SemanticsProperties.Text) { null }
                        .orEmpty()
                        .map { it.text }
            label in names
        }

/** Counts taps, so a test can assert how many a job takes. */
class TapCounter(
    private val rule: ComposeContentTestRule,
) {
    /** Taps so far. */
    var taps: Int = 0
        private set

    /** Taps the first control named [label] (several may match: a segment row repeats a label). */
    fun tap(label: String) {
        rule.onAllNodes(tappable(label))[0].performClick()
        rule.waitForIdle()
        taps++
    }
}
