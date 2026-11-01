package com.scorchsoft.ssmarkdown.fonts.download

import com.scorchsoft.ssmarkdown.fonts.store.InstalledFont

/**
 * Progress of one family download, for the picker row's bar.
 *
 * @property family the family.
 * @property filesDone files finished.
 * @property filesTotal files to fetch (known once the CSS is read).
 * @property bytesDone bytes written so far, across files.
 */
data class DownloadProgress(
    val family: String,
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
)

/**
 * How a font download ended. Cancellation is not a result: cancelling the
 * coroutine throws `CancellationException` as usual, after the partial
 * files are removed.
 */
sealed interface DownloadResult {
    /** The family the download was for. */
    val family: String

    /**
     * Installed and verified.
     *
     * @property font what is now on disk; [InstalledFont.bytes] is the real size.
     */
    data class Done(
        val font: InstalledFont,
    ) : DownloadResult {
        override val family: String get() = font.family
    }

    /**
     * Nothing to download: the family ships in the APK.
     */
    data class AlreadyBundled(
        override val family: String,
    ) : DownloadResult

    /**
     * Every way a download can fail. Each carries the bundled family used
     * meanwhile, so the message can name it.
     */
    sealed interface Failure : DownloadResult {
        /** The bundled family shown until the download succeeds. */
        val fallback: String

        /** The inline message under the font row (mockup MoreStates, "Theme editor problems"). */
        fun message(): String = "Couldn't download $family — ${reason()}. Using $fallback until then."

        /** The middle of [message]. */
        fun reason(): String
    }

    /** No connection: DNS, connect or timeout failed. */
    data class Offline(
        override val family: String,
        override val fallback: String,
    ) : Failure {
        override fun reason(): String = "you're offline"
    }

    /** The family is not in the catalogue, or Google answered 400/404 for it. */
    data class NotFound(
        override val family: String,
        override val fallback: String,
    ) : Failure {
        override fun reason(): String = "Google Fonts doesn't have it"
    }

    /**
     * Google answered with another error status.
     *
     * @property status the HTTP status.
     */
    data class ServerError(
        override val family: String,
        override val fallback: String,
        val status: Int,
    ) : Failure {
        override fun reason(): String = "Google Fonts answered with error $status"
    }

    /** The connection dropped part-way through. */
    data class Interrupted(
        override val family: String,
        override val fallback: String,
    ) : Failure {
        override fun reason(): String = "the connection dropped"
    }

    /**
     * What arrived is not what was promised: not a font, the wrong length,
     * or from a host other than [Css2Api.FILE_HOST].
     *
     * @property detail for logs; never shown raw.
     */
    data class Corrupt(
        override val family: String,
        override val fallback: String,
        val detail: String,
    ) : Failure {
        override fun reason(): String = "the file arrived damaged"
    }

    /**
     * Bigger than the app will store for one family.
     *
     * @property limitBytes the limit that was hit.
     */
    data class TooLarge(
        override val family: String,
        override val fallback: String,
        val limitBytes: Long,
    ) : Failure {
        override fun reason(): String = "it's larger than ${limitBytes / BYTES_PER_MB} MB"
    }

    /** Writing to app storage failed (usually: the phone is full). */
    data class StorageFailed(
        override val family: String,
        override val fallback: String,
    ) : Failure {
        override fun reason(): String = "there isn't room on this phone"
    }

    private companion object {
        const val BYTES_PER_MB = 1024 * 1024
    }
}
