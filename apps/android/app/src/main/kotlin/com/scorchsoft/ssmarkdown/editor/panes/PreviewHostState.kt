package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.themes.PreviewLook

/**
 * Everything the Preview pane needs from the editor, for the
 * `previewPane: @Composable (PreviewHostState) -> Unit` slot that
 * `feature/preview` fills in the next integration step.
 *
 * The pane must be stateless with respect to text (robustness §5a, rule
 * 1): it renders [document] and maps its own edits back through
 * [onEdit]. The editor hosts it with `movableContentOf`, so the same
 * WebView survives mode switches and rotation.
 *
 * @property document the live buffer; null while nothing is open.
 * @property version the buffer's edit version: re-render the visible section when it changes.
 * @property anchorLine the source line the pane should keep at its top (position continuity). A
 *   function, read when the pane lands, not a value: passing the value made the screen's root
 *   observe the anchor and recompose on every frame of a fling (PERF-13).
 * @property zoomPercent this file's Preview zoom; 100 % is the theme's base size.
 * @property tapToEdit "Tap to edit in Preview"; off makes Preview read-only (links still show the chip).
 * @property onAnchorLineChanged the pane scrolled: the new top source line.
 * @property onZoomChanged a pinch: [Int] percent, and whether fingers are still down.
 * @property onEdit a Preview edit as a minimal source change: `(start, end, replacement)`.
 * @property onHeadingLongPress long-press on a heading: its source line (the section menu, J10).
 * @property look the preview theme as the page draws it; null until themes have loaded.
 * @property pageMode Display → Preview wrapping: the A4 page instead of Fluid.
 * @property noWrap Display → Preview wrapping: No wrap, code lines and tables scroll sideways.
 * @property readOnly the file refuses edits, so the page must too.
 * @property renderer running, restarted, or paused with Retry.
 * @property onTouched a finger went down on the pane: in Split, it now leads the scrolling.
 * @property onRetry "Preview paused · Retry".
 * @property onShowCode "Preview paused · Use Code".
 */
@Suppress("LongParameterList") // A state holder: every field is something the Preview needs.
@Stable
class PreviewHostState(
    val document: TextDocument?,
    val version: Long,
    val anchorLine: () -> Int,
    val zoomPercent: Int,
    val tapToEdit: Boolean,
    val onAnchorLineChanged: (Int) -> Unit,
    val onZoomChanged: (Int, Boolean) -> Unit,
    val onEdit: (Int, Int, String) -> Unit,
    val onHeadingLongPress: (Int) -> Unit,
    val look: PreviewLook? = null,
    val pageMode: Boolean = false,
    val noWrap: Boolean = false,
    val readOnly: Boolean = false,
    val renderer: RendererState = RendererState.RUNNING,
    val onTouched: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onShowCode: () -> Unit = {},
)

/**
 * A stand-in Preview pane, for tests and screenshots that run without a
 * WebView. It says so plainly rather than pretending to render.
 */
@Composable
fun PreviewPlaceholder(
    @Suppress("UNUSED_PARAMETER") state: PreviewHostState,
) {
    Box(Modifier.fillMaxSize().background(SS.colors.bg).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.preview_placeholder),
            style = SS.type.body,
            color = SS.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}
