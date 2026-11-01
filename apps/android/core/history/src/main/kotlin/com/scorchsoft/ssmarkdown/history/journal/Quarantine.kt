package com.scorchsoft.ssmarkdown.history.journal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where journals go that the store cannot vouch for. A file we cannot
 * parse might be the only copy of someone's work in a form a later build
 * (or a person with a hex editor) can still read, so it is moved here
 * instead of deleted.
 *
 * Names keep the original and add the time, so two quarantines of the
 * same document never overwrite each other.
 */
internal class Quarantine(
    private val directory: File,
    private val clock: JournalClock,
) {
    /** Moves [file] in and returns its new location; throws [IOException] if it cannot. */
    fun admit(file: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        val target = freeName(file.name)
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        AtomicFile.syncDirectory(directory)
        AtomicFile.syncDirectory(file.absoluteFile.parentFile)
        return target
    }

    /**
     * Deals with an [AtomicFile] leftover `<journal>.new`. Beside its
     * journal it is an interrupted compaction: the journal is whole, so the
     * leftover goes. Alone it is an interrupted start or resume and may hold
     * a snapshot, so it is kept, aside. Returns where it was moved, if it was.
     */
    fun settleLeftover(newFile: File): File? {
        val journal = File(newFile.path.removeSuffix(AtomicFile.NEW_SUFFIX))
        return when {
            !newFile.exists() -> null
            journal.exists() -> null.also { newFile.delete() }
            else -> admit(newFile)
        }
    }

    private fun freeName(name: String): File {
        val stamp = clock.nowMillis()
        return generateSequence(0) { it + 1 }
            .map { n -> File(directory, if (n == 0) "$name.$stamp" else "$name.$stamp-$n") }
            .first { !it.exists() }
    }

    companion object {
        /** The folder's name inside the store's root. */
        const val DIRECTORY_NAME: String = "quarantine"
    }
}
