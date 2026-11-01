package com.scorchsoft.ssmarkdown.preview.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPolicyTest {
    @Test
    fun `web, mail and phone links are external`() {
        for (href in listOf("https://a.b", "HTTP://a.b", "mailto:x@y.z", "tel:+44123")) {
            assertEquals(LinkTarget.External(href), LinkPolicy.classify(href))
        }
    }

    @Test
    fun `local and script schemes are always blocked`() {
        val hostile =
            listOf(
                "file:///etc/passwd",
                "content://a/b",
                "intent://x#Intent;end",
                "javascript:x",
                " JaVaScRiPt:x",
                "data:text/html,x",
                "//evil.example",
                "",
            )
        for (href in hostile) assertEquals(href, LinkTarget.Blocked, LinkPolicy.classify(href))
    }

    @Test
    fun `relative paths are left to the host to resolve`() {
        assertEquals(LinkTarget.Relative("../other.md"), LinkPolicy.classify("../other.md"))
    }
}
