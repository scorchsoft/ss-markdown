package com.scorchsoft.ssmarkdown.history.journal

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Where a [JournalWriter] puts its bytes. A seam, so tests can count
 * fsyncs and inject a disk that fails halfway through a write.
 */
internal interface JournalSink : Closeable {
    /** Current length of the file in bytes. */
    val size: Long

    /** Appends `bytes[0 until length]` at [size]. May write part of them and then throw. */
    fun write(
        bytes: ByteArray,
        length: Int,
    )

    /** Makes everything written so far durable: `fsync`. */
    fun force()

    /** Cuts the file back to [size] bytes, discarding a partial write. */
    fun truncate(size: Long)

    /** Opens a sink on a file. */
    fun interface Factory {
        /** Opens [file], which already exists, positioned at its end. */
        fun open(file: File): JournalSink
    }
}

/**
 * The real sink: a [FileChannel] opened for writing (not `APPEND`, which
 * would stop [truncate] from repositioning the channel).
 */
internal class FileChannelSink(
    file: File,
) : JournalSink {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)

    init {
        channel.position(channel.size())
    }

    override val size: Long get() = channel.size()

    override fun write(
        bytes: ByteArray,
        length: Int,
    ) {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    // force(true) also syncs metadata. The file length is metadata, and
    // an appended record is useless if the length that covers it is lost.
    override fun force() = channel.force(true)

    override fun truncate(size: Long) {
        channel.truncate(size)
        channel.position(size)
    }

    override fun close() = channel.close()

    /** Opens a [FileChannelSink]; the default everywhere but tests. */
    companion object : JournalSink.Factory {
        override fun open(file: File): JournalSink = FileChannelSink(file)
    }
}
