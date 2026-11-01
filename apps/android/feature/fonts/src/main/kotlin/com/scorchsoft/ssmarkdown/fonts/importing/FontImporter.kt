package com.scorchsoft.ssmarkdown.fonts.importing

import com.scorchsoft.ssmarkdown.fonts.Clock
import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import com.scorchsoft.ssmarkdown.fonts.store.CopyOutcome
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFile
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFont
import com.scorchsoft.ssmarkdown.fonts.store.InstalledSource
import com.scorchsoft.ssmarkdown.fonts.store.Sha256
import com.scorchsoft.ssmarkdown.fonts.store.boundedCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Imports a `.ttf` or `.otf` the person picked (mockup FontPicker: "Or
 * import a .ttf / .otf"). The bytes are copied into a staging directory,
 * identified by their header, named from the font's own name table, and
 * only then installed. The source stream is read once and never trusted
 * for its name or type.
 *
 * @param store where the font is installed.
 * @param clock stamps the install time.
 * @param io the dispatcher blocking I/O runs on.
 * @param maxBytes the largest font accepted.
 */
class FontImporter(
    private val store: FontStore,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /**
     * Imports the font that [open] streams. [fileName] is the display name,
     * used for messages and as the family name if the font has none.
     */
    suspend fun import(
        fileName: String,
        open: () -> InputStream,
    ): ImportResult =
        withContext(io) {
            val staging =
                try {
                    store.stage()
                } catch (_: IOException) {
                    return@withContext ImportResult.StorageFailed(fileName)
                }
            var result: ImportResult? = null
            try {
                result = importInto(staging, fileName, open)
                result
            } finally {
                if (result !is ImportResult.Imported) staging.deleteRecursively()
            }
        }

    @Suppress("ReturnCount") // Copy, header, tables and the final move each refuse the file for their own reason.
    private suspend fun importInto(
        staging: File,
        fileName: String,
        open: () -> InputStream,
    ): ImportResult {
        val target = File(staging, "font.bin")
        copy(fileName, open, target)?.let { return it }
        val kind = FontFileSniffer.sniff(target)
        if (!kind.usable) {
            return if (kind ==
                FontKind.UNKNOWN
            ) {
                ImportResult.NotAFont(fileName)
            } else {
                ImportResult.Unsupported(fileName, kind)
            }
        }
        val info = OpenTypeInfo.read(target) ?: return ImportResult.NotAFont(fileName)
        val style = FontStyleKey(info.weight, info.italic)
        val extension = if (kind == FontKind.OPENTYPE) "otf" else "ttf"
        val named = File(staging, "${style.token}.$extension")
        if (!target.renameTo(named)) return ImportResult.StorageFailed(fileName)
        val family = info.family ?: fileName.substringBeforeLast('.').ifBlank { fileName }
        val font =
            InstalledFont(
                family = family,
                source = InstalledSource.IMPORTED,
                installedAtMillis = clock.nowMillis(),
                files = listOf(InstalledFile(named, style, named.length(), Sha256.of(named))),
            )
        return try {
            ImportResult.Imported(store.commit(staging, font))
        } catch (_: IOException) {
            ImportResult.StorageFailed(fileName)
        }
    }

    /** Copies the source into [target]; null on success, else the failure. */
    @Suppress("ReturnCount") // Opening and writing fail before the copy starts; the copy has its own outcome.
    private suspend fun copy(
        fileName: String,
        open: () -> InputStream,
        target: File,
    ): ImportResult.Failure? {
        val input =
            try {
                open()
            } catch (_: IOException) {
                return ImportResult.Unreadable(fileName)
            } catch (_: SecurityException) {
                return ImportResult.Unreadable(fileName)
            }
        return input.use { source ->
            val output =
                try {
                    target.outputStream()
                } catch (_: IOException) {
                    return ImportResult.StorageFailed(fileName)
                }
            when (output.use { boundedCopy(source, it, maxBytes) }) {
                is CopyOutcome.Done -> null
                is CopyOutcome.ReadFailed -> ImportResult.Unreadable(fileName)
                CopyOutcome.WriteFailed -> ImportResult.StorageFailed(fileName)
                CopyOutcome.OverLimit -> ImportResult.TooLarge(fileName, maxBytes)
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
    }
}
