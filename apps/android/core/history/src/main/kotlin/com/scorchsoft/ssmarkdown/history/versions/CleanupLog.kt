package com.scorchsoft.ssmarkdown.history.versions

import com.scorchsoft.ssmarkdown.history.journal.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Why versions were removed: the wording of a "Recent clean-ups" row. */
public enum class CleanupReason(
    internal val code: String,
) {
    /** The thinning schedule (hourly after a day, daily after a week, weekly after 90 days). */
    Thinned("thinned"),

    /** The per-file limit. */
    FileLimit("file-limit"),

    /** The total history limit. */
    TotalLimit("total-limit"),

    /** "Forget history of files unused for" a set time. */
    UnusedFile("unused-file"),

    /** Versions whose stored data was found damaged while rewriting; they could not be rebuilt. */
    Damaged("damaged"),

    /** A clear the user asked for on the Storage screen or per file. */
    Cleared("cleared"),

    /** History deleted when it was switched off. */
    HistoryOff("history-off"),
    ;

    internal companion object {
        fun of(code: String): CleanupReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One row of Storage → Recent clean-ups, e.g. "3 Nov · thinned 34 older
 * versions of handbook.md to stay under 1 GB".
 *
 * @property timeMillis when it happened (wall time).
 * @property document the file whose history it was.
 * @property versionsRemoved how many versions went.
 * @property bytesFreed disk space it freed (0 or more).
 * @property reason why.
 * @property limitBytes the limit it kept to, for the limit reasons.
 */
public data class CleanupEntry(
    public val timeMillis: Long,
    public val document: DocumentKey,
    public val versionsRemoved: Int,
    public val bytesFreed: Long,
    public val reason: CleanupReason,
    public val limitBytes: Long? = null,
)

/**
 * The record of every version removed from history, so nothing is ever
 * removed silently (docs/ux-spec.md, "Automatic limits").
 *
 * JSON Lines in `cleanup-log.jsonl`, appended and synced per entry. A
 * torn last line (a crash mid-append) is skipped on read. The log keeps
 * its newest [KEEP_ENTRIES] rows once it grows past [MAX_ENTRIES].
 */
public class CleanupLog internal constructor(
    private val file: File,
) {
    /** Appends [entries] durably. Throws [IOException]. */
    internal fun append(entries: List<CleanupEntry>) {
        if (entries.isEmpty()) return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            entries.forEach { out.write((Json.write(toJson(it)) + "\n").toByteArray()) }
            out.flush()
            out.fd.sync()
        }
        val all = read()
        if (all.size > MAX_ENTRIES) {
            AtomicFile(
                file,
            ).write(all.takeLast(KEEP_ENTRIES).joinToString("") { Json.write(toJson(it)) + "\n" }.toByteArray())
        }
    }

    /** Every entry still in the log, oldest first. */
    public fun read(): List<CleanupEntry> {
        val text =
            try {
                if (file.isFile) file.readText() else ""
            } catch (ignored: IOException) {
                ""
            }
        return text
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { fromJson(it) }
            .toList()
    }

    private fun toJson(entry: CleanupEntry): Map<String, Any?> =
        linkedMapOf(
            "time" to entry.timeMillis,
            "uri" to entry.document.uri,
            "removed" to entry.versionsRemoved,
            "freed" to entry.bytesFreed,
            "reason" to entry.reason.code,
            "limit" to entry.limitBytes,
        )

    private fun fromJson(line: String): CleanupEntry? =
        runCatching {
            val map = Json.parse(line) as Map<*, *>
            CleanupEntry(
                timeMillis = map.long("time"),
                document = DocumentKey(map.string("uri")),
                versionsRemoved = map.int("removed"),
                bytesFreed = map.long("freed"),
                reason = requireNotNull(CleanupReason.of(map.string("reason"))),
                limitBytes = map.longOrNull("limit"),
            )
        }.getOrNull()

    /** Size bounds of the log. */
    public companion object {
        /** Entries the log may hold before it is trimmed. */
        public const val MAX_ENTRIES: Int = 2_000

        /** Entries kept after a trim. */
        public const val KEEP_ENTRIES: Int = 1_000
    }
}
