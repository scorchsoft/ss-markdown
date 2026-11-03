package com.scorchsoft.ssmarkdown.editor.compose

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.scorchsoft.ssmarkdown.editor.EditorListener
import com.scorchsoft.ssmarkdown.editor.EditorTheme
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.text.TextDocument
import java.util.concurrent.Executor

/**
 * The Code pane for Compose screens: a [MarkdownCodeView] inside an
 * [AndroidView], bound to [document].
 *
 * **Host it with `movableContentOf`** (docs/robustness.md §5a, rule 2).
 * Moving between Code, Split and the side-by-side layout must move this
 * same view instance, not rebuild it: a rebuilt view loses its keyboard
 * connection, composing word, selection and scroll. Create the movable
 * content once (`remember { movableContentOf { MarkdownEditor(...) } }`)
 * and call it from whichever layout is showing.
 *
 * The view owns no text. [document] belongs to the host's store; changing
 * it rebinds the view (and restarts the keyboard). [zoomPercent] and
 * [wrap] are applied only when they differ from the view's own values, so
 * a pinch's `onZoomChanged` feeding back into state does not fight the
 * gesture. [typeface] is the code font; null keeps the system monospace.
 * [lineNumbers] shows or hides the gutter, at once and in place
 * ([MarkdownCodeView.showLineNumbers]).
 * [onViewReady] hands the view to a host that needs its
 * imperative API ([MarkdownCodeView.scrollToLine],
 * [MarkdownCodeView.requestFlushComposing], [MarkdownCodeView.applyEdit]),
 * already bound to [document].
 */
@Composable
fun MarkdownEditor(
    document: TextDocument,
    modifier: Modifier = Modifier,
    theme: EditorTheme? = null,
    wrap: Boolean = false,
    zoomPercent: Int = 100,
    typeface: Typeface? = null,
    lineNumbers: Boolean = true,
    listener: EditorListener? = null,
    /** Where large pastes are prepared ([MarkdownCodeView.backgroundExecutor]); null keeps its inline default. */
    background: Executor? = null,
    onViewReady: (MarkdownCodeView) -> Unit = {},
) {
    val bound = remember { BoundDocument() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MarkdownCodeView(context).also { view ->
                if (background != null) view.backgroundExecutor = background
                // Bound before anyone gets the view: a host that restores a remembered
                // cursor in onViewReady must find this document, not the empty placeholder
                // (a cold start with a remembered cursor crashed; edge-cases, kill loop).
                bound.document = document
                view.setDocument(document)
                onViewReady(view)
            }
        },
        update = { view ->
            if (bound.document !== document) {
                bound.document = document
                view.setDocument(document)
            }
            if (theme != null && view.theme != theme) view.theme = theme
            if (view.wrap != wrap) view.wrap = wrap
            view.showLineNumbers = lineNumbers
            if (typeface != null && view.codeTypeface != typeface) view.codeTypeface = typeface
            if (view.zoomPercent != zoomPercent) view.zoomPercent = zoomPercent
            view.listener = listener
        },
    )
}

/** Which document the view was last bound to, so recomposition never rebinds the same one. */
private class BoundDocument {
    var document: TextDocument? = null
}
