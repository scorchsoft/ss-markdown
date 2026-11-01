package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorScreen
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.fixtures.FakeCodePane
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.settings.Appearance

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
) {
    EditorScreen(
        state = state,
        onAction = onAction,
        codePane = { FakeCodePane(it) },
        previewPane = { PreviewPlaceholder(it) },
        previewState = Samples.previewState,
        appearance = Appearance.System,
    )
}
