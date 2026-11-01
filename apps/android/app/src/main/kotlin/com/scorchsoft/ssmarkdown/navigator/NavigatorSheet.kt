package com.scorchsoft.ssmarkdown.navigator

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.editor.chrome.UnsavedDot
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.RelativeTime
import com.scorchsoft.ssmarkdown.ui.relativeTimeText

private val IndentPerLevel = 14.dp

/**
 * The Navigator, opened **below the header** so the title, the unsaved
 * dot and Save stay visible (ux-spec "Navigator"). The search field
 * autofocuses; an empty query shows the Outline and Files tabs, a typed
 * one shows the quick switcher's groups.
 */
@Composable
fun NavigatorSheet(
    state: NavigatorUiState,
    onAction: (NavigatorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val pane = stringResource(R.string.nav_pane_title)
    // A pane title, so TalkBack announces the sheet as it opens (UX-13).
    Column(modifier.fillMaxWidth().background(SS.colors.chrome).semantics { paneTitle = pane }) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(NavigatorAction.Query(it)) },
            placeholder = { Text(stringResource(R.string.nav_search_hint), style = SS.type.body) },
            leadingIcon = { Icon(AppIcons.search, contentDescription = null, tint = SS.colors.inkMuted) },
            trailingIcon = {
                if (state.hardwareKeyboard) {
                    Text(
                        stringResource(R.string.nav_ctrl_k),
                        style = SS.type.caption,
                        color = SS.colors.inkMuted,
                    )
                }
            },
            singleLine = true,
            textStyle = SS.type.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onAction(NavigatorAction.OpenFirstResult) }),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SS.colors.ink,
                    unfocusedBorderColor = SS.colors.border,
                    cursorColor = SS.colors.ink,
                ),
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
        )
        if (!state.searching) {
            SSSegmentedControl(
                options =
                    listOf(
                        pluralStringResource(R.plurals.nav_outline_count, state.outline.size, state.outline.size),
                        stringResource(R.string.nav_files),
                    ),
                selectedIndex = state.tab.ordinal,
                onSelect = { onAction(NavigatorAction.SelectTab(NavigatorTab.entries[it])) },
                modifier = Modifier.padding(horizontal = 12.dp).align(Alignment.CenterHorizontally),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            when {
                state.searching -> results(state, onAction)
                state.tab == NavigatorTab.Outline -> outline(state, onAction)
                else -> files(state, onAction)
            }
        }
        HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
    }
}

private fun LazyListScope.outline(
    state: NavigatorUiState,
    onAction: (NavigatorAction) -> Unit,
) {
    item { PlainRow(stringResource(R.string.nav_top), icon = true) { onAction(NavigatorAction.TopOfDocument) } }
    if (state.outline.isEmpty()) item { Hint(stringResource(R.string.nav_no_headings)) }
    items(state.outline, key = { "h${it.line}" }) { row -> HeadingRow(row, onAction) }
    if (state.outline.isNotEmpty()) item { Hint(stringResource(R.string.nav_long_press_hint)) }
}

private fun LazyListScope.files(
    state: NavigatorUiState,
    onAction: (NavigatorAction) -> Unit,
) {
    if (state.drafts.isNotEmpty()) {
        item { GroupLabel(stringResource(R.string.nav_drafts)) }
        items(state.drafts, key = { "d${it.uri}" }) { FileRowView(it, onAction) }
    }
    if (state.recent.isNotEmpty()) {
        item { GroupLabel(stringResource(R.string.nav_recent)) }
        items(state.recent, key = { "r${it.uri}" }) { FileRowView(it, onAction) }
    }
    state.folderName?.let { name ->
        item { GroupLabel(stringResource(R.string.nav_in_folder, name)) }
        items(state.folder, key = { "f${it.uri}" }) { FileRowView(it, onAction) }
    }
    item { PlainRow(stringResource(R.string.nav_open_file)) { onAction(NavigatorAction.OpenPicker) } }
}

