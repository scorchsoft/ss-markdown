package com.scorchsoft.ssmarkdown.fonts.importing

import com.scorchsoft.ssmarkdown.fonts.store.InstalledFont

/** How importing a `.ttf` / `.otf` ended. Each failure has the sentence the picker shows. */
sealed interface ImportResult {
    /**
     * Added to this phone.
     *
     * @property font the imported family; its name comes from the font itself.
     */
    data class Imported(
        val font: InstalledFont,
    ) : ImportResult

    /** Every way an import can fail. */
    sealed interface Failure : ImportResult {
        /** The file's display name. */
        val fileName: String

        /** The sentence to show. */
        fun message(): String
    }

    /** The file is not a font (checked by its bytes, not its name). */
    data class NotAFont(
        override val fileName: String,
    ) : Failure {
        override fun message(): String = "Couldn't import $fileName: it isn't a .ttf or .otf font."
    }

    /**
     * A real font in a format the app can't use.
     *
     * @property kind what it is: a web font or a collection.
     */
    data class Unsupported(
        override val fileName: String,
        val kind: FontKind,
    ) : Failure {
        override fun message(): String =
            when (kind) {
                FontKind.COLLECTION -> {
                    "Couldn't import $fileName: font collections (.ttc) aren't supported. Use a .ttf or .otf."
                }

                else -> {
                    "Couldn't import $fileName: web fonts (.woff) aren't supported. Use a .ttf or .otf."
                }
            }
    }

    /**
     * Over the size cap.
     *
     * @property limitBytes the cap.
     */
    data class TooLarge(
        override val fileName: String,
        val limitBytes: Long,
    ) : Failure {
        override fun message(): String = "Couldn't import $fileName: it's larger than ${limitBytes / BYTES_PER_MB} MB."
    }

    /** The source could not be read (provider gone, permission revoked). */
    data class Unreadable(
        override val fileName: String,
    ) : Failure {
        override fun message(): String = "Couldn't read $fileName. Try choosing it again."
    }

    /** Writing to app storage failed. */
    data class StorageFailed(
        override val fileName: String,
    ) : Failure {
        override fun message(): String = "Couldn't import $fileName: there isn't room on this phone."
    }

    private companion object {
        const val BYTES_PER_MB = 1024 * 1024
    }
}
