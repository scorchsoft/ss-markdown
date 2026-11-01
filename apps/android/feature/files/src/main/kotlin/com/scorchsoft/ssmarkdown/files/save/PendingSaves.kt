package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fingerprint.ByteDigest
import com.scorchsoft.ssmarkdown.files.fingerprint.HashingOutputStream
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The write-ahead record of a save (docs/robustness.md §2): what a save
 * is about to write, kept in app storage until the save has settled, so
 * a process killed part way through the write can be repaired at the
 * next launch.
 *
 * A file reached through the Storage Access Framework cannot be renamed
 * into place, so its bytes are overwritten where they are; a kill (or a
 * battery pull) in the middle leaves it half old, half new. The backup
 * alone cannot fix that afterwards, because the next launch cannot tell a
 * torn write of ours from a change another app made. With this record it
 * can: the old bytes are the base copy, the new bytes are here, and a
 * file made only of those two, byte for byte, is our torn write.
 *
 * Per document, beside its base copy: `<name>.pending` (the new bytes)
 * and `<name>.intent` (which file, and the digests of the old and new
 * bytes). Each is written whole and synced before it is renamed into
 * place, the bytes first; an intent therefore always names complete
 * bytes. The base copy stays the *old* version until [finish], which the
 * store runs only after the journal has moved to the new version, so a
 * kill between the write and that step still has the text the journal's
 * edits apply to.
 */
class PendingSaves internal constructor(
    private val copies: BaseCopyStore,
) {
    /**
     * A save that started and has not settled.
     *
     * @property uri the file being written.
     * @property old the bytes it held before (the base copy, while this record exists).
     * @property new the bytes being written, kept in [bytes].
     * @property bytes the file holding [new]; null if it is missing.
     */
    class Record internal constructor(
        val uri: DocumentUri,
        val old: ByteDigest,
        val new: ByteDigest,
        val bytes: File?,
    ) {
        /** [old] as a base fingerprint (no `lastModified`), for matching the base copy and journals. */
        val oldFingerprint: BaseFingerprint get() = BaseFingerprint(old.size, 0, old.sha256)

        /** Opens the new bytes. */
        fun openNew(): InputStream = BufferedInputStream(requireNotNull(bytes) { "no pending bytes" }.inputStream())
    }

    /**
     * Records that [uri], now holding [old], is about to get the bytes
     * [write] produces. Returns the record once both files are durable.
     *
     * @throws IOException if app storage refused; nothing has been written to [uri] then.
     */
    fun begin(
        uri: DocumentUri,
        old: BaseFingerprint,
        write: (OutputStream) -> Unit,
    ): Record {
        copies.root.mkdirs()
        val bytes = bytesFile(uri)
        val digest = writeWhole(bytes) { out -> HashingOutputStream(out).also(write).result() }
        writeWhole(intentFile(uri)) { out ->
            out.write(encode(uri, ByteDigest(old.size, old.sha256), digest).toByteArray(Charsets.UTF_8))
        }
        return Record(uri, ByteDigest(old.size, old.sha256), digest, bytes)
    }

    /** The unsettled record for [uri], if any. */
    fun find(uri: DocumentUri): Record? = read(intentFile(uri))

    /** Every unsettled record: what the launch scan repairs. */
    fun all(): List<Record> =
        copies.root
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(INTENT_SUFFIX) }
            .mapNotNull(::read)

    /**
     * The save of [new] to [uri] has settled: its bytes become the base
     * copy and the record goes. Does nothing (and returns false) if the
     * record is for another save, as when a later save has begun since.
     * The intent goes first, so a kill in between leaves no record and the
     * old base copy, never a record whose old bytes are gone.
     */
    fun finish(
        uri: DocumentUri,
        new: ByteDigest,
    ): Boolean {
        val record = find(uri) ?: return false
        val bytes = record.bytes
        if (!record.new.sameBytesAs(new) || bytes == null) return false
        intentFile(uri).delete()
        return try {
            move(bytes, copies.fileFor(uri))
            true
        } catch (_: IOException) {
            bytes.delete()
            copies.delete(uri) // A stale copy must not pass for this version.
            false
        }
    }

    /** Drops [uri]'s record: the file holds its old bytes, or something nobody can repair. */
    fun clear(uri: DocumentUri) {
        intentFile(uri).delete()
        bytesFile(uri).delete()
    }

    /** The files this store keeps for [records]; everything else of ours in the directory is a leftover. */
    internal fun keptNames(records: Collection<Record>): Set<String> =
        records.flatMapTo(HashSet()) { listOf(intentFile(it.uri).name, bytesFile(it.uri).name) }

    private fun bytesFile(uri: DocumentUri) =
        File(
            copies.root,
            copies.fileFor(uri).nameWithoutExtension + PENDING_SUFFIX,
        )

    private fun intentFile(uri: DocumentUri) =
        File(
            copies.root,
            copies.fileFor(uri).nameWithoutExtension + INTENT_SUFFIX,
        )

    private fun read(file: File): Record? {
        val lines =
            try {
                file.takeIf { it.isFile }?.readLines(Charsets.UTF_8)
            } catch (_: IOException) {
                null
            } ?: return null
        if (lines.size < INTENT_LINES || lines.first() != MAGIC) return null
        val (uriLine, oldLine, newLine) = lines.drop(1)
        val uri = DocumentUri(uriLine)
        val old = digest(oldLine) ?: return null
        val new = digest(newLine) ?: return null
        return Record(uri, old, new, bytesFile(uri).takeIf { it.isFile && it.length() == new.size })
    }

    private companion object {
        const val MAGIC = "ssmarkdown-save 1"
        const val INTENT_LINES = 4
        const val PENDING_SUFFIX = ".pending"
        const val INTENT_SUFFIX = ".intent"
        const val STAGING_SUFFIX = ".new"
        const val HEX_RADIX = 16

        fun encode(
            uri: DocumentUri,
            old: ByteDigest,
            new: ByteDigest,
        ) = "$MAGIC\n${uri.value}\n${old.size} ${hex(old.sha256)}\n${new.size} ${hex(new.sha256)}\n"

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun digest(line: String): ByteDigest? {
            val parts = line.split(' ')
            val size = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val hex = parts.getOrNull(1)?.takeIf { it.length == BaseFingerprint.SHA256_BYTES * 2 } ?: return null
            val sha =
                ByteArray(BaseFingerprint.SHA256_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
            return ByteDigest(size, sha)
        }

        /** Writes [target] whole: into `<target>.new`, synced, then renamed over [target]. */
        fun <T> writeWhole(
            target: File,
            body: (OutputStream) -> T,
        ): T {
            val staging = File(target.path + STAGING_SUFFIX)
            try {
                val result =
                    FileOutputStream(staging).use { file ->
                        val buffered = BufferedOutputStream(file)
                        val value = body(buffered)
                        buffered.flush()
                        file.fd.sync()
                        value
                    }
                move(staging, target)
                return result
            } catch (e: IOException) {
                staging.delete()
                throw e
            }
        }

        fun move(
            from: File,
            to: File,
        ) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
