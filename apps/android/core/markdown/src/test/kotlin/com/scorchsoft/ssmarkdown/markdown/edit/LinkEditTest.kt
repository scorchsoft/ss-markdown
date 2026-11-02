package com.scorchsoft.ssmarkdown.markdown.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strip's Link prompt: what it finds, writes and takes off (docs/ux-spec.md, "Formatting strip"). */
class LinkEditTest {
    @Test
    fun writesTheLinkOverTheSelectionOrAtTheCaret() {
        assertCases(
            EditCommand.SetLink("words", "https://a.b"),
            listOf(
                Case("Some «words» here", "Some [words](https://a.b)‸ here"),
                Case("At ‸ here", "At [words](https://a.b)‸ here"),
            ),
        )
        // No text: the address is the text.
        assertCases(EditCommand.SetLink("", "https://a.b"), listOf(Case("‸", "[https://a.b](https://a.b)‸")))
    }

    @Test
    fun editsTheLinkTheSelectionIsIn() {
        assertCases(
            EditCommand.SetLink("new", "https://new.example"),
            listOf(
                Case("See [old](https://old.example)‸ now", "See [new](https://new.example)‸ now"),
                Case("See [o‸ld](https://old.example) now", "See [new](https://new.example)‸ now"),
                // A title is kept as written.
                Case("[o‸ld](https://old.example \"Title\")", "[new](https://new.example \"Title\")‸"),
                Case("[o‸ld](<https://old.example/a b>)", "[new](https://new.example)‸"),
            ),
        )
    }

    @Test
    fun escapesWhatWouldEndTheTextOrTheAddressEarly() {
        assertCases(
            EditCommand.SetLink("a [b] c", "https://x.y/a b"),
            listOf(Case("‸", "[a \\[b\\] c](<https://x.y/a b>)‸")),
        )
        assertCases(
            EditCommand.SetLink("w", "https://en.wikipedia.org/wiki/Foo_(bar)"),
            listOf(Case("‸", "[w](https://en.wikipedia.org/wiki/Foo_(bar))‸")),
        )
        assertCases(EditCommand.SetLink("w", "https://x.y/a)b"), listOf(Case("‸", "[w](<https://x.y/a)b>)‸")))
    }

    @Test
    fun removesTheLinkAndKeepsItsText() {
        assertCases(
            EditCommand.RemoveLink,
            listOf(
                Case("See [o‸ld](https://old.example) now", "See «old» now"),
                // Not in a link: nothing changes.
                Case("Pl‸ain", "Pl‸ain"),
                // An image is not a link.
                Case("![al‸t](pic.png)", "![al‸t](pic.png)"),
            ),
        )
    }

    @Test
    fun findsTheExistingLinkForThePrompt() {
        fun at(source: String): ExistingLink? = marked(source).let { ExistingLink.at(it.text, it.selection) }
        assertEquals(ExistingLink("old", "https://old.example"), at("See [o‸ld](https://old.example) now"))
        assertEquals(ExistingLink("a", "b c"), at("[‸a](<b c>)"))
        assertEquals(ExistingLink("x [y]", "u"), at("[x [y]‸](u)"))
        assertNull(at("See [old](https://old.example) n‸ow"))
        assertNull(at("[not a link]‸ here"))
        assertNull(at("\\[esc‸aped](u)"))
        assertNull(at("![im‸age](u)"))
    }

    @Test
    fun knowsAnAddress() {
        for (url in listOf("https://a.b", "http://a.b/c?d=e", "mailto:a@b.c", "www.example.com", " HTTPS://A.B ")) {
            assertTrue(url, ExistingLink.isUrl(url))
        }
        for (text in listOf("", "words", "a.b", "https://", "https://a b", "ftp://x")) {
            assertFalse(text, ExistingLink.isUrl(text))
        }
    }
}
