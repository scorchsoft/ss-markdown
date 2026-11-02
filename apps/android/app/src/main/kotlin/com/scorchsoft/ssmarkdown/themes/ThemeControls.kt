package com.scorchsoft.ssmarkdown.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.preview.bridge.ThemeFonts
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.css.FontFaceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ChipShape = RoundedCornerShape(12.dp)

/**
 * Display → Preview theme (mockup Display): a chip per theme showing "Aa"
 * in its own page and heading colours, built-ins first, then custom
 * themes; and Edit theme… for the chosen one (a built-in starts a copy).
 */
@Composable
fun ThemeControls(
    themes: List<Theme>,
    selectedId: String,
    dark: Boolean,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.display_preview_theme),
            style = SS.type.label,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            themes.forEach { theme -> ThemeChip(theme, theme.id == selectedId, dark) { onSelect(theme.id) } }
        }
        TextButton(onClick = { onEdit(selectedId) }) {
            Text(stringResource(R.string.display_edit_theme), style = SS.type.label, color = SS.colors.ink)
        }
    }
}

@Composable
private fun ThemeChip(
    theme: Theme,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val palette = if (dark) theme.dark else theme.light
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .heightIn(min = SS.dimens.chip)
                .background(palette.bg.toCompose(), ChipShape)
                .border(
                    if (selected) 2.dp else SS.dimens.hairline,
                    if (selected) SS.colors.ink else SS.colors.border,
                    ChipShape,
                ).clickable(role = Role.RadioButton, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(R.string.theme_chip_sample),
            color = palette.heading.toCompose(),
            style = SS.type.bodyStrong.copy(fontWeight = FontWeight.Bold),
        )
        Text(theme.name, color = palette.text.toCompose(), style = SS.type.label)
    }
}

/** Whether the app is drawing dark now: Display → Appearance, or the phone's setting under System. */
@Composable
fun isDark(appearance: Appearance): Boolean =
    when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }

/**
 * The preview's look for the chosen theme, recomputed when the theme, its
 * file or the appearance changes. Its fonts are resolved from [faces] off
 * the main thread and arrive a moment later.
 */
@Composable
fun rememberPreviewLook(
    library: ThemeLibrary,
    themeId: String,
    appearance: Appearance,
    faces: FontFaceSource = FontFaceSource.None,
): PreviewLook {
    val customs by library.themes.collectAsState()
    val dark = isDark(appearance)
    val theme = remember(themeId, customs) { library.byId(themeId) }
    val look = remember(theme, dark) { PreviewLook.of(theme, dark) }
    val fonts by produceState<ThemeFonts?>(null, theme, faces) {
        value = withContext(Dispatchers.IO) { PreviewLook.fontsOf(theme, faces) }
    }
    return remember(look, fonts) { look.copy(fonts = fonts) }
}
