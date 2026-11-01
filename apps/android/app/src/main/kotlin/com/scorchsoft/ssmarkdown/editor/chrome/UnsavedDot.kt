package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS

private val DotSize = 8.dp

/**
 * The 8 dp red unsaved dot, for the header, the folder drawer and the
 * Navigator (UX-6). A drawn dot, not a "●" character, so TalkBack says
 * "Unsaved" rather than "black circle"; [labelled] is false where the
 * text beside it already says so.
 */
@Composable
fun UnsavedDot(
    modifier: Modifier = Modifier,
    labelled: Boolean = true,
) {
    val label = stringResource(R.string.unsaved_dot)
    val semantics =
        if (labelled) {
            Modifier.semantics {
                contentDescription = label
            }
        } else {
            Modifier.clearAndSetSemantics {}
        }
    Box(modifier.size(DotSize).background(SS.colors.needsYou, CircleShape).then(semantics))
}
