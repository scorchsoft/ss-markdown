package com.scorchsoft.ssmarkdown.fonts.download

import com.scorchsoft.ssmarkdown.fonts.Clock
import com.scorchsoft.ssmarkdown.fonts.catalogue.CatalogueEntry
import com.scorchsoft.ssmarkdown.fonts.store.CopyOutcome
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFile
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFont
import com.scorchsoft.ssmarkdown.fonts.store.InstalledSource
import com.scorchsoft.ssmarkdown.fonts.store.boundedCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * Downloads one Google Fonts family into the [FontStore]: asks the CSS2
 * API which files make up the requested styles, fetches each from
 * `fonts.gstatic.com`, checks it (length, font header, size caps, SHA-256
 * recorded), and only then moves the complete family into place.
 *
 * This is the only network code in the app (CLAUDE.md). It never runs at
 * build time and never fetches the catalogue: only a family the person
 * chose.
 *
 * @param http the transport; tests pass a fake.
 * @param store where families are installed.
 * @param clock stamps the install time.
 * @param io the dispatcher blocking I/O runs on.
 * @param limits size caps.
 */
class FontDownloader(
    private val http: HttpTransport,
    private val store: FontStore,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: DownloadLimits = DownloadLimits(),
) {
    /**
     * Downloads [entry]'s styles. [fallback] names the bundled family
     * shown meanwhile, for failure messages. [onProgress] is called on the
     * I/O dispatcher after every chunk. Cancel the calling coroutine to
     * stop; partial files are removed before the cancellation propagates.
     */
    suspend fun download(
        entry: CatalogueEntry,
        fallback: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult =
        withContext(io) {
            val failures = FileFetcher.Failures(entry.family, fallback)
            val staging =
                try {
                    store.stage()
                } catch (_: IOException) {
                    return@withContext failures.storage()
                }
            var result: DownloadResult? = null
            try {
                result = fetchFamily(entry, staging, failures, onProgress)
                result
            } finally {
                if (result !is DownloadResult.Done) staging.deleteRecursively()
            }
        }

    private suspend fun fetchFamily(
        entry: CatalogueEntry,
        staging: File,
        failures: FileFetcher.Failures,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        val faces =
            when (val css = fetchCss(entry, failures)) {
                is CssOutcome.Failed -> return css.failure
                is CssOutcome.Ok -> css.faces
            }
        val fetcher = FileFetcher(http, failures)
        val files = mutableListOf<InstalledFile>()
        var done = 0L
        onProgress(DownloadProgress(entry.family, 0, faces.size, 0))
        for (face in faces) {
            val budget = minOf(limits.maxFileBytes, limits.maxFamilyBytes - done)
            val target = File(staging, "${face.style.token}.ttf")
            val fetched =
                fetcher.fetch(face.url, face.style, target, budget) { bytes ->
                    done += bytes
                    onProgress(DownloadProgress(entry.family, files.size, faces.size, done))
                }
            when (fetched) {
                is Fetched.Failed -> return fetched.failure
                is Fetched.Ok -> files += fetched.file
            }
        }
        onProgress(DownloadProgress(entry.family, files.size, faces.size, done))
        val font = InstalledFont(entry.family, InstalledSource.GOOGLE, clock.nowMillis(), files)
        return try {
            DownloadResult.Done(store.commit(staging, font))
        } catch (_: IOException) {
            failures.storage()
        }
    }

    private sealed interface CssOutcome {
        data class Ok(
            val faces: List<RemoteFace>,
        ) : CssOutcome

        data class Failed(
            val failure: DownloadResult.Failure,
        ) : CssOutcome
    }

    @Suppress("ReturnCount") // Connect, status, size and content each refuse the CSS for their own reason.
    private suspend fun fetchCss(
        entry: CatalogueEntry,
        failures: FileFetcher.Failures,
    ): CssOutcome {
        val response =
            try {
                http.get(Css2Api.url(entry.family, entry.styles), mapOf("User-Agent" to Css2Api.USER_AGENT))
            } catch (error: IOException) {
                return CssOutcome.Failed(failures.network(error))
            }
        val buffer = ByteArrayOutputStream()
        val copied =
            response.use {
                if (it.status != HTTP_OK) return CssOutcome.Failed(failures.status(it.status))
                boundedCopy(it.body, buffer, limits.maxCssBytes.toLong())
            }
        when (copied) {
            is CopyOutcome.ReadFailed -> return CssOutcome.Failed(failures.network(copied.error))

            CopyOutcome.OverLimit, CopyOutcome.WriteFailed -> return CssOutcome.Failed(
                failures.corrupt("CSS response too large"),
            )

            is CopyOutcome.Done -> Unit
        }
        val faces = Css2Api.parse(buffer.toString(Charsets.UTF_8.name()))
        return when {
            faces.isEmpty() -> CssOutcome.Failed(failures.corrupt("no @font-face rules"))

            faces.any {
                host(
                    it.url,
                ) != Css2Api.FILE_HOST
            } -> CssOutcome.Failed(failures.corrupt("unexpected font host"))

            else -> CssOutcome.Ok(faces)
        }
    }

    private fun host(url: String): String? =
        try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        }

    private companion object {
        const val HTTP_OK = 200
    }
}
