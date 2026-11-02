package com.scorchsoft.ssmarkdown.history.journal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces a file all at once or not at all, for plain JVM `File`s.
 *
 * Mirrors `android.util.AtomicFile` (AOSP, Apache-2.0), which this
 * pure-JVM module cannot use: write `<name>.new`, sync it, then rename it
 * over `<name>`. A rename within one directory is atomic on POSIX file
 * systems, so a reader sees the old file or the new one, never half of
 * either. The method names match AOSP's so the two read the same.
 *
 * After the rename the directory itself is synced, so the rename
 * survives a power cut and not just a process crash.
 *
 * @property baseFile the file being replaced.
 */
internal class AtomicFile(
    val baseFile: File,
) {
    /** Where the next version is written before it replaces [baseFile]. */
    val newFile: File = File(baseFile.path + NEW_SUFFIX)

    /** Opens [newFile] for writing, truncating any leftover from an interrupted write. */
    fun startWrite(): FileOutputStream {
        baseFile.parentFile?.mkdirs()
        return FileOutputStream(newFile)
    }

    /** Syncs and closes [stream], then renames [newFile] over [baseFile]. */
    fun finishWrite(stream: FileOutputStream) {
        stream.use {
            it.flush()
            it.fd.sync()
        }
        try {
            Files.move(newFile.toPath(), baseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (ignored: AtomicMoveNotSupportedException) {
            Files.move(newFile.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        syncDirectory(baseFile.absoluteFile.parentFile)
    }

    /** Abandons a write: closes [stream] and deletes [newFile]. [baseFile] is untouched. */
    fun failWrite(stream: FileOutputStream) {
        runCatching { stream.close() }
        newFile.delete()
    }

    /**
     * Writes [parts], in order, as the new content in one step. Throws
     * [IOException] and leaves [baseFile] as it was.
     */
    fun write(vararg parts: ByteArray) = write(parts.asList())

    /** [write] for parts already in a list. */
    fun write(parts: List<ByteArray>) {
        val stream = startWrite()
        try {
            parts.forEach { stream.write(it) }
            finishWrite(stream)
        } catch (e: IOException) {
            failWrite(stream)
            throw e
        }
    }

    companion object {
        /** Suffix of the in-progress file, as in AOSP. */
        const val NEW_SUFFIX: String = ".new"

        /**
         * Best-effort fsync of a directory, which makes a create or rename
         * in it durable. Linux supports it; some platforms refuse to open a
         * directory, and then there is nothing more we can do.
         */
        fun syncDirectory(directory: File?) {
            if (directory == null) return
            try {
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (ignored: IOException) {
                // Not supported here (for example on Windows); the rename itself has still happened.
            }
        }
    }
}
