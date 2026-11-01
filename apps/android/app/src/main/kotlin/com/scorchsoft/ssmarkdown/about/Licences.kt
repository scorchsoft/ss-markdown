package com.scorchsoft.ssmarkdown.about

/**
 * One bundled component on the Open-source licences screen.
 *
 * @property source where it comes from, shown as text (the app is offline; nothing is fetched).
 */
data class Licence(
    val name: String,
    val licence: String,
    val source: String,
)

/** Reads `assets/licences.txt`: one `name | licence | source` per line, `#` for comments. */
object Licences {
    /** The entries in [text]; malformed lines are skipped rather than failing the screen. */
    fun parse(text: String): List<Licence> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                if (parts.size == FIELDS) Licence(parts[0], parts[1], parts[2]) else null
            }.toList()

    private const val FIELDS = 3
}
