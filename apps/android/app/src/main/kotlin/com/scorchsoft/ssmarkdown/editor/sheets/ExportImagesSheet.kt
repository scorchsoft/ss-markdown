package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.media.ExportImagesAction
import com.scorchsoft.ssmarkdown.media.ExportImagesState
import com.scorchsoft.ssmarkdown.media.MediaType
import com.scorchsoft.ssmarkdown.settings.DiagramFormat
import com.scorchsoft.ssmarkdown.settings.ExportDestination

private val TileSize = 88.dp
private val TileShape = RoundedCornerShape(10.dp)

/**
 * ⋮ → Export images… (ux-spec "Export images"; mockup ExportImages): what
 * to include, diagrams as PNG / SVG / Both at 1×–3×, and where: separate
 * files in Downloads (the default, J6 in three taps) or one ZIP, or Share.
 */
@Composable
fun ExportImagesSheet(
    state: ExportImagesState,
    onAction: (EditorAction) -> Unit,
) {
    val act = { action: ExportImagesAction -> onAction(EditorAction.ExportImages(action)) }
    SSSheet(onDismiss = { act(ExportImagesAction.Dismiss) }) {
        SheetTitle(stringResource(R.string.export_title))
        SheetBody(
            pluralStringResource(
                R.plurals.export_found,
                state.items.size,
                state.items.size,
                state.diagrams,
                state.pictures,
            ),
            muted = true,
        )
        IncludeHeader(state, act)
        Thumbnails(state, act)
        Formats(state, act)
        SheetBody(stringResource(R.string.export_pictures_note), muted = true)
        Destination(state, act)
        if (state.running) {
            LinearProgressIndicator(
                color = SS.colors.ink,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        SheetButton(
            pluralStringResource(R.plurals.export_go, state.count, state.count),
            Emphasis.Ink,
            { act(ExportImagesAction.Export) },
        )
        SheetButton(stringResource(R.string.export_share), Emphasis.Quiet, { act(ExportImagesAction.Share) })
    }
}

@Composable
private fun IncludeHeader(
    state: ExportImagesState,
    act: (ExportImagesAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.export_include),
            style = SS.type.label,
            color = SS.colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { act(ExportImagesAction.ToggleAll) }) {
            Text(
                stringResource(
                    if (state.selected.isEmpty()) R.string.export_select_all else R.string.export_select_none,
                ),
                style = SS.type.label,
                color = SS.colors.ink,
            )
        }
    }
}

/** Diagrams as PNG / SVG / Both, and the PNG scale. */
@Composable
private fun Formats(
    state: ExportImagesState,
    act: (ExportImagesAction) -> Unit,
) {
    Text(
        stringResource(R.string.export_diagrams_as),
        style = SS.type.label,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
    SSSegmentedControl(
        options =
            listOf(
                stringResource(R.string.export_png),
                stringResource(R.string.export_svg),
                stringResource(R.string.export_both),
            ),
        selectedIndex = state.format.ordinal,
        onSelect = { act(ExportImagesAction.SetFormat(DiagramFormat.entries[it])) },
    )
    if (state.format != DiagramFormat.Svg) {
        SSSegmentedControl(
            options = (1..ExportImagesState.MAX_SCALE).map { stringResource(R.string.export_scale, it) },
            selectedIndex = state.scale - 1,
            onSelect = { act(ExportImagesAction.SetScale(it + 1)) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Thumbnails(
    state: ExportImagesState,
    act: (ExportImagesAction) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(state.items) { index, item ->
            val chosen = index in state.selected
            val kind =
                stringResource(
                    if (item.type ==
                        MediaType.Diagram
                    ) {
                        R.string.media_kind_diagram
                    } else {
                        R.string.media_kind_picture
                    },
                )
            Box(
                contentAlignment = Alignment.BottomStart,
                modifier =
                    Modifier
                        .size(TileSize)
                        .background(if (chosen) SS.colors.segmentTrack else SS.colors.bg, TileShape)
                        .border(
                            if (chosen) 2.dp else SS.dimens.hairline,
                            if (chosen) SS.colors.ink else SS.colors.border,
                            TileShape,
                        ).clickable(role = Role.Checkbox) { act(ExportImagesAction.Toggle(index)) }
                        .semantics {
                            selected = chosen
                            contentDescription = "$kind ${item.title}"
                        }.padding(8.dp),
            ) {
                Column {
                    Text(kind, style = SS.type.caption, color = SS.colors.inkMuted)
                    Text(
                        item.title,
                        style = SS.type.meta,
                        color = SS.colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Destination(
    state: ExportImagesState,
    act: (ExportImagesAction) -> Unit,
) {
    Text(
        stringResource(R.string.export_save_as),
        style = SS.type.label,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
    Choice(
        stringResource(R.string.export_separate),
        stringResource(R.string.export_separate_hint, state.docName),
        state.destination == ExportDestination.SeparateFiles,
    ) { act(ExportImagesAction.SetDestination(ExportDestination.SeparateFiles)) }
    Choice(
        stringResource(R.string.export_zip),
        stringResource(R.string.export_zip_hint, state.docName),
        state.destination == ExportDestination.Zip,
    ) { act(ExportImagesAction.SetDestination(ExportDestination.Zip)) }
}

@Composable
private fun Choice(
    title: String,
    hint: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .heightIn(min = SS.dimens.rowPrimary)
            .background(if (chosen) SS.colors.segmentTrack else SS.colors.chrome, TileShape)
            .border(
                if (chosen) 2.dp else SS.dimens.hairline,
                if (chosen) SS.colors.ink else SS.colors.border,
                TileShape,
            ).clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, style = SS.type.bodyStrong, color = SS.colors.ink)
        Text(hint, style = SS.type.meta, color = SS.colors.inkMuted)
    }
}
