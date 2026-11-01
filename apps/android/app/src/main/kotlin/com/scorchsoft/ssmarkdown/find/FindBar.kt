package com.scorchsoft.ssmarkdown.find

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.semanticsLabel

/**
 * The find bar, which slides in **under** the header so the title, the
 * unsaved dot and Save stay visible (ux-spec "Find & replace"). Row 1:
 * query, "3 of 12", previous / next, ⇄ Replace, ✕. Row 2 only once
 * Replace is opened. Options: Match case · Whole word · `.*`.
 */
@Composable
fun FindBar(
    state: FindUiState,
    onAction: (FindAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier.fillMaxWidth().background(SS.colors.chrome).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(FindAction.Query(it)) },
                placeholder = { Text(stringResource(R.string.find_hint), style = SS.type.body) },
                singleLine = true,
                textStyle = SS.type.body,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAction(FindAction.Next) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Text(
                countText(state),
                style = SS.type.meta,
                color = if (state.invalidPattern) SS.colors.needsYou else SS.colors.inkMuted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = {
                onAction(FindAction.Previous)
            }) { Icon(AppIcons.chevronUp, stringResource(R.string.find_previous), tint = SS.colors.ink) }
            IconButton(onClick = {
                onAction(FindAction.Next)
            }) { Icon(AppIcons.chevronDown, stringResource(R.string.find_next), tint = SS.colors.ink) }
            IconButton(onClick = { onAction(FindAction.ToggleReplace) }) {
                Icon(
                    AppIcons.swap,
                    stringResource(R.string.find_replace_toggle),
                    tint = if (state.replaceOpen) SS.colors.ink else SS.colors.inkMuted,
                )
            }
            IconButton(onClick = {
                onAction(FindAction.Close)
            }) { Icon(AppIcons.close, stringResource(R.string.find_close), tint = SS.colors.ink) }
        }
        if (state.replaceOpen) ReplaceRow(state, onAction)
        Options(state.options, onAction)
    }
    HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
}

@Composable
private fun ReplaceRow(
    state: FindUiState,
    onAction: (FindAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = state.replacement,
            onValueChange = { onAction(FindAction.Replacement(it)) },
            placeholder = { Text(stringResource(R.string.find_replace_hint), style = SS.type.body) },
            singleLine = true,
            textStyle = SS.type.body,
            colors = fieldColors(),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onAction(FindAction.ReplaceOne) }, enabled = state.currentMatch != null) {
            Text(stringResource(R.string.find_replace_one), style = SS.type.label, color = SS.colors.ink)
        }
        TextButton(onClick = { onAction(FindAction.ReplaceAll) }, enabled = state.matches.isNotEmpty()) {
            Text(stringResource(R.string.find_replace_all), style = SS.type.label, color = SS.colors.ink)
        }
    }
}

@Composable
private fun Options(
    options: FindOptions,
    onAction: (FindAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionChip(stringResource(R.string.find_match_case), options.matchCase) { onAction(FindAction.ToggleMatchCase) }
        OptionChip(stringResource(R.string.find_whole_word), options.wholeWord) { onAction(FindAction.ToggleWholeWord) }
        OptionChip(stringResource(R.string.find_regex), options.regex, ".*") { onAction(FindAction.ToggleRegex) }
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    shown: String = label,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(shown, style = SS.type.label) },
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = SS.colors.ink,
                selectedLabelColor = SS.colors.bg,
                labelColor = SS.colors.ink,
            ),
        modifier = Modifier.semanticsLabel(label),
    )
}

@Composable
private fun countText(state: FindUiState): String =
    when {
        state.invalidPattern -> stringResource(R.string.find_invalid)
        state.query.isEmpty() -> ""
        state.matches.isEmpty() -> stringResource(R.string.find_none)
        state.capped -> stringResource(R.string.find_count_capped, state.current + 1, state.matches.size)
        else -> stringResource(R.string.find_count, state.current + 1, state.matches.size)
    }

@Composable
private fun fieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = SS.colors.ink,
        unfocusedBorderColor = SS.colors.border,
        cursorColor = SS.colors.ink,
        focusedTextColor = SS.colors.ink,
        unfocusedTextColor = SS.colors.ink,
    )
