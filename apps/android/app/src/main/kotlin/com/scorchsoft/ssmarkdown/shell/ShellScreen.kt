package com.scorchsoft.ssmarkdown.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl

private val GutterWidth = 34.dp

/**
 * The app shell: the two bars around an empty draft. A pure
 * `(state, callbacks)` composable, so it renders under Robolectric.
 *
 * Only the chrome is real in iteration 1. The page shows the spec's empty
 * state (an untitled draft with "Start writing…" on line 1); the editor
 * itself replaces it in iteration 2.
 */
@Composable
fun ShellScreen(
    mode: ViewMode,
    onModeSelect: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(SS.colors.bg)) {
        TopBar(
            title = stringResource(R.string.untitled),
            status = stringResource(R.string.status_draft),
        )
        EmptyDraft(Modifier.weight(1f))
        BottomBar(mode = mode, onModeSelect = onModeSelect)
    }
}

@Composable
private fun TopBar(
    title: String,
    status: String,
) {
    Column(Modifier.background(SS.colors.chrome).statusBarsPadding()) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SS.dimens.topBar)
                    .padding(horizontal = SS.dimens.grid * 4),
        ) {
            Text(
                text = title,
                style = SS.type.bodyStrong,
                color = SS.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = SS.type.meta, color = SS.colors.inkMuted, maxLines = 1)
        }
        HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
    }
}

@Composable
private fun EmptyDraft(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = SS.dimens.grid * 3)) {
        Text(
            text = "1",
            style = SS.type.caption.copy(lineHeight = SS.type.code.lineHeight),
            color = SS.colors.gutter,
            textAlign = TextAlign.End,
            modifier = Modifier.width(GutterWidth).padding(end = 10.dp),
        )
        Text(stringResource(R.string.placeholder_start_writing), style = SS.type.code, color = SS.colors.gutter)
    }
}

@Composable
private fun BottomBar(
    mode: ViewMode,
    onModeSelect: (ViewMode) -> Unit,
) {
    val labels =
        listOf(
            stringResource(R.string.mode_code),
            stringResource(R.string.mode_split),
            stringResource(R.string.mode_preview),
        )
    Column(Modifier.background(SS.colors.chrome).navigationBarsPadding()) {
        HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
        Box(Modifier.fillMaxWidth().height(SS.dimens.bottomBar), contentAlignment = Alignment.Center) {
            SSSegmentedControl(
                options = labels,
                selectedIndex = mode.ordinal,
                onSelect = { onModeSelect(ViewMode.entries[it]) },
            )
        }
    }
}
