package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.editor.EditorTheme
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.compose.MarkdownEditor
import com.scorchsoft.ssmarkdown.ui.ChromeFonts

/**
 * The real Code pane: `feature/editor`'s [MarkdownEditor] bound to the
 * document on screen. It is the content the screen hosts with
 * `movableContentOf`, so this one view instance travels between Code,
 * Split and the side-by-side layout (robustness §5a, rule 2).
 *
 * [onAttach] / [onDetach] tell the session which view programmatic edits
 * must go through; a new [document] (a reload, a resolved conflict, a
 * different file) re-attaches the same view.
 */
@Composable
fun CodePaneHost(
    document: ActiveDocument?,
    wrap: Boolean,
    zoomPercent: Int,
    onAttach: (MarkdownCodeView) -> Unit,
    onDetach: (MarkdownCodeView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = document ?: return
    val holder = remember { ViewHolder() }
    val colors = SS.colors
    val theme = remember(colors) { EditorTheme.fromColors(colors) }
    val assets = LocalContext.current.assets
    val typeface = remember { ChromeFonts.codeTypeface(assets) }
    MarkdownEditor(
        document = doc.text,
        modifier = modifier,
        theme = theme,
        wrap = wrap,
        zoomPercent = zoomPercent,
        typeface = typeface,
        listener = doc,
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

private class ViewHolder {
    var view: MarkdownCodeView? = null
}
