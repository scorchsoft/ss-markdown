package com.scorchsoft.ssmarkdown.fonts.download

import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import com.scorchsoft.ssmarkdown.fonts.importing.FontFileSniffer
import com.scorchsoft.ssmarkdown.fonts.store.CopyOutcome
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFile
import com.scorchsoft.ssmarkdown.fonts.store.Sha256
import com.scorchsoft.ssmarkdown.fonts.store.boundedCopy
import java.io.File
import java.io.IOException

/** The outcome of fetching one file. */
internal sealed interface Fetched {
    data class Ok(
        val file: InstalledFile,
    ) : Fetched

    data class Failed(
        val failure: DownloadResult.Failure,
    ) : Fetched
}

/**
 * Streams one font file to disk, hashing as it goes and checking it on
 * arrival: the declared length must match, the header must be a usable
 * font, and the running total must stay inside [DownloadLimits]. Network
 * and disk errors are told apart so the message is right.
 */
internal class FileFetcher(
    private val http: HttpTransport,
    private val failures: Failures,
) {
    /** Builds the failure values for one family. */
    class Failures(
        val family: String,
        val fallback: String,
    ) {
        fun network(error: IOException): DownloadResult.Failure =
            if (isOffline(
                    error,
                )
            ) {
                DownloadResult.Offline(family, fallback)
            } else {
                DownloadResult.Interrupted(family, fallback)
            }

        fun storage(): DownloadResult.Failure = DownloadResult.StorageFailed(family, fallback)

        fun corrupt(detail: String): DownloadResult.Failure = DownloadResult.Corrupt(family, fallback, detail)

        fun tooLarge(limit: Long): DownloadResult.Failure = DownloadResult.TooLarge(family, fallback, limit)

        fun status(code: Int): DownloadResult.Failure =
            if (code == HTTP_BAD_REQUEST || code == HTTP_NOT_FOUND) {
                DownloadResult.NotFound(family, fallback)
            } else {
                DownloadResult.ServerError(family, fallback, code)
            }

        private companion object {
            const val HTTP_BAD_REQUEST = 400
            const val HTTP_NOT_FOUND = 404
        }
    }

    /**
     * Fetches [url] into [target] as [style]. [budget] is how many bytes
     * this file may use; [onBytes] reports each chunk written.
     */
    @Suppress("ReturnCount") // Each early return is one reason a response is refused before it is kept.
    suspend fun fetch(
        url: String,
        style: FontStyleKey,
        target: File,
        budget: Long,
        onBytes: (Int) -> Unit,
    ): Fetched {
        val response =
            try {
                http.get(url, mapOf("User-Agent" to Css2Api.USER_AGENT))
            } catch (error: IOException) {
                return Fetched.Failed(failures.network(error))
            }
        return response.use {
            if (response.status != HTTP_OK) return Fetched.Failed(failures.status(response.status))
            if (response.contentLength > budget) return Fetched.Failed(failures.tooLarge(budget))
            val output =
                try {
                    target.outputStream()
                } catch (_: IOException) {
                    return Fetched.Failed(failures.storage())
                }
            val digest = Sha256.newDigest()
            val copied =
                output.use {
                    boundedCopy(response.body, it, budget) { chunk, count ->
                        digest.update(chunk, 0, count)
                        onBytes(count)
                    }
                }
            when (copied) {
                is CopyOutcome.ReadFailed -> {
                    Fetched.Failed(failures.network(copied.error))
                }

                CopyOutcome.WriteFailed -> {
                    Fetched.Failed(failures.storage())
                }

                CopyOutcome.OverLimit -> {
                    Fetched.Failed(failures.tooLarge(budget))
                }

                is CopyOutcome.Done -> {
                    check(target, response.contentLength, copied.bytes)?.let { Fetched.Failed(it) }
                        ?: Fetched.Ok(InstalledFile(target, style, copied.bytes, Sha256.hex(digest)))
                }
            }
        }
    }

    private fun check(
        target: File,
        declared: Long,
        written: Long,
    ): DownloadResult.Failure? =
        when {
            declared >= 0 && declared != written -> failures.corrupt("expected $declared bytes, got $written")
            !FontFileSniffer.sniff(target).usable -> failures.corrupt("not a TrueType/OpenType font")
            else -> null
        }

    private companion object {
        const val HTTP_OK = 200
    }
}
