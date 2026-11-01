package com.scorchsoft.ssmarkdown.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen
import com.scorchsoft.ssmarkdown.ui.SectionLabel
import com.scorchsoft.ssmarkdown.ui.SettingRow

/**
 * Settings → About (ux-spec "Storage" → About): the version, "Made by
 * Scorchsoft" (scorchsoft.com), the Apache 2.0 licence, the as-is
 * disclaimer and the Open-source licences list.
 */
@Composable
fun AboutScreen(
    versionName: String,
    onOpenSite: () -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(stringResource(R.string.about_title), onBack, modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.app_name), style = SS.type.display, color = SS.colors.ink)
            Text(stringResource(R.string.about_version, versionName), style = SS.type.meta, color = SS.colors.inkMuted)
        }
        SettingRow(
            stringResource(R.string.about_made_by),
            stringResource(R.string.about_site),
            value = "",
            onClick = onOpenSite,
        )
        SettingRow(stringResource(R.string.about_licence))
        NoteText(stringResource(R.string.about_disclaimer))
        SettingRow(stringResource(R.string.about_licences), value = "", onClick = onOpenLicences)
    }
}

/** Every bundled library and font with its licence (generated list in `assets/licences.txt`). */
@Composable
fun LicencesScreen(
    licences: List<Licence>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(stringResource(R.string.licences_title), onBack, modifier) {
        licences.groupBy { it.licence }.forEach { (licence, items) ->
            SectionLabel(licence)
            items.forEach { SettingRow(it.name, it.source) }
        }
    }
}
