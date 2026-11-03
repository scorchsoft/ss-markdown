package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorScreen
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.panes.CodePane
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.fixtures.FakeCodePane
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.settings.SplitTyping
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.ui.strip.StripRows

/** [content] in the app theme (with the app's bundled fonts), at [fontScale], with a fixed clock. */
@Composable
fun Themed(
    dark: Boolean,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val assets = LocalContext.current.assets
    val fonts = remember { ChromeFonts.families(assets) }
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalNow provides 120_000L) {
        SSMarkdownTheme(darkTheme = dark, fonts = fonts, content = content)
    }
}

/** The editor screen in [state], with the stand-in Code pane and the Preview placeholder. */
@Composable
fun EditorFor(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit = {},
) = EditorWith(state, codePane = { FakeCodePane(it) }, onAction = onAction)

/**
 * The editor screen in [state] with [codePane] as the Code pane (the screenshots pass [RealCodePane]),
 * [stripRows] as Settings → Formatting bar and [splitTyping] as Settings → When typing in Split.
 */
@Composable
fun EditorWith(
    state: EditorUiState,
    codePane: @Composable (Modifier) -> Unit,
    onAction: (EditorAction) -> Unit = {},
    stripRows: StripRows = StripRows.One,
    splitTyping: SplitTyping = SplitTyping.EditedPane,
) {
    EditorScreen(
        state = state,
        onAction = onAction,
        codePane = codePane,
        previewPane = { PreviewPlaceholder(it) },
        previewState = Samples.previewState,
        appearance = Appearance.System,
        stripRows = stripRows,
        splitTyping = splitTyping,
    )
}

/**
 * The app's real Code pane ([CodePane]: the view, the core/ui colours and
 * the bundled code font) over [Samples.codeSample], at [state]'s wrap and
 * zoom, with or without [lineNumbers]. The screenshots use it, so they show the colouring the app draws;
 * the stand-in pane was plain `Text` and showed none.
 */
@Composable
fun RealCodePane(
    state: EditorUiState,
    modifier: Modifier = Modifier,
    lineNumbers: Boolean = true,
) {
    val text = remember { TextDocument(PieceTree.of(Samples.codeSample)) }
    CodePane(text, wrap = state.codeWrap, zoomPercent = state.codeZoom, modifier = modifier, lineNumbers = lineNumbers)
}
