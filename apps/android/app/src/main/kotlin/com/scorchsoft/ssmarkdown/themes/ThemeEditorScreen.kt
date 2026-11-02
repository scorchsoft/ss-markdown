package com.scorchsoft.ssmarkdown.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen

private val NoticeShape = RoundedCornerShape(12.dp)

/**
 * The custom theme editor (mockup ThemeEditor): name and base, a live
 * preview in both variants, then Type · Colours · Spacing · JSON, with
 * Duplicate and Use this theme at the foot. Problems show inline, next to
 * what is wrong, each with a way out (mockup MoreStates).
 */
@Composable
fun ThemeEditorScreen(
    state: ThemeEditorUiState,
    onAction: (ThemeEditorAction) -> Unit,
) {
    SecondaryScreen(
        title = state.theme.name,
        onBack = { onAction(ThemeEditorAction.Back) },
        actions = {
            TextButton(onClick = { onAction(ThemeEditorAction.Import) }) {
                Text(stringResource(R.string.theme_import), style = SS.type.label, color = SS.colors.ink)
            }
            TextButton(onClick = { onAction(ThemeEditorAction.Export) }) {
                Text(stringResource(R.string.theme_export), style = SS.type.label, color = SS.colors.ink)
            }
        },
    ) {
        Text(
            stringResource(R.string.theme_based_on, state.baseName),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        state.banner?.let { Notice(it, R.string.theme_details) { onAction(ThemeEditorAction.DismissBanner) } }
        if (state.saveFailed) {
            Notice(
                stringResource(R.string.theme_save_failed),
                R.string.theme_ok,
            ) { onAction(ThemeEditorAction.DismissBanner) }
        }
        ThemeSamples(state.theme)
        SSSegmentedControl(
            options =
                listOf(
                    stringResource(R.string.theme_tab_type),
                    stringResource(R.string.theme_tab_colours),
                    stringResource(R.string.theme_tab_spacing),
                    stringResource(R.string.theme_tab_json),
                ),
            selectedIndex = state.tab.ordinal,
            onSelect = { onAction(ThemeEditorAction.SelectTab(ThemeTab.entries[it])) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        state.problems.forEach { Problem(it) }
        when (state.tab) {
            ThemeTab.Type -> TypeTab(state, onAction)
            ThemeTab.Colours -> ColoursTab(state, onAction)
            ThemeTab.Spacing -> SpacingTab(state, onAction)
            ThemeTab.Json -> JsonTab(state, onAction)
        }
        Footer(onAction)
    }
}

@Composable
private fun Footer(onAction: (ThemeEditorAction) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        OutlinedButton(onClick = { onAction(ThemeEditorAction.Duplicate) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.theme_duplicate), style = SS.type.label, color = SS.colors.ink)
        }
        TextButton(
            onClick = { onAction(ThemeEditorAction.Use) },
            modifier = Modifier.weight(1f).background(SS.colors.ink, NoticeShape),
        ) { Text(stringResource(R.string.theme_use), style = SS.type.label, color = SS.colors.bg) }
    }
}

/** A caution notice with one way out (never an accent bar: a tinted box). */
@Composable
internal fun Notice(
    text: String,
    actionLabel: Int,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .background(SS.colors.cautionBg, NoticeShape)
                .padding(start = 12.dp),
    ) {
        Text(
            text,
            style = SS.type.meta,
            color = SS.colors.caution,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        TextButton(
            onClick = onAction,
        ) { Text(stringResource(actionLabel), style = SS.type.label, color = SS.colors.caution) }
    }
}

/** An edit the validator refused, in its words ("line 12, “bg” isn't a colour"). */
@Composable
internal fun Problem(text: String) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(SS.colors.cautionBg, NoticeShape)
            .padding(10.dp),
    ) { Text(text, style = SS.type.meta, color = SS.colors.caution) }
}
