package com.scorchsoft.ssmarkdown.fonts.download

import com.scorchsoft.ssmarkdown.fonts.Clock
import com.scorchsoft.ssmarkdown.fonts.FakeHttp
import com.scorchsoft.ssmarkdown.fonts.FontCategory
import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import com.scorchsoft.ssmarkdown.fonts.TestFonts
import com.scorchsoft.ssmarkdown.fonts.catalogue.CatalogueEntry
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import com.scorchsoft.ssmarkdown.fonts.store.InstalledSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FontDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val http = FakeHttp()
    private val store by lazy { FontStore(temp.newFolder("fonts")) }
    private val lora =
        CatalogueEntry("Lora", FontCategory.SERIF, listOf(FontStyleKey(400, false), FontStyleKey(700, true)), 106)
    private val cssUrl = Css2Api.url(lora.family, lora.styles)
    private val regular = TestFonts.ttf("Lora", 400)
    private val boldItalic = TestFonts.ttf("Lora", 700, italic = true)

    private fun downloader(limits: DownloadLimits = DownloadLimits()) =
        FontDownloader(http, store, Clock { 1_767_225_600_000 }, Dispatchers.Unconfined, limits)

    private fun serveLora(
        host: String = "fonts.gstatic.com",
        first: ByteArray = regular,
    ) {
        http.answer(cssUrl, body = css(host).toByteArray())
        http.answer("https://$host/s/lora/400.ttf", body = first)
        http.answer("https://$host/s/lora/700i.ttf", body = boldItalic)
    }

    private fun css(host: String) =
        """
        @font-face { font-family: 'Lora'; font-style: normal; font-weight: 400; src: url(https://$host/s/lora/400.ttf) format('truetype'); }
        @font-face { font-family: 'Lora'; font-style: italic; font-weight: 700; src: url(https://$host/s/lora/700i.ttf) format('truetype'); }
        """.trimIndent()

    private fun assertNothingLeftBehind() {
        assertEquals(
            emptyList<String>(),
            store.root
                .list()
                .orEmpty()
                .toList(),
        )
    }

    @Test
    fun `downloads, verifies and installs every style, reporting progress and size`() =
        runTest {
            serveLora()
            val progress = mutableListOf<DownloadProgress>()
            val result = downloader().download(lora, "Source Serif 4") { progress += it }

            val font = (result as DownloadResult.Done).font
            assertEquals("Lora", font.family)
            assertEquals(InstalledSource.GOOGLE, font.source)
            assertEquals(1_767_225_600_000, font.installedAtMillis)
            assertEquals(listOf("400", "700i"), font.files.map { it.style.token })
            assertEquals((regular.size + boldItalic.size).toLong(), font.bytes)
            assertEquals(font, store.find("lora"))
            assertTrue(store.verify(font))
            assertEquals(DownloadProgress("Lora", 2, 2, font.bytes), progress.last())
            assertEquals(DownloadProgress("Lora", 0, 2, 0), progress.first())
            assertTrue(http.requests.all { it.second["User-Agent"] == Css2Api.USER_AGENT })
            assertEquals(listOf("google-lora"), store.root.list()?.toList())
        }

    @Test
    fun `offline gives the mockup's sentence and leaves nothing behind`() =
        runTest {
            listOf(UnknownHostException("fonts.googleapis.com"), ConnectException(), SocketTimeoutException()).forEach {
                http.failEverything(it)
                val result = downloader().download(lora, "Inter")
                assertEquals(DownloadResult.Offline("Lora", "Inter"), result)
                assertEquals(
                    "Couldn't download Lora — you're offline. Using Inter until then.",
                    (result as DownloadResult.Failure).message(),
                )
                assertNothingLeftBehind()
            }
        }

    @Test
    fun `a family Google does not have is NotFound, other statuses are ServerError`() =
        runTest {
            http.answer(cssUrl, status = 400, body = ByteArray(0))
            assertEquals(DownloadResult.NotFound("Lora", "Inter"), downloader().download(lora, "Inter"))
            http.answer(cssUrl, status = 503, body = ByteArray(0))
            val error = downloader().download(lora, "Inter") as DownloadResult.ServerError
            assertEquals(503, error.status)
            assertEquals(
                "Couldn't download Lora — Google Fonts answered with error 503. Using Inter until then.",
                error.message(),
            )
            assertNothingLeftBehind()
        }

    @Test
    fun `a file that is not a font is refused`() =
        runTest {
            serveLora(first = "<html>captive portal</html>".toByteArray())
            val result = downloader().download(lora, "Inter")
            assertTrue(result.toString(), result is DownloadResult.Corrupt)
            assertNothingLeftBehind()
        }

    @Test
    fun `a short body is refused by the declared length`() =
        runTest {
            serveLora()
            http.answer("https://fonts.gstatic.com/s/lora/400.ttf", body = regular, declaredLength = regular.size + 10L)
            assertTrue(downloader().download(lora, "Inter") is DownloadResult.Corrupt)
            assertNothingLeftBehind()
        }

    @Test
    fun `files from any host but fonts gstatic com are refused before fetching`() =
        runTest {
            serveLora(host = "evil.example")
            assertTrue(downloader().download(lora, "Inter") is DownloadResult.Corrupt)
            assertEquals(1, http.requests.size)
        }

    @Test
    fun `size caps stop a runaway response`() =
        runTest {
            serveLora()
            http.stream("https://fonts.gstatic.com/s/lora/400.ttf") { endless() }
            val result = downloader(DownloadLimits(maxFileBytes = 64 * 1024)).download(lora, "Inter")
            assertEquals(DownloadResult.TooLarge("Lora", "Inter", 64 * 1024), result)
            assertNothingLeftBehind()
        }

    @Test
    fun `a dropped connection mid-file is Interrupted`() =
        runTest {
            serveLora()
            http.stream("https://fonts.gstatic.com/s/lora/400.ttf") {
                object : InputStream() {
                    private var served = 0

                    override fun read(): Int = if (served++ < 100) 0 else throw IOException("reset")
                }
            }
            assertEquals(DownloadResult.Interrupted("Lora", "Inter"), downloader().download(lora, "Inter"))
            assertNothingLeftBehind()
        }

    @Test
    fun `cancelling stops the download and removes partial files`() =
        runTest {
            serveLora()
            val started = CompletableDeferred<Unit>()
            http.stream("https://fonts.gstatic.com/s/lora/400.ttf") {
                object : InputStream() {
                    override fun read(): Int {
                        started.complete(Unit)
                        Thread.sleep(1)
                        return 0
                    }
                }
            }
            val real = FontDownloader(http, store, Clock { 0 }, Dispatchers.IO)
            val job = async(Dispatchers.Default) { real.download(lora, "Inter") }
            withContext(Dispatchers.Default) { started.await() }
            job.cancel()
            try {
                job.await()
                fail("expected cancellation")
            } catch (_: CancellationException) {
                // expected
            }
            assertNothingLeftBehind()
        }

    @Test
    fun `a second download replaces the first`() =
        runTest {
            serveLora()
            downloader().download(lora, "Inter")
            val again = downloader().download(lora, "Inter") as DownloadResult.Done
            assertEquals(listOf(again.font), store.installed())
        }

    private fun endless(): InputStream =
        object : InputStream() {
            private val header = ByteArrayInputStream(regular)

            override fun read(): Int = header.read().let { if (it < 0) 0 else it }
        }
}
