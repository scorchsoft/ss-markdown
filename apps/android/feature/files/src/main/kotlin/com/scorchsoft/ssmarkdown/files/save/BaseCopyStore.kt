package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fingerprint.ByteDigest
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The exact bytes each open document was loaded from or last saved as,
 * in app-private storage.
 *
 * One copy serves two promises (docs/robustness.md):
 * - **§2 backup**: it *is* the last-saved version, kept before every
 *   overwrite, so a failed write can be put back;
 * - **§2a merge base**: when another app changes the file under unsaved
 *   edits, it is the "base" of the three-way merge, whether or not
 *   version history is switched on.
 *
 * Copies are replaced atomically (`<name>.new`, `fsync`, rename), so a
 * crash leaves the old copy or the new one, never half of either.
 */
class BaseCopyStore(
    val root: File,
) {
    /** The copy's file for [uri], whether or not it exists. */
    fun fileFor(uri: DocumentUri): File = File(root, nameFor(uri))

    /** True when the copy for [uri] holds exactly the bytes [base] names. Reads the copy to be sure. */
    fun matches(
        uri: DocumentUri,
        base: BaseFingerprint,
    ): Boolean {
        val file = fileFor(uri)
        if (!file.isFile || file.length() != base.size) return false
        return try {
            val digest = file.inputStream().use { ByteDigest.of(it) }
            digest.sameBytesAs(ByteDigest(base.size, base.sha256))
        } catch (_: IOException) {
            false
        }
    }

    /** Opens the copy for reading, or null if there is none. */
    fun openInput(uri: DocumentUri): InputStream? =
        try {
            fileFor(uri).takeIf { it.isFile }?.inputStream()
        } catch (_: IOException) {
            null
        }

    /** Starts writing a replacement copy; nothing changes until [StagedCopy.commit]. */
    fun stage(uri: DocumentUri): StagedCopy {
        root.mkdirs()
        val target = fileFor(uri)
        return StagedCopy(target, File(root, target.name + NEW_SUFFIX))
    }

    /** Deletes the copy for [uri], if any. */
    fun delete(uri: DocumentUri) {
        fileFor(uri).delete()
    }

    /**
     * Deletes every copy except those for [keep]: run at launch, after the
     * journal scan, so copies of documents with recoverable edits survive.
     */
    fun retainOnly(keep: Collection<DocumentUri>) {
        val kept = keep.mapTo(HashSet(), ::nameFor)
        root.listFiles()?.filter { it.isFile && it.name !in kept }?.forEach { it.delete() }
    }

    /** Total bytes held, for the Storage screen. */
    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0

    /**
     * A copy being written. Write to [stream], then [commit] or [abandon].
     * A write failure is remembered rather than lost inside whoever was
     * copying, so the pipeline can tell "the provider failed" from "our
     * own storage failed".
     */
    class StagedCopy internal constructor(
        private val target: File,
        private val staging: File,
    ) {
        private var out: FileOutputStream? = null

        /** The first failure writing the copy, if any. */
        var failure: IOException? = null
            private set

        /** Where the copy's bytes go. Never throws: failures land in [failure]. */
        val stream: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (failure != null) return
                    try {
                        (out ?: FileOutputStream(staging).also { out = it }).write(b, off, len)
                    } catch (e: IOException) {
                        failure = e
                    }
                }
            }

        /** Makes the copy durable and puts it in place. False if anything failed; the old copy then stays. */
        fun commit(): Boolean {
            if (failure == null) {
                try {
                    val stream = out ?: FileOutputStream(staging)
                    stream.flush()
                    stream.fd.sync()
                    stream.close()
                    out = null
                    if (!staging.renameTo(target)) failure = IOException("cannot rename $staging")
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (failure != null) abandon()
            return failure == null
        }

        /** Drops the staged bytes; the old copy is untouched. */
        fun abandon() {
            try {
                out?.close()
            } catch (_: IOException) {
                // Closing a stream we are discarding: nothing to save.
            }
            out = null
            staging.delete()
        }
    }

    private companion object {
        const val NEW_SUFFIX = ".new"
        const val NAME_HASH_BYTES = 16

        fun nameFor(uri: DocumentUri): String {
            val digest = ByteDigest.sha256().digest(uri.value.toByteArray(Charsets.UTF_8))
            return digest.take(NAME_HASH_BYTES).joinToString("", postfix = ".base") { "%02x".format(it) }
        }
    }
}
