package com.scorchsoft.ssmarkdown.fonts.store

import java.io.File
import java.security.MessageDigest

/** SHA-256 as lower-case hex: the integrity record for every installed file. */
internal object Sha256 {
    private const val BUFFER = 64 * 1024

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    fun of(file: File): String {
        val digest = newDigest()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest)
    }
}
