package com.scorchsoft.ssmarkdown.fonts.catalogue

import com.scorchsoft.ssmarkdown.fonts.FontCategory
import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleFontsCatalogueTest {
    private val shipped =
        File("src/main/assets/${GoogleFontsCatalogue.ASSET}").reader().use {
            GoogleFontsCatalogue.parse(it)
        }

    @Test
    fun `the shipped catalogue is the full Latin list and parses cleanly`() {
        assertTrue("only ${shipped.entries.size}", shipped.entries.size > 1_500)
        val rows =
            File("src/main/assets/${GoogleFontsCatalogue.ASSET}").readLines().count {
                it.isNotBlank() &&
                    !it.startsWith("#")
            }
        assertEquals("every row parses", rows, shipped.entries.size)
        assertEquals(
            shipped.entries.size,
            shipped.entries
                .map { it.family }
                .distinct()
                .size,
        )
    }

    @Test
    fun `every bundled family is also on Google Fonts`() {
        BundledFonts.ALL.forEach { assertTrue(it.family, shipped.find(it.family) != null) }
        assertEquals(FontCategory.SERIF, shipped.find("lora")?.category)
    }

    @Test
    fun `search puts prefix matches first and filters by category`() {
        val catalogue =
            GoogleFontsCatalogue(
                listOf(
                    entry("Noto Serif", FontCategory.SERIF),
                    entry("Lora", FontCategory.SERIF),
                    entry("Source Serif 4", FontCategory.SERIF),
                    entry("Serif Display", FontCategory.DISPLAY),
                ),
            )
        assertEquals(
            listOf("Serif Display", "Noto Serif", "Source Serif 4"),
            catalogue.search("serif").map { it.family },
        )
        assertEquals(
            listOf("Noto Serif", "Source Serif 4"),
            catalogue.search("SERIF", FontCategory.SERIF).map { it.family },
        )
        assertEquals(2, catalogue.search("", limit = 2).size)
        assertNull(catalogue.find("Comic Sans"))
    }

    @Test
    fun `malformed rows are skipped`() {
        val text = "# header\nGood\tserif\t400,700i\t12\nBad\tnope\t400\t1\nNoStyles\tsans\t\t1\nShort\tsans\n"
        val catalogue = GoogleFontsCatalogue.parse(text.reader())
        assertEquals(listOf("Good"), catalogue.entries.map { it.family })
        assertEquals(listOf(FontStyleKey(400, false), FontStyleKey(700, true)), catalogue.entries.single().styles)
    }

    private fun entry(
        family: String,
        category: FontCategory,
    ) = CatalogueEntry(family, category, listOf(FontStyleKey(400, false)), 50)
}
