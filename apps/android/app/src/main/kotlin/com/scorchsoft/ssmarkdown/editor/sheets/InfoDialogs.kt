package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.SaveProblem
import com.scorchsoft.ssmarkdown.editor.chrome.problemText

/** Tapping the Big-file chip: what is paused, and why. */
@Composable
fun BigFileInfoDialog(onDismiss: () -> Unit) =
    InfoDialog(stringResource(R.string.big_file_title), stringResource(R.string.big_file_body), onDismiss)

/** "Details" on a failed save: the reason, and that the file is untouched. */
@Composable
fun SaveFailureDialog(
    problem: SaveProblem,
    onDismiss: () -> Unit,
) = InfoDialog(
    stringResource(R.string.save_failed_title),
    problemText(problem).replaceFirstChar { it.uppercase() } + ". " + stringResource(R.string.save_failed_untouched),
    onDismiss,
)

@Composable
private fun InfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SS.colors.chrome,
        title = { Text(title, style = SS.type.title, color = SS.colors.ink) },
        text = { Text(body, style = SS.type.body, color = SS.colors.ink) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) { Text(stringResource(R.string.ok), style = SS.type.label, color = SS.colors.ink) }
        },
    )
}
