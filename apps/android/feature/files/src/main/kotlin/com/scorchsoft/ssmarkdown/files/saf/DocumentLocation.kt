package com.scorchsoft.ssmarkdown.files.saf

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import java.io.ByteArrayOutputStream

/**
 * Where a document lives, for the second line of a recent-file row
 * ("Internal storage › Notes › 2024", "Google Drive", "Downloads"), so two
 * files called `notes.md` can be told apart.
 *
 * Read from the URI alone: no provider call, so it is instant for a list
 * of recents and still works for a file whose grant has lapsed. Only the
 * well-known providers expose readable paths in their document ids; for
 * the rest [place] names the provider and [folders] is empty.
 *
 * @property place the storage or app the file is in.
 * @property folders folder names from the place's root down to the file's
 *   parent, as the provider spells them; empty when it does not say.
 * @property otherName the URI authority, for [Place.Other] only.
 */
data class DocumentLocation(
    val place: Place,
    val folders: List<String> = emptyList(),
    val otherName: String? = null,
) {
    /** The places the app can name. */
    enum class Place {
        InternalStorage,
        SdCard,
        Downloads,
        GoogleDrive,
        OneDrive,
        Dropbox,
        Media,
        Drafts,
        DeviceFile,
        Other,
    }

    /**
     * "Place › folder › folder". [label] names each place; pass a resource
     * lookup to localise it. Long paths keep the place and the last
     * [MAX_FOLDERS] folders, with "…" between, so the row stays one line.
     */
    fun displayString(label: (Place) -> String = ::englishLabel): String {
        val head = if (place == Place.Other) otherName ?: label(place) else label(place)
        val shown = if (folders.size > MAX_FOLDERS) listOf(ELLIPSIS) + folders.takeLast(MAX_FOLDERS) else folders
        return (listOf(head) + shown).joinToString(SEPARATOR)
    }

    companion object {
        /** How many trailing folders [displayString] keeps. */
        const val MAX_FOLDERS: Int = 3

        private const val SEPARATOR = " › "
        private const val ELLIPSIS = "…"
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        private const val PRIMARY_VOLUME = "primary"

        private val knownAuthorities =
            mapOf(
                "com.android.providers.downloads.documents" to Place.Downloads,
                "com.google.android.apps.docs.storage" to Place.GoogleDrive,
                "com.google.android.apps.docs.storage.legacy" to Place.GoogleDrive,
                "com.microsoft.skydrive.content.StorageAccessProvider" to Place.OneDrive,
                "com.dropbox.android.FileCache" to Place.Dropbox,
                "com.dropbox.product.android.dbapp.document_provider.documents" to Place.Dropbox,
                "com.android.providers.media.documents" to Place.Media,
            )

        /** Where [uri] lives. Never throws: an unreadable URI is [Place.Other]. */
        fun of(uri: DocumentUri): DocumentLocation {
            val value = uri.value
            return when {
                uri.isDraft -> DocumentLocation(Place.Drafts)

                value.startsWith(
                    "file:",
                ) -> DocumentLocation(Place.DeviceFile, parentFolders(value.substringAfter("file:")))

                value.startsWith("content://") -> content(value.removePrefix("content://"))

                else -> DocumentLocation(Place.Other, otherName = value.substringBefore(':'))
            }
        }

        /** The English names, used when the caller does not localise. */
        fun englishLabel(place: Place): String =
            when (place) {
                Place.InternalStorage -> "Internal storage"
                Place.SdCard -> "SD card"
                Place.Downloads -> "Downloads"
                Place.GoogleDrive -> "Google Drive"
                Place.OneDrive -> "OneDrive"
                Place.Dropbox -> "Dropbox"
                Place.Media -> "Media"
                Place.Drafts -> "Drafts"
                Place.DeviceFile -> "This device"
                Place.Other -> "Other app"
            }

        private fun content(rest: String): DocumentLocation {
            val authority = rest.substringBefore('/')
            val place = knownAuthorities[authority]
            return when {
                authority == EXTERNAL_STORAGE -> externalStorage(documentId(rest))
                place != null -> DocumentLocation(place)
                else -> DocumentLocation(Place.Other, otherName = authority)
            }
        }

        /** `primary:Notes/2024/a.md` → Internal storage › Notes › 2024; other volumes are SD cards or USB drives. */
        private fun externalStorage(documentId: String): DocumentLocation {
            val volume = documentId.substringBefore(':')
            val path = documentId.substringAfter(':', missingDelimiterValue = "")
            val place = if (volume == PRIMARY_VOLUME) Place.InternalStorage else Place.SdCard
            return DocumentLocation(place, parentFolders(path))
        }

        /** The document id: the segment after `document/` in a tree URI, else the last segment. */
        private fun documentId(rest: String): String {
            val segments = rest.substringBefore('?').split('/')
            val marker = segments.indexOf("document")
            val raw = if (marker >= 0 && marker + 1 < segments.size) segments[marker + 1] else segments.last()
            return decode(raw)
        }

        private fun parentFolders(path: String): List<String> =
            decode(path).split('/').filter { it.isNotEmpty() }.dropLast(1)

        /**
         * Percent-decoding as SAF writes it: `%2F` and `%20` become `/` and
         * space, `+` stays `+` (a legal file-name char), and a malformed
         * escape is kept as typed rather than failing the whole row.
         */
        private fun decode(value: String): String {
            val bytes = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val hex = if (value[i] == '%') value.substring(i + 1, minOf(i + ESCAPE_LENGTH, value.length)) else ""
                if (hex.length == 2 && hex.all { it.isHexDigit() }) {
                    bytes.write(hex.toInt(HEX))
                    i += ESCAPE_LENGTH
                } else {
                    val codePoint = value.codePointAt(i)
                    bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(codePoint)
                }
            }
            return bytes.toString("UTF-8")
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private const val HEX = 16
        private const val ESCAPE_LENGTH = 3
    }
}
