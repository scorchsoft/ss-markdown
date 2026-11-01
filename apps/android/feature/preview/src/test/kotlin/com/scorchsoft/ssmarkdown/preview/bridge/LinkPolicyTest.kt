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
    fun `mailto keeps only subject, body, cc and bcc, and bidi controls are dropped (SEC-7)`() {
        assertEquals(
            LinkTarget.External("mailto:a@b.c?subject=Hi&body=x"),
            LinkPolicy.classify("mailto:a@b.c?subject=Hi&attach=/sdcard/secret.pdf&body=x&ATTACHMENT=y"),
        )
        assertEquals(LinkTarget.External("mailto:a@b.c"), LinkPolicy.classify("mailto:a@b.c?attach=x"))
        assertEquals(
            LinkTarget.External("https://example.com/evil.com"),
            LinkPolicy.classify("https://example.com/\u202Eevil.com"),
        )
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
