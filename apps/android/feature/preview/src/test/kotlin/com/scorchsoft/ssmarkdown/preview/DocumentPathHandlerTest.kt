package com.scorchsoft.ssmarkdown.preview

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * The page names pictures beside the document as `<climbs>/<path>`
 * (web/src/core/imagePaths.ts), because a URL parser folds `..` away; the
 * host turns that back into the path as written and lets its resolver decide
 * how far a climb may go (SEC-9).
 */
@RunWith(RobolectricTestRunner::class)
class DocumentPathHandlerTest {
    @Test
    fun `a key becomes the relative path it names`() {
        assertEquals("img/a.png", DocPaths.relative("0/img/a.png"))
        assertEquals("../../x.png", DocPaths.relative("2/x.png"))
        assertEquals("a%20b.png", DocPaths.relative("0/a%20b.png"))
        // From Uri.getPath, already decoded: encoded again so it is decoded once, not twice.
        assertEquals("a%20b.png", DocPaths.relative("0/a b.png", decoded = true))
        assertEquals("50%25.png", DocPaths.relative("0/50%.png", decoded = true))
    }

    @Test
    fun `malformed keys name nothing`() {
        assertNull(DocPaths.relative("img/a.png"))
        assertNull(DocPaths.relative("0/"))
        assertNull(DocPaths.relative("0/../x.png"))
        assertNull(DocPaths.relative("-1/x.png"))
        assertNull(DocPaths.relative("999/x.png"))
        assertNull(DocPaths.relative(""))
    }

    @Test
    fun `the handler serves only images, through the resolver`() {
        val asked = ArrayList<String>()
        val handler =
            DocumentPathHandler {
                DocumentResourceResolver { path ->
                    asked += path
                    ByteArrayInputStream(byteArrayOf(1))
                }
            }
        assertEquals("image/png", handler.handle("1/shared/a b.png")?.mimeType)
        assertEquals(listOf("../shared/a%20b.png"), asked)
        assertNull(handler.handle("0/notes.txt"))
        assertNull(handler.handle("../a.png"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `a picture asked for by a page that held another document is not served from this one`() {
        val asked = ArrayList<String>()
        val resolver =
            DocumentResourceResolver { path ->
                asked += path
                ByteArrayInputStream(byteArrayOf(1))
            }
        var epoch = 2
        val loader =
            PreviewWebViews.assetLoader(ApplicationProvider.getApplicationContext(), { resolver }, { null })
        val client = PreviewWebViewClient(loader, onPageFinished = {}, onRendererGone = {}, pageEpoch = { epoch })
        val url = "${PreviewWebViewClient.ORIGIN}/doc/0/img/a.png?d=1&e=2"

        assertEquals("image/png", client.intercept(Uri.parse(url)).mimeType)
        assertEquals(listOf("img/a.png"), asked)

        // The page is given document 3; a request A's page made just before is refused, not resolved against 3.
        epoch = 3
        assertEquals(404, client.intercept(Uri.parse(url)).statusCode)
        assertEquals(404, client.intercept(Uri.parse("${PreviewWebViewClient.ORIGIN}/doc/0/img/a.png")).statusCode)
        assertEquals(1, asked.size)
    }

    @Test
    fun `the default status opens the picture`() {
        val found = DocumentResourceResolver { ByteArrayInputStream(byteArrayOf(1)) }
        val missing = DocumentResourceResolver { null }
        assertEquals(ImageStatus.OK, found.status("a.png"))
        assertEquals(ImageStatus.NOT_FOUND, missing.status("a.png"))
    }

    @Test
    fun `a query names at most two hundred short paths`() {
        val paths = (0 until 500).joinToString(",") { "\"0/$it.png\"" }
        val long = "\"0/" + "x".repeat(2_000) + ".png\""
        val query = BridgeCodec.decode("""{"type":"imageQuery","id":1,"paths":[$long,$paths]}""")
        assertEquals(199, (query as PageMessage.ImageQuery).paths.size)
    }
}
