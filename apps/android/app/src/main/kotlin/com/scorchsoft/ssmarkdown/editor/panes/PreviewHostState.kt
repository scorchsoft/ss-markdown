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
import com.scorchsoft.ssmarkdown.text.TextDocument

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
 * @property anchorLine the source line the pane should keep at its top (position continuity).
 * @property zoomPercent this file's Preview zoom; 100 % is the theme's base size.
 * @property tapToEdit "Tap to edit in Preview"; off makes Preview read-only and links open on tap.
 * @property onAnchorLineChanged the pane scrolled: the new top source line.
 * @property onZoomChanged a pinch: [Int] percent, and whether fingers are still down.
 * @property onEdit a Preview edit as a minimal source change: `(start, end, replacement)`.
 * @property onHeadingLongPress long-press on a heading: its source line (the section menu, J10).
 */
@Suppress("LongParameterList") // A state holder: every field is something the Preview needs.
@Stable
class PreviewHostState(
    val document: TextDocument?,
    val version: Long,
    val anchorLine: Int,
    val zoomPercent: Int,
    val tapToEdit: Boolean,
    val onAnchorLineChanged: (Int) -> Unit,
    val onZoomChanged: (Int, Boolean) -> Unit,
    val onEdit: (Int, Int, String) -> Unit,
    val onHeadingLongPress: (Int) -> Unit,
)

/**
 * The temporary Preview pane until `feature/preview` is integrated. It
 * says so plainly rather than pretending to render.
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
