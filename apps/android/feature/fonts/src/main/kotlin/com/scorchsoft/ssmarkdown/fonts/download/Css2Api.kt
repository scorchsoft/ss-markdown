package com.scorchsoft.ssmarkdown.fonts.download

import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import java.net.URLEncoder

/**
 * One face listed in a CSS2 API response.
 *
 * @property style its weight and slope.
 * @property url the file on `fonts.gstatic.com`.
 */
data class RemoteFace(
    val style: FontStyleKey,
    val url: String,
)

/**
 * The Google Fonts CSS2 API (https://developers.google.com/fonts/docs/css2):
 * the request URL for a family's styles, and the `@font-face` rules it
 * answers with.
 *
 * Asked with a non-browser User-Agent, the API answers with plain TrueType
 * files, which both `Typeface` (the editor) and the WebView (the preview)
 * load. That is why the app sends its own agent string.
 */
object Css2Api {
    /** The only host font files may come from; anything else is refused. */
    const val FILE_HOST: String = "fonts.gstatic.com"

    /** Sent with every request; see the object KDoc. */
    const val USER_AGENT: String = "SSMarkdown (Android)"

    private const val ENDPOINT = "https://fonts.googleapis.com/css2"
    private val block = Regex("""@font-face\s*\{([^}]*)}""")
    private val styleRule = Regex("""font-style:\s*(normal|italic)""")
    private val weightRule = Regex("""font-weight:\s*(\d{3})""")
    private val srcRule = Regex("""src:\s*url\(\s*['"]?(https://[^)'"\s]+)['"]?\s*\)""")

    /** The request URL for [family] in [styles]: `…/css2?family=Lora:ital,wght@0,400;1,700`. */
    fun url(
        family: String,
        styles: List<FontStyleKey>,
    ): String {
        val axes =
            styles
                .sortedWith(compareBy({ it.italic }, { it.weight }))
                .joinToString(";") { "${if (it.italic) 1 else 0},${it.weight}" }
        val name = URLEncoder.encode(family, Charsets.UTF_8.name())
        return "$ENDPOINT?family=$name:ital,wght@$axes"
    }

    /** The faces in a CSS2 response. Rules without a recognisable style or URL are skipped. */
    fun parse(css: String): List<RemoteFace> =
        block
            .findAll(css)
            .mapNotNull { match ->
                val body = match.groupValues[1]
                val italic = styleRule.find(body)?.groupValues?.get(1) == "italic"
                val weight =
                    weightRule
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                val url = srcRule.find(body)?.groupValues?.get(1)
                if (weight == null || url == null) null else RemoteFace(FontStyleKey(weight, italic), url)
            }.distinctBy { it.style }
            .toList()
}
