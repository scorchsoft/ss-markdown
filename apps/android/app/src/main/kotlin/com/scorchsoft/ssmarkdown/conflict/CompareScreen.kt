package com.scorchsoft.ssmarkdown.conflict

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

/** The + / − column. */
private val SignColumn = 16.dp

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
    val style = rowStyle(row.type)
    // Git-style: a + or − before the line and a tint behind the row; the tag and strike-through stay,
    // so the meaning never rests on colour (Andrew, 2026-11-04).
    val rowModifier = if (style.tint == Color.Unspecified) Modifier else Modifier.background(style.tint)
    Row(
        rowModifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Number(row.theirsLine, style.numbers)
        Number(row.mineLine, style.numbers)
        Text(
            style.sign,
            style = SS.type.code.copy(fontWeight = FontWeight.Bold),
            color = style.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(SignColumn),
        )
        Text(
            stringResource(style.tag),
            style = SS.type.caption,
            color = style.numbers,
            modifier = Modifier.width(36.dp),
        )
        Text(row.text.trimEnd('\n'), style = SS.type.code.copy(textDecoration = style.decoration), color = style.ink)
    }
}

/** How a Compare row is drawn: its sign, tag, colours and decoration. */
private data class RowStyle(
    val sign: String,
    val tag: Int,
    val ink: Color,
    val numbers: Color,
    val tint: Color,
    val decoration: TextDecoration?,
)

@Composable
private fun rowStyle(type: DiffLineType): RowStyle {
    val c = SS.colors
    return when (type) {
        DiffLineType.Removed -> {
            RowStyle(
                "−",
                R.string.compare_file_tag,
                c.diffRemoved,
                c.inkMuted,
                c.diffRemovedBg,
                TextDecoration.LineThrough,
            )
        }

        DiffLineType.Added -> {
            RowStyle("+", R.string.compare_mine_tag, c.diffAdded, c.inkMuted, c.diffAddedBg, null)
        }

        DiffLineType.Context -> {
            RowStyle(" ", R.string.compare_no_tag, c.ink, c.gutter, Color.Unspecified, null)
        }
    }
}

@Composable
private fun Number(
    n: Int?,
    color: Color = SS.colors.gutter,
) {
    Text(
        n?.toString().orEmpty(),
        style = SS.type.caption,
        color = color,
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
