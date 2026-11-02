package com.scorchsoft.ssmarkdown.theme.codec

import com.scorchsoft.ssmarkdown.theme.Palette
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.json.JsonParse
import com.scorchsoft.ssmarkdown.theme.json.JsonReader
import com.scorchsoft.ssmarkdown.theme.json.JsonWriter
import com.scorchsoft.ssmarkdown.theme.json.JsonWriter.Out

/**
 * Theme JSON import and export (docs/ux-spec.md, Themes).
 *
 * Import is forgiving about what is missing (it inherits from `base`,
 * Clean by default) and strict about what is wrong: every bad value is
 * reported with its line and key, and nothing is half-imported. Export
 * writes every field, so a file works even if a future built-in changes.
 * `decode(encode(theme)) == theme` for every custom theme.
 */
public object ThemeCodec {
    /** Largest theme file accepted; real ones are under 2 KB. */
    public const val MAX_BYTES: Int = 256 * 1024

    /** Reads a theme file's text. Never throws. */
    public fun decode(text: String): ThemeDecodeResult {
        if (text.length > MAX_BYTES) {
            return ThemeDecodeResult.Invalid(listOf(ThemeProblem(null, null, "the file is too large to be a theme")))
        }
        return when (val parsed = JsonReader.parse(text)) {
            is JsonParse.Failed -> {
                ThemeDecodeResult.Invalid(
                    listOf(ThemeProblem(parsed.line, null, "the JSON is broken: ${parsed.message}")),
                )
            }

            is JsonParse.Ok -> {
                ThemeReader().read(parsed.value)
            }
        }
    }

    /** Writes [theme] as complete, pretty JSON in the ux-spec key order. */
    public fun encode(theme: Theme): String =
        JsonWriter.write(
            Out.Object(
                listOf(
                    "version" to Out.Number(Theme.SCHEMA_VERSION.toDouble()),
                    "name" to Out.Text(theme.name),
                    "base" to Out.Text(theme.base ?: theme.id),
                    "fonts" to
                        Out.Object(
                            listOf(
                                "body" to Out.Text(theme.fonts.body),
                                "heading" to Out.Text(theme.fonts.heading),
                                "code" to Out.Text(theme.fonts.code),
                            ),
                        ),
                    "size" to
                        Out.Object(
                            listOf(
                                "base" to Out.Number(theme.size.base),
                                "headingScale" to Out.Number(theme.size.headingScale),
                                "lineHeight" to Out.Number(theme.size.lineHeight),
                            ),
                        ),
                    "light" to palette(theme.light),
                    "dark" to palette(theme.dark),
                    "css" to Out.Text(theme.css),
                ),
            ),
        )

    private fun palette(palette: Palette): Out.Object =
        Out.Object(PaletteKey.entries.map { it.json to Out.Text(palette[it].hex) })
}
