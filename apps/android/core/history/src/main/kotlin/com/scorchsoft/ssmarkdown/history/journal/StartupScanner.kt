package com.scorchsoft.ssmarkdown.history.journal

import java.io.File
import java.io.IOException

/**
 * The launch-time walk over a store's directory, kept apart from
 * [JournalStore] because it is the one place that meets files the
 * current session did not write.
 *
 * Only `*.ssmj` and `*.ssmj.new` are considered; anything else in the
 * directory is not ours to judge and is left alone.
 */
internal class StartupScanner(
    private val root: File,
    private val quarantine: Quarantine,
) {
    private val unclean = mutableListOf<JournalHeader>()
    private val historyOnly = mutableListOf<JournalHeader>()
    private val unsupported = mutableListOf<UnsupportedJournal>()
    private val quarantined = mutableListOf<File>()
    private val failures = mutableListOf<IOException>()

    fun scan(): StartupScan {
        val files =
            root
                .listFiles()
                ?.filter { it.isFile }
                .orEmpty()
                .sortedBy { it.name }
        // Leftovers first, so a journal and its `.new` are settled before the journal is read.
        files.filter { it.name.endsWith(JOURNAL_SUFFIX + AtomicFile.NEW_SUFFIX) }.forEach(::settleLeftover)
        files.filter { it.name.endsWith(JOURNAL_SUFFIX) }.forEach(::inspect)
        return StartupScan(
            unclean.toList(),
            unsupported.toList(),
            quarantined.toList(),
            failures.toList(),
            historyOnly.toList(),
        )
    }

    private fun settleLeftover(newFile: File) =
        guarded {
            quarantine.settleLeftover(newFile)?.let { quarantined += it }
        }

    private fun inspect(file: File) =
        guarded {
            when (val read = JournalReader(file).header()) {
                is HeaderRead.Ok -> {
                    // Named by the base the edits start from now: a Rebase (a save it outlived) moves it.
                    val summary = JournalReader(file).summary()
                    val header = summary?.rebase?.let { read.header.copy(base = it) } ?: read.header
                    if (summary?.holdsEdits == false) historyOnly += header else unclean += header
                }

                is HeaderRead.Unsupported -> {
                    unsupported += UnsupportedJournal(file, read.formatVersion)
                }

                is HeaderRead.Unreadable -> {
                    quarantined += quarantine.admit(file)
                }
            }
        }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            failures += e
        }
    }

    private companion object {
        const val JOURNAL_SUFFIX = "." + JournalFormat.FILE_EXTENSION
    }
}
