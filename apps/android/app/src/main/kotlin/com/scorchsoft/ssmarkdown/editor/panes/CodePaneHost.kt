package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.editor.EditorListener
import com.scorchsoft.ssmarkdown.editor.EditorTheme
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.compose.MarkdownEditor
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.ui.ChromeFonts
import java.util.concurrent.Executor

/**
 * The real Code pane: `feature/editor`'s [MarkdownEditor] bound to the
 * document on screen. It is the content the screen hosts with
 * `movableContentOf`, so this one view instance travels between Code,
 * Split and the side-by-side layout (robustness §5a, rule 2).
 *
 * [onAttach] / [onDetach] tell the session which view programmatic edits
 * must go through; a new [document] (a reload, a resolved conflict, a
 * different file) re-attaches the same view. [lineNumbers] is whether
 * the gutter shows ([showsLineNumbers]); it changes in place.
 */
@Composable
fun CodePaneHost(
    document: ActiveDocument?,
    wrap: Boolean,
    zoomPercent: Int,
    onAttach: (MarkdownCodeView) -> Unit,
    onDetach: (MarkdownCodeView) -> Unit,
    /** Where large pastes are prepared: AppGraph's paste thread. */
    background: Executor,
    modifier: Modifier = Modifier,
    lineNumbers: Boolean = true,
) {
    val doc = document ?: return
    val holder = remember { ViewHolder() }
    CodePane(
        text = doc.text,
        wrap = wrap,
        zoomPercent = zoomPercent,
        lineNumbers = lineNumbers,
        listener = doc,
        background = background,
        modifier = modifier,
        onViewReady = { view ->
            holder.view = view
            onAttach(view)
        },
    )
    LaunchedEffect(doc) { holder.view?.let(onAttach) }
    DisposableEffect(Unit) {
        onDispose { holder.view?.let(onDetach) }
    }
}

/**
 * The Code pane's look in the app: [MarkdownEditor] over [text] with the
 * core/ui colours ([EditorTheme.fromColors]) and the bundled code font
 * ([ChromeFonts.codeTypeface]). [CodePaneHost] binds it to the open
 * document; the screenshot harness draws it over a sample, so the
 * screenshots show the colouring the app really draws.
 */
@Composable
internal fun CodePane(
    text: TextDocument,
    wrap: Boolean,
    zoomPercent: Int,
    modifier: Modifier = Modifier,
    lineNumbers: Boolean = true,
    listener: EditorListener? = null,
    background: Executor? = null,
    onViewReady: (MarkdownCodeView) -> Unit = {},
) {
    val colors = SS.colors
    val theme = remember(colors) { EditorTheme.fromColors(colors) }
    val assets = LocalContext.current.assets
    val typeface = remember { ChromeFonts.codeTypeface(assets) }
    MarkdownEditor(
        document = text,
        modifier = modifier,
        theme = theme,
        wrap = wrap,
        zoomPercent = zoomPercent,
        typeface = typeface,
        lineNumbers = lineNumbers,
        listener = listener,
        background = background,
        onViewReady = onViewReady,
    )
}

/**
 * Whether the Code view draws its line numbers: Settings → Show line
 * numbers ([setting]) applies to the Code view; Split's Code pane always
 * keeps them (ux-spec "Settings").
 */
internal fun showsLineNumbers(
    setting: Boolean,
    mode: ViewMode,
): Boolean = setting || mode == ViewMode.Split

private class ViewHolder {
    var view: MarkdownCodeView? = null
}
