package com.scorchsoft.ssmarkdown.theme.codec

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.Palette
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.ThemeFonts
import com.scorchsoft.ssmarkdown.theme.ThemeSize
import com.scorchsoft.ssmarkdown.theme.json.JsonValue
import com.scorchsoft.ssmarkdown.theme.json.Member

/**
 * Turns a parsed JSON tree into a [Theme], collecting every problem
 * rather than stopping at the first, and filling each missing field from
 * the base theme. One instance reads one file.
 */
internal class ThemeReader {
    private val findings = Findings()

    fun read(root: JsonValue): ThemeDecodeResult {
        if (root !is JsonValue.Obj) {
            return ThemeDecodeResult.Invalid(listOf(ThemeProblem(root.line, null, "a theme must be a JSON object")))
        }
        checkVersion(root)
        findings.warnUnknown(root, ROOT_KEYS)
        val name = readName(root)
        val base = readBase(root)
        val theme =
            Theme(
                id = customId(name),
                name = name,
                base = base.id,
                fonts = readFonts(root.member("fonts"), base.fonts),
                size = readSize(root.member("size"), base.size),
                light = readPalette(root.member("light"), base.light),
                dark = readPalette(root.member("dark"), base.dark),
                css = readCss(root.member("css")),
            )
        return if (findings.problems.isEmpty()) {
            ThemeDecodeResult.Decoded(theme, findings.warnings.toList())
        } else {
            ThemeDecodeResult.Invalid(findings.problems.sortedBy { it.line ?: 0 })
        }
    }

    private fun checkVersion(root: JsonValue.Obj) {
        val member = root.member("version") ?: return
        val version = findings.number(member) ?: return
        if (version != Math.rint(version) || version < 1) {
            findings.problem(member, "must be a whole number from 1")
        } else if (version > Theme.SCHEMA_VERSION) {
            findings.problem(member, "is ${plainNumber(version)}; this theme needs a newer SS Markdown")
        }
    }

    @Suppress("ReturnCount") // Missing, wrong type and present each end differently.
    private fun readName(root: JsonValue.Obj): String {
        val member = root.member("name")
        if (member == null) {
            findings.problems += ThemeProblem(root.line, "name", "is missing; every theme needs a name")
            return ""
        }
        val name = findings.string(member)?.trim() ?: return ""
        when {
            name.isEmpty() -> findings.problem(member, "can't be empty")
            name.length > MAX_NAME_LENGTH -> findings.problem(member, "is longer than $MAX_NAME_LENGTH characters")
        }
        return name
    }

    @Suppress("ReturnCount") // Absent and wrong type both mean the default; unknown also reports.
    private fun readBase(root: JsonValue.Obj): Theme {
        val member = root.member("base") ?: return BuiltInThemes.DEFAULT
        val id = findings.string(member) ?: return BuiltInThemes.DEFAULT
        return BuiltInThemes.byId(id) ?: BuiltInThemes.DEFAULT.also {
            findings.problem(member, "must be one of ${BuiltInThemes.ALL.joinToString { it.id }}")
        }
    }

    private fun readFonts(
        member: Member?,
        inherited: ThemeFonts,
    ): ThemeFonts {
        val obj = member?.let { findings.objectOf(it) } ?: return inherited
        findings.warnUnknown(obj, FONT_KEYS)
        return ThemeFonts(
            body = fontName(obj.member("body")) ?: inherited.body,
            heading = fontName(obj.member("heading")) ?: inherited.heading,
            code = fontName(obj.member("code")) ?: inherited.code,
        )
    }

    @Suppress("ReturnCount") // Absent, wrong type and malformed each yield "inherit".
    private fun fontName(member: Member?): String? {
        val name = member?.let { findings.string(it) }?.trim() ?: return null
        if (!FONT_NAME.matches(name)) {
            findings.problem(member, "isn't a font family name")
            return null
        }
        return name
    }

    private fun readSize(
        member: Member?,
        inherited: ThemeSize,
    ): ThemeSize {
        val obj = member?.let { findings.objectOf(it) } ?: return inherited
        findings.warnUnknown(obj, SIZE_KEYS)
        return ThemeSize(
            base = bounded(obj.member("base"), ThemeSize.BASE_RANGE) ?: inherited.base,
            headingScale =
                bounded(obj.member("headingScale"), ThemeSize.HEADING_SCALE_RANGE) ?: inherited.headingScale,
            lineHeight = bounded(obj.member("lineHeight"), ThemeSize.LINE_HEIGHT_RANGE) ?: inherited.lineHeight,
        )
    }

    @Suppress("ReturnCount") // Absent, wrong type and out of range each yield "inherit".
    private fun bounded(
        member: Member?,
        range: ClosedFloatingPointRange<Double>,
    ): Double? {
        val value = member?.let { findings.number(it) } ?: return null
        if (value !in range) {
            val (low, high) = plainNumber(range.start) to plainNumber(range.endInclusive)
            findings.problem(member, "must be between $low and $high")
            return null
        }
        return value
    }

    private fun readPalette(
        member: Member?,
        inherited: Palette,
    ): Palette {
        val obj = member?.let { findings.objectOf(it) } ?: return inherited
        var palette = inherited
        for (entry in obj.members) {
            val key = PaletteKey.fromJson(entry.name)
            val text = if (key == null) null else findings.string(entry)
            val color = text?.let { ThemeColor.parse(it) }
            when {
                key == null -> findings.warn(entry, "isn't a palette colour and was ignored")
                text == null -> Unit
                color == null -> findings.problem(entry, "isn't a colour")
                else -> palette = palette.with(key, color)
            }
        }
        return palette
    }

    private fun readCss(member: Member?): String {
        val css = member?.let { findings.string(it) } ?: return ""
        CssPolicy.violation(css)?.let { findings.problem(member, it) }
        return css
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        private const val CUSTOM_PREFIX = "custom-"
        private val ROOT_KEYS = setOf("version", "name", "base", "fonts", "size", "light", "dark", "css")
        private val FONT_KEYS = setOf("body", "heading", "code")
        private val SIZE_KEYS = setOf("base", "headingScale", "lineHeight")

        /** Google Fonts family names are letters, digits and single spaces. */
        private val FONT_NAME = Regex("""[A-Za-z0-9]+( [A-Za-z0-9]+)*""")
        private val NOT_SLUG = Regex("[^a-z0-9]+")

        /** A stable id for a custom theme: never equal to a built-in's. */
        fun customId(name: String): String = CUSTOM_PREFIX + name.lowercase().replace(NOT_SLUG, "-").trim('-')
    }
}
