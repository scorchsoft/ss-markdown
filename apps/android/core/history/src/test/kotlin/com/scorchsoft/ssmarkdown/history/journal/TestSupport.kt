package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** A clock that only moves when a test says so. */
internal class FakeClock(
    var now: Long = 1_700_000_000_000,
) : JournalClock {
    override fun nowMillis(): Long = now
}

/** Runs scheduled tasks when [advanceBy] moves the [clock] past their time. No threads. */
internal class FakeScheduler(
    private val clock: FakeClock,
) : FlushScheduler {
    private class Task(
        val dueAt: Long,
        val task: Runnable,
    ) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    val pending: Int get() = tasks.count { !it.cancelled }

    override fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Cancellable {
        val entry = Task(clock.now + delayMillis, task)
        tasks += entry
        return Cancellable { entry.cancelled = true }
    }

    fun advanceBy(millis: Long) {
        val end = clock.now + millis
        while (true) {
            val next = tasks.filter { !it.cancelled && it.dueAt <= end }.minByOrNull { it.dueAt } ?: break
            tasks.remove(next)
            clock.now = maxOf(clock.now, next.dueAt)
            next.task.run()
        }
        tasks.removeAll { it.cancelled }
        clock.now = end
    }
}

/**
 * Real files underneath, with counters and a fault switch on top: the
 * next write lands [failAfterBytes] bytes and then throws, and
 * [failTruncate] makes the clean-up fail too.
 */
internal class InstrumentedSinks : JournalSink.Factory {
    var forces = 0
    var writes = 0
    var failAfterBytes: Int? = null
    var failTruncate = false

    override fun open(file: File): JournalSink =
        object : JournalSink {
            private val real = FileChannelSink(file)
            override val size: Long get() = real.size

            override fun write(
                bytes: ByteArray,
                length: Int,
            ) {
                writes++
                val limit = failAfterBytes
                if (limit != null) {
                    failAfterBytes = null
                    real.write(bytes, minOf(limit, length))
                    throw IOException("injected: disk full")
                }
                real.write(bytes, length)
            }

            override fun force() {
                forces++
                real.force()
            }

            override fun truncate(size: Long) {
                if (failTruncate) throw IOException("injected: truncate refused")
                real.truncate(size)
            }

            override fun close() = real.close()
        }
}

/** A store on a fake clock and instrumented sinks, plus the knobs tests turn. */
internal class TestStore(
    root: File,
    policy: FlushPolicy = IdleFlushPolicy(),
) {
    val clock = FakeClock()
    val scheduler = FakeScheduler(clock)
    val sinks = InstrumentedSinks()
    val store = JournalStore(root, FlushTiming(clock, scheduler, policy), sinks)

    fun start(
        key: String,
        base: BaseFingerprint,
    ): JournalWriter = (store.start(key, base) as OpenResult.Opened).writer
}

internal fun fingerprintOf(
    text: String,
    lastModified: Long = 1_000L,
): BaseFingerprint = BaseFingerprint.of(text.toByteArray(Charsets.UTF_8), lastModified)

/** Applies [edit] the way replay must. */
internal fun String.applying(edit: TextEdit): String =
    substring(0, edit.offset) + edit.inserted + substring(edit.offset + edit.deleted.length)

/**
 * Random text built to hurt: CRLF, emoji (surrogate pairs), lone
 * surrogates, combining marks, CJK, NUL, and now and then a large block.
 */
internal object Hostile {
    private val pieces =
        listOf(
            "a",
            "b",
            "z",
            " ",
            "#",
            "*",
            "\n",
            "\r\n",
            "\r",
            "\t",
            "\u0000",
            "é",
            "é",
            "中文",
            "😀",
            "👩‍💻",
            "\uD83D",
            "\uDE00",
            "􏿿",
            "",
            "```",
            "- [ ] ",
        )

    fun text(
        random: Random,
        maxPieces: Int,
    ): String =
        buildString {
            repeat(random.nextInt(maxPieces + 1)) { append(pieces[random.nextInt(pieces.size)]) }
        }

    fun edit(
        random: Random,
        current: String,
        allowLarge: Boolean = true,
    ): TextEdit {
        val offset = random.nextInt(current.length + 1)
        val deleteLength = random.nextInt(minOf(current.length - offset, MAX_DELETE) + 1)
        val inserted =
            if (allowLarge && random.nextInt(LARGE_ONE_IN) == 0) {
                text(random, maxPieces = 4).repeat(LARGE_REPEAT) + "x"
            } else {
                text(random, maxPieces = 6)
            }
        return TextEdit(offset, current.substring(offset, offset + deleteLength), inserted)
    }

    private const val MAX_DELETE = 40
    private const val LARGE_ONE_IN = 40
    private const val LARGE_REPEAT = 4_000
}