private fun LazyListScope.results(
    state: NavigatorUiState,
    onAction: (NavigatorAction) -> Unit,
) {
    val results = state.results
    if (results.isEmpty) {
        item { Hint(stringResource(R.string.nav_no_results, state.query.trim())) }
        item { PlainRow(stringResource(R.string.nav_open_file)) { onAction(NavigatorAction.OpenPicker) } }
        return
    }
    if (results.headings.isNotEmpty()) {
        item { GroupLabel(stringResource(R.string.nav_in_file)) }
        items(results.headings, key = { "sh${it.line}" }) { HeadingRow(it, onAction) }
    }
    if (results.recent.isNotEmpty()) {
        item { GroupLabel(stringResource(R.string.nav_recent_files)) }
        items(results.recent, key = { "sr${it.uri}" }) { FileRowView(it, onAction) }
    }
    if (results.folder.isNotEmpty()) {
        item { GroupLabel(stringResource(R.string.nav_in_folder, state.folderName.orEmpty())) }
        items(results.folder, key = { "sf${it.uri}" }) { FileRowView(it, onAction) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeadingRow(
    row: OutlineRow,
    onAction: (NavigatorAction) -> Unit,
) {
    // "You're here" is a filled tint plus weight, never a one-sided accent border.
    val tint = if (row.current) Modifier.background(SS.colors.segmentTrack) else Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SS.dimens.row)
                .then(tint)
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = { onAction(NavigatorAction.HeadingMenu(row.line)) },
                    onClick = { onAction(NavigatorAction.JumpToHeading(row.line)) },
                ).padding(start = 16.dp + IndentPerLevel * (row.level - 1).coerceAtLeast(0)),
    ) {
        Text(
            row.text,
            style = if (row.level <= 1 || row.current) SS.type.bodyStrong else SS.type.body,
            color = SS.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.current) Text(stringResource(R.string.nav_here), style = SS.type.caption, color = SS.colors.inkMuted)
        IconButton(onClick = { onAction(NavigatorAction.HeadingMenu(row.line)) }) {
            Icon(AppIcons.more, stringResource(R.string.nav_row_menu, row.text), tint = SS.colors.inkMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRowView(
    row: FileRow,
    onAction: (NavigatorAction) -> Unit,
) {
    val untitled = stringResource(R.string.untitled)
    val name = if (row.kind == FileRowKind.Draft) untitled else row.name
    val detail =
        listOfNotNull(
            if (row.kind == FileRowKind.Draft &&
                row.detail.isNotEmpty()
            ) {
                "“${row.detail}…”"
            } else {
                row.detail.ifEmpty { null }
            },
            row.ageMillis?.let { relativeTimeText(RelativeTime.ofAge(it)) },
            if (row.oldDraft) stringResource(R.string.nav_old_draft) else null,
        ).joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SS.dimens.rowPrimary)
                .combinedClickable(role = Role.Button, onClick = { onAction(NavigatorAction.OpenFile(row.uri)) })
                .padding(start = 16.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = SS.type.body,
                    color = if (row.muted) SS.colors.inkMuted else SS.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A drawn dot labelled "Unsaved", never a "●" read as "black circle" (UX-6).
                if (row.dirty) UnsavedDot(Modifier.padding(start = 6.dp))
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = SS.type.meta,
                    color = SS.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.kind == FileRowKind.Draft) {
            IconButton(onClick = { onAction(NavigatorAction.DeleteDraft(row.uri)) }) {
                Icon(AppIcons.close, stringResource(R.string.nav_delete_draft), tint = SS.colors.inkMuted)
            }
        }
    }
}

@Composable
private fun PlainRow(
    label: String,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = SS.dimens.row,
                ).combinedClickableCompat(onClick)
                .padding(horizontal = 16.dp),
    ) {
        if (icon) {
            Icon(
                AppIcons.arrowUp,
                contentDescription = null,
                tint = SS.colors.inkMuted,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(label, style = SS.type.body.copy(fontWeight = FontWeight.Medium), color = SS.colors.ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    combinedClickable(role = Role.Button, onClick = onClick)

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = SS.type.label,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = SS.type.meta, color = SS.colors.inkMuted, modifier = Modifier.padding(16.dp))
}
