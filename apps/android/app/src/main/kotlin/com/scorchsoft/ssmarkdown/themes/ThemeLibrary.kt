package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import com.scorchsoft.ssmarkdown.theme.codec.ThemeDecodeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The preview themes on this phone: the four built-ins, then the user's
 * custom themes, each kept as its own JSON file (the same format as
 * Export, so a file in [dir] is exactly what a person could import).
 *
 * No method touches the disk on the caller's thread (performance review
 * PERF-C): custom themes are read ahead on [io] when the library is
 * built, and [themes] publishes them when they land; until then [all]
 * and [byId] answer from the built-ins. [save] and [delete] suspend onto
 * [io]. A file that no longer decodes is skipped, not deleted: it may be
 * from a newer version of the app.
 */
class ThemeLibrary(
    private val dir: File,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
) {
    private val customs = MutableStateFlow<List<Theme>?>(null)

    /** The custom themes once read (null until then), by name. */
    val themes: StateFlow<List<Theme>?> get() = customs.asStateFlow()

    init {
        scope.launch(io) { loaded() }
    }

    /** Built-ins first, then the custom themes read so far. */
    fun all(): List<Theme> = BuiltInThemes.ALL + customs.value.orEmpty()

    /** The theme with [id], or the default when it is gone (deleted, from another install, or not read yet). */
    fun byId(id: String): Theme =
        BuiltInThemes.byId(id) ?: customs.value?.firstOrNull { it.id == id } ?: BuiltInThemes.DEFAULT

    /** Saves [theme] as a custom theme, replacing one with the same id. False if it could not be written. */
    suspend fun save(theme: Theme): Boolean {
        require(BuiltInThemes.byId(theme.id) == null) { "built-in themes are read-only" }
        return withContext(io) {
            val written =
                try {
                    dir.mkdirs()
                    val temp = File(dir, "${theme.id}.json.tmp")
                    temp.writeText(ThemeCodec.encode(theme))
                    temp.renameTo(File(dir, "${theme.id}.json"))
                } catch (_: IOException) {
                    false
                }
            if (written) {
                val others = loaded().filterNot { it.id == theme.id }
                customs.value = (others + theme).sortedBy { it.name.lowercase() }
            }
            written
        }
    }

    /**
     * [theme], renamed "<name> (2)", "(3)"… if another custom theme already
     * has its id. A custom theme's id comes from its name, so an imported
     * theme, a second copy or a rename that matched another theme's name
     * would otherwise replace that theme silently on save (SEC-L1).
     * [ownId] is the id [theme] is already stored under, if any: saving
     * over itself is not a clash. Suspends onto [io]: it may read the
     * custom themes.
     */
    suspend fun withFreeName(
        theme: Theme,
        ownId: String?,
    ): Theme =
        withContext(io) {
            val taken = loaded().map { it.id }.toSet() - setOfNotNull(ownId)
            if (theme.id !in taken) return@withContext theme
            generateSequence(2) { it + 1 }
                .take(MAX_RENAME_TRIES)
                .mapNotNull { renamed(theme, " ($it)") }
                .firstOrNull { it.id !in taken } ?: theme
        }

    /** Removes the custom theme [id]. */
    suspend fun delete(id: String) {
        withContext(io) {
            File(dir, "$id.json").delete()
            customs.value = loaded().filterNot { it.id == id }
        }
    }

    /** [theme] with [suffix] on its name and the id that name gives, through the codec that derives it. */
    private fun renamed(
        theme: Theme,
        suffix: String,
    ): Theme? {
        val name = theme.name.take(MAX_NAME_CHARS - suffix.length) + suffix
        return (ThemeCodec.decode(ThemeCodec.encode(theme.copy(name = name))) as? ThemeDecodeResult.Decoded)?.theme
    }

    /** On [io] only. */
    private fun loaded(): List<Theme> = customs.value ?: read().also { customs.compareAndSet(null, it) }

    private fun read(): List<Theme> =
        dir
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                val text =
                    try {
                        file.readText()
                    } catch (_: IOException) {
                        return@mapNotNull null
                    }
                (ThemeCodec.decode(text) as? ThemeDecodeResult.Decoded)?.theme
            }.sortedBy { it.name.lowercase() }

    private companion object {
        /** The codec's longest theme name. */
        const val MAX_NAME_CHARS = 60

        /** Far more same-named themes than anyone keeps; past it, saving replaces as before. */
        const val MAX_RENAME_TRIES = 1_000
    }
}
