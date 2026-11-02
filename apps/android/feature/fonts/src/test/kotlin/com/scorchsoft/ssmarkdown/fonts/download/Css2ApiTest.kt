package com.scorchsoft.ssmarkdown.fonts.download

import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import org.junit.Assert.assertEquals
import org.junit.Test

class Css2ApiTest {
    @Test
    fun `builds the CSS2 URL with sorted ital,wght tuples`() {
        val styles =
            listOf(FontStyleKey(700, true), FontStyleKey(400, false), FontStyleKey(400, true), FontStyleKey(700, false))
        assertEquals(
            "https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:ital,wght@0,400;0,700;1,400;1,700",
            Css2Api.url("IBM Plex Sans", styles),
        )
    }

    @Test
    fun `parses a real-shaped response`() {
        val css =
            """
            /* latin */
            @font-face {
              font-family: 'Lora';
              font-style: italic;
              font-weight: 700;
              font-display: swap;
              src: url(https://fonts.gstatic.com/s/lora/v37/b.ttf) format('truetype');
            }
            @font-face {
              font-family: 'Lora';
              font-style: normal;
              font-weight: 400;
              src: url(https://fonts.gstatic.com/s/lora/v37/a.ttf) format('truetype');
            }
            @font-face { font-family: 'Lora'; font-style: normal; src: url(https://x/y.ttf); }
            """.trimIndent()
        assertEquals(
            listOf(
                RemoteFace(FontStyleKey(700, true), "https://fonts.gstatic.com/s/lora/v37/b.ttf"),
                RemoteFace(FontStyleKey(400, false), "https://fonts.gstatic.com/s/lora/v37/a.ttf"),
            ),
            Css2Api.parse(css),
        )
    }
}
