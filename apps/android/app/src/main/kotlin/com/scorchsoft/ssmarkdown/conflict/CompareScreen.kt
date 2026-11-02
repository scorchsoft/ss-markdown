package com.scorchsoft.ssmarkdown.conflict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.editor.sheets.Emphasis
import com.scorchsoft.ssmarkdown.editor.sheets.SheetButton
import com.scorchsoft.ssmarkdown.history.versions.DiffLineType
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen

private val NumberColumn = 32.dp

/** What the Compare screen can do. */
sealed interface CompareAction {
    data class SetView(
        val view: CompareView,
    ) : CompareAction

    data object KeepTheirs : CompareAction

    data object Merge : CompareAction

    data object Back : CompareAction
}

/**
 * ComparePhone: one scrolling diff with a Changes / File's version / My
 * version toggle. From the conflict sheet it ends with Keep the file's
 * version · Merge my edits.
 */
@Composable
fun CompareScreen(
    state: CompareUiState,
    onAction: (CompareAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(stringResource(R.string.compare_title, state.fileName), {
        onAction(CompareAction.Back)
    }, modifier, scroll = false) {
        Text(
            pluralStringResource(R.plurals.compare_differences, state.differences, state.differences),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        SSSegmentedControl(
            options =
                listOf(
                    stringResource(R.string.compare_changes),
                    stringResource(R.string.compare_theirs),
                    stringResource(R.string.compare_mine),
                ),
            selectedIndex = state.view.ordinal,
            onSelect = { onAction(CompareAction.SetView(CompareView.entries[it])) },
            modifier = Modifier.padding(12.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (state.view) {
                CompareView.Changes -> itemsIndexed(state.rows) { _, row -> ChangeRow(row) }
                CompareView.Theirs -> itemsIndexed(state.theirs.lines()) { i, line -> PlainLine(i + 1, line) }
                CompareView.Mine -> itemsIndexed(state.mine.lines()) { i, line -> PlainLine(i + 1, line) }
            }
        }
        NoteText(stringResource(R.string.compare_note))
        if (state.origin == CompareOrigin.Conflict) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SheetButton(
                    stringResource(R.string.compare_keep_theirs),
                    Emphasis.Outline,
                    { onAction(CompareAction.KeepTheirs) },
                )
                SheetButton(stringResource(R.string.compare_merge), Emphasis.Ink, { onAction(CompareAction.Merge) })
            }
        }
    }
}

@Composable
private fun ChangeRow(row: CompareRow) {
    if (row.collapsed > 0) {
        Text(
            pluralStringResource(R.plurals.compare_unchanged, row.collapsed, row.collapsed),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    val (tag, color, decoration) =
        when (row.type) {
            DiffLineType.Removed -> {
                Triple(
                    stringResource(R.string.compare_file_tag),
                    SS.colors.inkMuted,
                    TextDecoration.LineThrough,
                )
            }

            DiffLineType.Added -> {
                Triple(stringResource(R.string.compare_mine_tag), SS.colors.info, null)
            }

            DiffLineType.Context -> {
                Triple("", SS.colors.ink, null)
            }
        }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Number(row.theirsLine)
        Number(row.mineLine)
        Text(tag, style = SS.type.caption, color = SS.colors.inkMuted, modifier = Modifier.width(36.dp))
        Text(row.text.trimEnd('\n'), style = SS.type.code.copy(textDecoration = decoration), color = color)
    }
}

@Composable
private fun Number(n: Int?) {
    Text(
        n?.toString().orEmpty(),
        style = SS.type.caption,
        color = SS.colors.gutter,
        textAlign = TextAlign.End,
        modifier = Modifier.width(NumberColumn).padding(end = 4.dp),
    )
}

@Composable
private fun PlainLine(
    n: Int,
    line: String,
) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Number(n)
        Text(line, style = SS.type.code, color = SS.colors.ink)
    }
}
