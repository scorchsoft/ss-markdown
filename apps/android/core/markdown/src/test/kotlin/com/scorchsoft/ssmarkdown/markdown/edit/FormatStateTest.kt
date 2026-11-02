package com.scorchsoft.ssmarkdown.markdown.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatStateTest {
    private fun at(marked: String): FormatState = marked(marked).let { FormatState.at(it.text, it.selection) }

    @Test
    fun inlineFormatsAreReportedWhereTheToggleWouldRemoveThem() {
        assertEquals(setOf(InlineFormat.Bold), at("a **bo‸ld** b").inline)
        assertEquals(setOf(InlineFormat.Bold, InlineFormat.Italic), at("***bo‸th***").inline)
        assertEquals(setOf(InlineFormat.Code), at("`co‸de`").inline)
        assertEquals(emptySet<InlineFormat>(), at("‸**bold**").inline)
        assertEquals(setOf(InlineFormat.Italic), at("_«it»_").inline)
    }

    @Test
    fun blockState() {
        assertEquals(FormatState(headingLevel = 2, block = BlockType.Heading2), at("## Ti‸tle"))
        assertEquals(FormatState(list = ListKind.Task, quote = true, block = BlockType.Task), at("> - [ ] ta‸sk"))
        assertEquals(FormatState(list = ListKind.Numbered, block = BlockType.Numbered), at("3. ite‸m"))
        assertEquals(FormatState(list = ListKind.Bullet, block = BlockType.Bullet), at("- ite‸m"))
        assertEquals(FormatState(), at("‸"))
    }
}
