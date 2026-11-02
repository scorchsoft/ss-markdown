package com.scorchsoft.ssmarkdown.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * Scorchsoft" (scorchsoft.com), the Apache 2.0 licence with a link to the
 * source code on GitHub, the as-is disclaimer and the Open-source
 * licences list.
 */
@Composable
fun AboutScreen(
    versionName: String,
    onOpenSite: () -> Unit,
    onOpenSource: () -> Unit,
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
        SettingRow(
            stringResource(R.string.about_source),
            stringResource(R.string.about_source_link),
            value = "",
            onClick = onOpenSource,
        )
        NoteText(stringResource(R.string.about_disclaimer))
        SettingRow(stringResource(R.string.about_licences), value = "", onClick = onOpenLicences)
    }
}

/**
 * Every bundled library and font with its licence, from the hand-kept
 * `assets/licences.txt`. A row with a packaged text opens it in full.
 */
@Composable
fun LicencesScreen(
    licences: List<Licence>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (Licence) -> Unit = {},
) {
    SecondaryScreen(stringResource(R.string.licences_title), onBack, modifier) {
        licences.groupBy { it.licence }.forEach { (licence, items) ->
            SectionLabel(licence)
            items.forEach { item ->
                if (item.textAsset == null) {
                    SettingRow(item.name, item.source)
                } else {
                    // An empty value draws the chevron: this row opens something.
                    SettingRow(item.name, item.source, value = "", onClick = { onOpen(item) })
                }
            }
        }
    }
}

/**
 * One licence text in full, exactly as shipped. The preview bundle's
 * notice is about 85 KB, so paragraphs are laid out lazily.
 *
 * @param paragraphs the text from [Licences.paragraphs]; null while it loads, empty if it is missing.
 */
@Composable
fun LicenceTextScreen(
    title: String,
    paragraphs: List<String>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(title, onBack, modifier, scroll = false) {
        when {
            paragraphs == null -> {
                Unit
            }

            paragraphs.isEmpty() -> {
                NoteText(stringResource(R.string.licence_text_missing))
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(paragraphs) { paragraph ->
                        Text(
                            paragraph,
                            style = SS.type.meta,
                            color = SS.colors.ink,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
