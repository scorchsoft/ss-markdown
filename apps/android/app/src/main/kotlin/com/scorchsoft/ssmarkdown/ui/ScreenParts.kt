package com.scorchsoft.ssmarkdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.chrome.inkSwitchColors

/**
 * A secondary screen: a bar with Back and a title (56 dp, taller at a
 * large font), then scrolling content, edge to edge with the insets applied.
 */
@Composable
fun SecondaryScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(SS.colors.bg)) {
        Column(Modifier.background(SS.colors.chrome).statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = SS.dimens.topBar,
                        ).padding(horizontal = SS.dimens.grid),
            ) {
                IconButton(
                    onClick = onBack,
                ) { Icon(AppIcons.back, stringResource(R.string.screen_back), tint = SS.colors.ink) }
                Text(
                    title,
                    style = SS.type.title,
                    color = SS.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
            HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
        }
        val body = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(body)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/** A section heading in a settings-style list. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = SS.type.label,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** A muted note under a section. */
@Composable
fun NoteText(text: String) {
    Text(
        text,
        style = SS.type.meta,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A tappable row: title, optional detail, optional trailing value and chevron. */
@Composable
fun SettingRow(
    title: String,
    detail: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val click = onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = SS.dimens.rowPrimary,
                ).then(click)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SS.type.body, color = SS.colors.ink)
            detail?.let { Text(it, style = SS.type.meta, color = SS.colors.inkMuted) }
        }
        value?.let { Text(it, style = SS.type.label, color = SS.colors.inkMuted) }
        trailing()
        if (onClick != null &&
            value != null
        ) {
            Icon(AppIcons.chevronRight, contentDescription = null, tint = SS.colors.inkMuted)
        }
    }
}

/**
 * A row whose whole width toggles an ink switch (switches are never red).
 * The row is the toggleable node, so TalkBack reads "Line numbers, on,
 * switch" and announces the new state (UX-3); the inner [Switch] has no
 * callback and therefore no semantics of its own.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    detail: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SS.dimens.rowPrimary)
                .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SS.type.body, color = SS.colors.ink)
            detail?.let { Text(it, style = SS.type.meta, color = SS.colors.inkMuted) }
        }
        Switch(checked = checked, onCheckedChange = null, colors = inkSwitchColors())
    }
}

/** A neutral tinted info box with an icon: the Android-buttons note, never an accent bar. */
@Composable
fun InfoNote(
    title: String,
    body: String,
) {
    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .background(SS.colors.infoBg, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AppIcons.info, contentDescription = null, tint = SS.colors.info)
            Text(title, style = SS.type.bodyStrong, color = SS.colors.info)
        }
        Text(body, style = SS.type.meta, color = SS.colors.ink, modifier = Modifier.padding(top = 6.dp))
    }
}
