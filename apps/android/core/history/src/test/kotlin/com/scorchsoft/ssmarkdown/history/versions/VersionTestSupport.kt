package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** A clock that moves only when a test says so. */
internal class ManualClock(
    var now: Long = 1_700_000_000_000,
) : VersionClock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** Markdown-ish texts and the edits people make to them, including the hostile corners. */
internal object Texts {
    private val words = listOf("alpha", "beta", "**bold**", "`code`", "- item", "# Head", "😀", "naïve", "\t", "[x](y)")

    fun line(random: Random): String {
        val body = List(random.nextInt(0, 9)) { words[random.nextInt(words.size)] }.joinToString(" ")
        return when (random.nextInt(20)) {
            0 -> body + "\r\n"

            1 -> body + "\uD800" + "\n"

            // A lone surrogate must survive exactly.
            else -> body + "\n"
        }
    }

    fun document(
        random: Random,
        lines: Int,
    ): String {
        val text = buildString { repeat(lines) { append(line(random)) } }
        return if (random.nextInt(4) == 0) text.dropLast(1) else text
    }

    /** One plausible edit: change, insert, delete or move a few lines, or rewrite everything rarely. */
    fun edit(
        random: Random,
        text: String,
    ): String {
        val lines = Lines.of(text).let { l -> MutableList(l.count) { l[it] } }
        when (random.nextInt(12)) {
            0 -> return document(random, random.nextInt(0, 30))
            1, 2 -> if (lines.isNotEmpty()) lines.removeAt(random.nextInt(lines.size))
            3, 4, 5 -> lines.add(random.nextInt(lines.size + 1), line(random))
            6 -> if (lines.size > 1) lines.add(random.nextInt(lines.size), lines.removeAt(random.nextInt(lines.size)))
            else -> if (lines.isNotEmpty()) lines[random.nextInt(lines.size)] = line(random)
        }
        return lines.joinToString("")
    }
}

/** A store in a temp folder with a manual clock. */
internal class TestHistory(
    val root: File,
    val clock: ManualClock = ManualClock(),
    policy: HistoryPolicy = HistoryPolicy(),
) {
    var store = VersionStore(root, clock, policy)
        private set

    /** A fresh store over the same files, as after a process restart. */
    fun reopen(policy: HistoryPolicy = store.policy) {
        store = VersionStore(root, clock, policy)
    }

    fun record(
        document: DocumentKey,
        text: String,
        kind: VersionKind = VersionKind.Saved,
    ): VersionInfo? =
        when (val result = store.record(document, text, kind)) {
            is RecordResult.Recorded -> result.version
            is RecordResult.Unchanged -> null
            else -> throw AssertionError("record failed: $result")
        }

    fun versions(document: DocumentKey): List<VersionInfo> =
        (store.list(document) as? HistoryListing.Listed)?.versions.orEmpty()

    fun text(
        document: DocumentKey,
        id: VersionId,
    ): String? = (store.read(document, id) as? ReadResult.Text)?.text

    fun documentDirectory(document: DocumentKey): File = HistoryDirectory(root).directoryFor(document)

    /** Every file under the history root, with its size. */
    fun diskBytes(): Long =
        root.walkTopDown().filter { it.isFile && it.name != "cleanup-log.jsonl" }.sumOf { it.length() }
}

/** Flips one byte of [file] at [offset]. */
internal fun corruptByte(
    file: File,
    offset: Long,
) {
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(offset)
        val b = raf.read()
        raf.seek(offset)
        raf.write(b xor 0x5A)
    }
}

/**
 * Case counts for the property tests. Every record syncs to disk, so the
 * default is sized for `check`; `-Pssmarkdown.heavyTests=true` runs ten
 * times as many for a soak before a release.
 */
internal object Soak {
    val heavy: Boolean = System.getProperty("ssmarkdown.heavyTests") == "true"

    fun cases(default: Int): Int = if (heavy) default * 10 else default
}
