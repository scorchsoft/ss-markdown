package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.core.ui.SS

/**
 * Table mode's pinned keys (docs/ux-spec.md, "Tables"): ⋯ opens the row
 * and column actions, ‹ Prev cell and Next cell › move between cells.
 * The menu's open state is the only state here, and it is pure UI: what
 * the actions do is the editor's business.
 */
@Composable
internal fun TableKeys(
    keys: List<StripKey>,
    onKey: (StripKey) -> Unit,
    onTableAction: (TableAction) -> Unit,
) {
    for (key in keys) {
        if (key == StripKey.TableMenu) {
            TableMenuKey(onTableAction)
        } else {
            StripKeyButton(key, pressed = false, enabled = true) { onKey(key) }
        }
    }
}

/** ⋯ and its menu, grouped as insert, move, delete with a rule between groups. */
@Composable
private fun TableMenuKey(onTableAction: (TableAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StripKeyButton(StripKey.TableMenu, pressed = false, enabled = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SS.colors.chrome) {
            TableAction.entries.forEachIndexed { i, action ->
                if (i > 0 && group(action) != group(TableAction.entries[i - 1])) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(action.label), style = SS.type.body, color = SS.colors.ink) },
                    onClick = {
                        open = false
                        onTableAction(action)
                    },
                )
            }
        }
    }
}

private fun group(action: TableAction): Int =
    when (action) {
        TableAction.AddRowBelow, TableAction.AddRowAbove, TableAction.AddColumnRight, TableAction.AddColumnLeft -> 0
        TableAction.MoveRowUp, TableAction.MoveRowDown, TableAction.MoveColumnLeft, TableAction.MoveColumnRight -> 1
        TableAction.DeleteRow, TableAction.DeleteColumn, TableAction.DeleteTable -> 2
    }
