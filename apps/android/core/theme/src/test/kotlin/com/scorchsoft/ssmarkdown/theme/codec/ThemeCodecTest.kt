package com.scorchsoft.ssmarkdown.theme.codec

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ThemeCodecTest {
    private val specExample =
        """
        {
          "name": "Studio Serif",
          "base": "reader",
          "fonts": { "body": "Lora", "heading": "Lora", "code": "Fira Code" },
          "size": { "base": 16, "headingScale": 1.25, "lineHeight": 1.65 },
          "light": { "bg": "#FBF7EF", "text": "#2B2620", "heading": "#7A2E1B", "link": "#9B3B22", "codeBg": "#F1EADC" },
          "dark":  { "bg": "#1E1B17", "text": "#E9E1D3", "heading": "#F0A58C", "link": "#F2B49E", "codeBg": "#2A2620" },
          "css": ""
        }
        """.trimIndent()

    private fun decoded(text: String): ThemeDecodeResult.Decoded =
        when (val result = ThemeCodec.decode(text)) {
            is ThemeDecodeResult.Decoded -> result
            is ThemeDecodeResult.Invalid -> fail("expected a theme, got ${result.problems}") as Nothing
        }

    private fun problems(text: String): List<String> =
        when (val result = ThemeCodec.decode(text)) {
            is ThemeDecodeResult.Decoded -> fail("expected problems, got ${result.theme}") as Nothing
            is ThemeDecodeResult.Invalid -> result.problems.map { it.describe() }
        }

    @Test
    fun `the ux-spec example decodes, inheriting the rest from Reader`() {
        val result = decoded(specExample)
        val theme = result.theme
        assertEquals("Studio Serif", theme.name)
        assertEquals("custom-studio-serif", theme.id)
        assertEquals("reader", theme.base)
        assertEquals("Fira Code", theme.fonts.code)
        assertEquals(16.0, theme.size.base, 0.0)
        assertEquals("#7A2E1B", theme.light.heading.hex)
        // Not in the file, so inherited from Reader.
        assertEquals(BuiltInThemes.READER.light.muted, theme.light.muted)
        assertEquals(BuiltInThemes.READER.dark.code, theme.dark.code)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `a name alone is a theme based on Clean`() {
        val theme = decoded("""{"name": "Mine"}""").theme
        assertEquals("clean", theme.base)
        assertEquals(BuiltInThemes.CLEAN.light, theme.light)
        assertEquals(BuiltInThemes.CLEAN.size, theme.size)
    }

    @Test
    fun `round trip - decode of encode is identity for custom themes`() {
        val original = decoded(specExample).theme.copy(css = "blockquote { font-style: italic; }\n\"quoted\" \\ back")
        val json = ThemeCodec.encode(original)
        assertEquals(original, decoded(json).theme)
        assertEquals(json, ThemeCodec.encode(decoded(json).theme))
    }

    @Test
    fun `every built-in survives export and import`() {
        BuiltInThemes.ALL.forEach { builtIn ->
            val back = decoded(ThemeCodec.encode(builtIn)).theme
            assertEquals(builtIn.copy(id = back.id, base = builtIn.id), back)
        }
    }

    @Test
    fun `export reads like the spec`() {
        val json = ThemeCodec.encode(decoded(specExample).theme)
        val head = "{\n  \"version\": 1,\n  \"name\": \"Studio Serif\",\n  \"base\": \"reader\",\n"
        assertTrue(json, json.startsWith(head))
        assertTrue(json, json.contains("  \"size\": { \"base\": 16, \"headingScale\": 1.25, \"lineHeight\": 1.65 },\n"))
        assertTrue(json, json.contains("\"light\": { \"bg\": \"#FBF7EF\", \"text\": \"#2B2620\""))
    }

    @Test
    fun `a bad colour names its line and key, as in the mockup`() {
        val text = specExample.replace("\"dark\":  { \"bg\": \"#1E1B17\"", "\"dark\":  { \"bg\": \"midnight\"")
        assertEquals(listOf("line 7, “bg” isn't a colour"), problems(text))
        val result = ThemeCodec.decode(text) as ThemeDecodeResult.Invalid
        assertEquals(
            "Couldn't import midnight.json: line 7, “bg” isn't a colour.",
            result.headline("midnight.json"),
        )
        assertEquals(ThemeProblem(7, "bg", "isn't a colour"), result.problems.single())
    }

    @Test
    fun `every problem is reported, in line order`() {
        val text =
            """
            {
              "name": "",
              "base": "midnight",
              "fonts": { "body": "Lora<script>" },
              "size": { "base": 4, "lineHeight": "tall" },
              "light": { "link": "#12" }
            }
            """.trimIndent()
        assertEquals(
            listOf(
                "line 2, “name” can't be empty",
                "line 3, “base” must be one of clean, reader, compact, contrast",
                "line 4, “body” isn't a font family name",
                "line 5, “base” must be between 10 and 32",
                "line 5, “lineHeight” must be a number, not text",
                "line 6, “link” isn't a colour",
            ),
            problems(text),
        )
    }

    @Test
    fun `a huge font name is refused, not a stack overflow`() {
        val name = "a ".repeat(50_000).trim()
        val text = "{\n  \"name\": \"Big\",\n  \"fonts\": { \"body\": \"$name\" }\n}"
        assertEquals(listOf("line 3, “body” isn't a font family name"), problems(text))
    }

    @Test
    fun `broken JSON points at the line`() {
        assertEquals(
            listOf("line 3, the JSON is broken: expected “,” or “}”"),
            problems("{\n  \"name\": \"A\"\n  \"base\": \"clean\"\n}"),
        )
        assertEquals(listOf("line 1, the JSON is broken: it ends too early"), problems(""))
        assertEquals(
            listOf("line 2, the JSON is broken: “name” appears twice"),
            problems("{\"name\": \"A\",\n\"name\": \"B\"}"),
        )
        assertEquals(listOf("line 1, a theme must be a JSON object"), problems("[1, 2]"))
    }

    @Test
    fun `missing name and wrong types`() {
        assertEquals(listOf("line 1, “name” is missing; every theme needs a name"), problems("{}"))
        assertEquals(
            listOf("line 1, “fonts” must be an object, not a list"),
            problems("""{"name":"A","fonts":[]}"""),
        )
    }

    @Test
    fun `a newer file is refused rather than half-read`() {
        assertEquals(
            listOf("line 1, “version” is 2; this theme needs a newer SS Markdown"),
            problems("""{"version": 2, "name": "Future"}"""),
        )
        val current = ThemeCodec.decode("""{"version": ${Theme.SCHEMA_VERSION}, "name": "Now"}""")
        assertTrue(current is ThemeDecodeResult.Decoded)
    }

    @Test
    fun `unknown keys are warnings, not errors`() {
        val result = decoded("{\"name\": \"A\",\n\"sparkle\": true,\n\"light\": { \"glow\": \"#FFFFFF\" }}")
        assertEquals(
            listOf(
                "line 2, “sparkle” isn't used and was ignored",
                "line 3, “glow” isn't a palette colour and was ignored",
            ),
            result.warnings.map { it.describe() },
        )
    }

    @Test
    fun `custom css that could escape the style element or go online is refused`() {
        listOf(
            "</style><script>alert(1)</script>" to "can't contain “</”",
            "@import url(x.css);" to "can't use @import; themes work offline",
            "body { background: url('https://example.com/a.png') }" to
                "can't load from the internet; themes work offline",
            "p { width: expression(alert(1)) }" to "can't contain script",
        ).forEach { (css, message) ->
            val text = "{\"name\": \"A\",\n\"css\": ${quote(css)}}"
            assertEquals(listOf("line 2, “css” $message"), problems(text))
        }
        assertEquals("p { color: red }", decoded("""{"name":"A","css":"p { color: red }"}""").theme.css)
    }

    @Test
    fun `css escapes, comments and image-set cannot smuggle a load past the policy (SEC-5)`() {
        listOf(
            "@\\69mport \"https://evil.example/i.css\";" to "can't use @import; themes work offline",
            "@im/**/port \"x.css\";" to "can't use @import; themes work offline",
            "body{background-image:image-set(\"https://evil.example/a.png\" 1x)}" to
                "can't load from the internet; themes work offline",
            ".x{background:\\75rl(https://evil.example/b.png)}" to "can't load from the internet; themes work offline",
            ".y{background:url(\"\\68ttps://evil.example/c.png\")}" to
                "can't load from the internet; themes work offline",
            ".z{background:url(//evil.example/d.png)}" to "can't load from the internet; themes work offline",
            "@font-face{font-family:x;src:url(a.woff2)}" to "can't declare fonts; pick them in the theme's Fonts",
        ).forEach { (css, message) ->
            assertEquals(css, message, CssPolicy.violation(css))
        }
        listOf(
            "h1 { color: #123 }",
            "p{background:url(data:image/png;base64,AAAA)}",
            ".a::before{content:'\\2014'}",
        ).forEach { assertEquals(it, null, CssPolicy.violation(it)) }
    }

    @Test
    fun `oversized files are refused before parsing`() {
        assertEquals(listOf("the file is too large to be a theme"), problems(" ".repeat(ThemeCodec.MAX_BYTES + 1)))
    }

    @Test
    fun `short hex and a byte-order mark are accepted`() {
        val theme = decoded("\uFEFF{\"name\":\"A\",\"light\":{\"bg\":\"#fff\"}}").theme
        assertEquals(ThemeColor.WHITE, theme.light.bg)
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
