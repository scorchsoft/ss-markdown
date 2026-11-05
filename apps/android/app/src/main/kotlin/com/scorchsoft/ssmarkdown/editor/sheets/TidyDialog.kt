package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.markdown.tidy.TidyRule

/**
 * "Tidy this file?" (Andrew, 2026-11-04): how Tidy works, what it never
 * touches, and what it would change in this file, then Tidy or Cancel. A
 * file that is already tidy says so, with only OK. The body scrolls, so a
 * large font scale never pushes the buttons off the screen.
 */
@Composable
internal fun TidyDialog(
    counts: Map<TidyRule, Int>,
    onAction: (EditorAction) -> Unit,
) {
    val changes = TidyRule.entries.mapNotNull { rule -> counts[rule]?.takeIf { it > 0 }?.let { rule to it } }
    AlertDialog(
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.tidy_title), style = SS.type.title, color = SS.colors.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tidy_how), style = SS.type.body, color = SS.colors.ink)
                Text(stringResource(R.string.tidy_never), style = SS.type.body, color = SS.colors.ink)
                Text(stringResource(R.string.tidy_settings), style = SS.type.body, color = SS.colors.inkMuted)
                if (changes.isEmpty()) {
                    Text(stringResource(R.string.tidy_nothing), style = SS.type.bodyStrong, color = SS.colors.ink)
                } else {
                    Text(stringResource(R.string.tidy_in_this_file), style = SS.type.bodyStrong, color = SS.colors.ink)
                    changes.forEach { (rule, n) ->
                        Text("• " + ruleText(rule, n), style = SS.type.body, color = SS.colors.ink)
                    }
                    Text(stringResource(R.string.tidy_undo), style = SS.type.body, color = SS.colors.inkMuted)
                }
            }
        },
        confirmButton = {
            if (changes.isEmpty()) {
                TextButton(onClick = { onAction(EditorAction.DismissOverlay) }) {
                    Text(stringResource(R.string.ok), style = SS.type.label, color = SS.colors.ink)
                }
            } else {
                TextButton(onClick = { onAction(EditorAction.TidyConfirmed) }) {
                    Text(stringResource(R.string.tidy_confirm), style = SS.type.label, color = SS.colors.ink)
                }
            }
        },
        dismissButton = {
            if (changes.isNotEmpty()) {
                TextButton(onClick = { onAction(EditorAction.DismissOverlay) }) {
                    Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink)
                }
            }
        },
    )
}

@Composable
private fun ruleText(
    rule: TidyRule,
    n: Int,
): String =
    when (rule) {
        TidyRule.ExtraBlankLines -> pluralStringResource(R.plurals.tidy_extra_blank_lines, n, n)
        TidyRule.TrailingSpaces -> pluralStringResource(R.plurals.tidy_trailing_spaces, n, n)
        TidyRule.BlankLineAroundHeading -> pluralStringResource(R.plurals.tidy_around_heading, n, n)
        TidyRule.BlankLineAroundFence -> pluralStringResource(R.plurals.tidy_around_fence, n, n)
        TidyRule.HeadingSpacing -> pluralStringResource(R.plurals.tidy_heading_spacing, n, n)
        TidyRule.ListNumbering -> pluralStringResource(R.plurals.tidy_list_numbering, n, n)
        TidyRule.FinalNewline -> stringResource(R.string.tidy_final_newline)
        TidyRule.UnclosedFence -> stringResource(R.string.tidy_unclosed_fence)
    }
