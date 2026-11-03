package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.LinkPrompt

/**
 * The strip's Link prompt (docs/ux-spec.md, "Formatting strip"): the
 * link's text and address, prefilled from the selection, an existing link
 * or a URL on the clipboard. Done (or the keyboard's Done on the address)
 * writes `[text](address)`; Cancel changes nothing; inside a link, Remove
 * link keeps its text. The fields keep what was typed across rotation.
 */
@Composable
fun LinkPromptSheet(
    prompt: EditorOverlay.LinkPrompt,
    onAction: (EditorAction) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // Read once, when the prompt opens, and only if there is no address yet.
    val opening =
        remember(prompt) {
            if (prompt.fields.url.isBlank()) {
                LinkPrompt.withClipboard(prompt.fields, runCatching { clipboard.getText()?.text }.getOrNull())
            } else {
                prompt.fields
            }
        }
    var text by rememberSaveable(prompt) { mutableStateOf(opening.text) }
    var url by rememberSaveable(prompt) { mutableStateOf(opening.url) }
    val done = { if (url.isNotBlank()) onAction(EditorAction.LinkDone(text, url.trim())) }
    val focus = remember { FocusRequester() }
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        SheetTitle(stringResource(if (prompt.fields.editing) R.string.link_edit else R.string.link_prompt_title))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.link_prompt_text)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.link_prompt_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
                    .focusRequester(focus),
        )
        SheetButton(stringResource(R.string.link_prompt_done), Emphasis.Ink, done, enabled = url.isNotBlank())
        if (prompt.fields.editing) {
            SheetButton(stringResource(R.string.link_remove), Emphasis.Outline, { onAction(EditorAction.LinkRemove) })
        }
        SheetButton(stringResource(R.string.cancel), Emphasis.Quiet, { onAction(EditorAction.DismissOverlay) })
    }
    // The address is what is usually missing: the keyboard opens on it.
    LaunchedEffect(prompt) { runCatching { focus.requestFocus() } }
}
