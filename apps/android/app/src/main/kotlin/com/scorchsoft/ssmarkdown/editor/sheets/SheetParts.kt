package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val ButtonShape = RoundedCornerShape(12.dp)

/** A bottom sheet in the app's chrome colour, its content scrolling when the font is large. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SSSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SS.colors.chrome,
        // Material's handle is a 32 dp pill whose expand and dismiss actions sit
        // on the pill alone: a 32 dp wide target. The box gives it 48 dp
        // without changing what is drawn.
        dragHandle = {
            Box(Modifier.widthIn(min = SS.dimens.iconButton), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/** The sheet's title (18 sp). */
@Composable
fun SheetTitle(text: String) {
    Text(text, style = SS.type.title, color = SS.colors.ink, modifier = Modifier.padding(bottom = 8.dp))
}

/** Body copy in a sheet. */
@Composable
fun SheetBody(
    text: String,
    muted: Boolean = false,
) {
    Text(
        text,
        style = SS.type.body,
        color = if (muted) SS.colors.inkMuted else SS.colors.ink,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** How strongly a sheet button speaks: red only when it protects work ("needs you"). */
enum class Emphasis { NeedsYou, Ink, Outline, Quiet }

/**
 * A full-width sheet button, 48 dp tall at least, with an optional
 * second line ([hint]) for the conflict sheet's explanations.
 */
@Composable
fun SheetButton(
    label: String,
    emphasis: Emphasis,
    onClick: () -> Unit,
    hint: String? = null,
) {
    val colors = SS.colors
    val (bg, fg) =
        when (emphasis) {
            Emphasis.NeedsYou -> colors.needsYou to colors.onNeedsYou
            Emphasis.Ink -> colors.ink to colors.bg
            Emphasis.Outline, Emphasis.Quiet -> colors.chrome to colors.ink
        }
    val border =
        if (emphasis ==
            Emphasis.Outline
        ) {
            Modifier.border(SS.dimens.hairline, colors.border, ButtonShape)
        } else {
            Modifier
        }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth()
                .heightIn(min = SS.dimens.rowPrimary)
                .background(bg, ButtonShape)
                .then(border)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column {
            Text(label, style = SS.type.bodyStrong, color = fg)
            hint?.let {
                Text(
                    it,
                    style = SS.type.meta,
                    color =
                        if (emphasis == Emphasis.Quiet ||
                            emphasis == Emphasis.Outline
                        ) {
                            colors.inkMuted
                        } else {
                            fg
                        },
                )
            }
        }
    }
}
