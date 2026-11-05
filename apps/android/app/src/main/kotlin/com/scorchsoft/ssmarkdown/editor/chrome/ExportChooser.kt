package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.ExportFormat
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.ui.AppIcons

/** ⋮ → Export…: PDF, HTML, plain text or images, each with a line saying what it makes (Andrew, 2026-11-04). */
@Composable
fun ExportChooser(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    DropdownMenu(
        expanded = state.overlay == EditorOverlay.ExportChooser,
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
    ) {
        ExportFormat.entries.forEach { ExportRow(it, onAction) }
        ImagesRow(onAction)
    }
}

/** Diagrams and pictures as PNG, ZIP or separate files: the Export images sheet. */
@Composable
private fun ImagesRow(onAction: (EditorAction) -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(R.string.export_images), style = SS.type.body, color = SS.colors.ink)
                Text(stringResource(R.string.export_images_hint), style = SS.type.meta, color = SS.colors.inkMuted)
            }
        },
        leadingIcon = { Icon(AppIcons.preview, contentDescription = null, tint = SS.colors.ink) },
        onClick = { onAction(EditorAction.ChooseMenuItem(MenuItem.ExportImages)) },
        modifier = Modifier.heightIn(min = 56.dp).testTag("export-images"),
    )
}

@Composable
private fun ExportRow(
    format: ExportFormat,
    onAction: (EditorAction) -> Unit,
) {
    val (title, hint, icon) =
        when (format) {
            ExportFormat.Pdf -> Triple(R.string.export_pdf, R.string.export_pdf_hint, AppIcons.file)
            ExportFormat.Html -> Triple(R.string.export_html, R.string.export_html_hint, AppIcons.code)
            ExportFormat.Text -> Triple(R.string.export_text, R.string.export_text_hint, AppIcons.menu)
        }
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(title), style = SS.type.body, color = SS.colors.ink)
                Text(stringResource(hint), style = SS.type.meta, color = SS.colors.inkMuted)
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null, tint = SS.colors.ink) },
        onClick = { onAction(EditorAction.ExportAs(format)) },
        modifier = Modifier.heightIn(min = 56.dp).testTag("export-${format.name.lowercase()}"),
    )
}
