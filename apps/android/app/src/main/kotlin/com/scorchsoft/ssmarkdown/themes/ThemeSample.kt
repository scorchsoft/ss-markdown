package com.scorchsoft.ssmarkdown.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.ThemeVariant

private val SampleShape = RoundedCornerShape(10.dp)
private const val OPAQUE = 0xFF000000.toInt()

/** A theme colour as a Compose colour. */
fun ThemeColor.toCompose(): Color = Color(OPAQUE or rgb)

/**
 * The theme editor's live preview: a heading, body text with a link and a
 * code chip, in the theme's own colours and sizes, light and dark side by
 * side so a change to either palette shows at once.
 */
@Composable
fun ThemeSamples(
    theme: Theme,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeSample(theme, ThemeVariant.LIGHT, Modifier.weight(1f))
        ThemeSample(theme, ThemeVariant.DARK, Modifier.weight(1f))
    }
}

@Composable
private fun ThemeSample(
    theme: Theme,
    variant: ThemeVariant,
    modifier: Modifier,
) {
    val palette = theme.palette(variant)
    val base = theme.size.base.sp
    val serif = theme.base == "reader" && theme.fonts.body == "Literata"
    val bodyFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif
    Column(
        modifier
            .background(palette.bg.toCompose(), SampleShape)
            .border(SS.dimens.hairline, SS.colors.border, SampleShape)
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.theme_sample_heading),
            color = palette.heading.toCompose(),
            fontSize = base * theme.size.headingScale.toFloat(),
            fontWeight = FontWeight.Bold,
            fontFamily = bodyFamily,
        )
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.theme_sample_body))
                append(' ')
                withStyle(SpanStyle(color = palette.link.toCompose(), textDecoration = TextDecoration.Underline)) {
                    append(stringResource(R.string.theme_sample_link))
                }
                append('.')
            },
            color = palette.text.toCompose(),
            fontSize = base * BODY_SCALE,
            fontFamily = bodyFamily,
            lineHeight = base * BODY_SCALE * theme.size.lineHeight.toFloat(),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Text(
            stringResource(R.string.theme_sample_code),
            color = palette.code.toCompose(),
            fontFamily = FontFamily.Monospace,
            fontSize = base * CODE_SCALE,
            modifier =
                Modifier
                    .background(
                        palette.codeBg.toCompose(),
                        RoundedCornerShape(4.dp),
                    ).padding(horizontal = 4.dp),
        )
    }
}

/** The samples are small: body text at 80 % of the base, code a little smaller. */
private const val BODY_SCALE = 0.8f
private const val CODE_SCALE = 0.72f
