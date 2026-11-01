package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import com.scorchsoft.ssmarkdown.theme.codec.ThemeDecodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * The preview themes on this phone: the four built-ins, then the user's
 * custom themes, each kept as its own JSON file (the same format as
 * Export, so a file in [dir] is exactly what a person could import).
 *
 * Custom themes load lazily on first use, never on the launch path. A
 * file that no longer decodes is skipped, not deleted: it may be from a
 * newer version of the app.
 */
class ThemeLibrary(
    private val dir: File,
) {
    private val customs = MutableStateFlow<List<Theme>?>(null)

    /** Built-ins first, then custom themes by name. */
    val themes: StateFlow<List<Theme>?> get() = customs.asStateFlow()

    /** Every theme, loading custom ones if they have not been read yet. */
    fun all(): List<Theme> = BuiltInThemes.ALL + loaded()

    /** The theme with [id], or the default when it is gone (deleted, or from another install). */
    fun byId(id: String): Theme =
        // A built-in never touches the disk: the common case stays off the launch path's I/O.
        BuiltInThemes.byId(id) ?: loaded().firstOrNull { it.id == id } ?: BuiltInThemes.DEFAULT

    /** Saves [theme] as a custom theme, replacing one with the same id. False if it could not be written. */
    fun save(theme: Theme): Boolean {
        require(BuiltInThemes.byId(theme.id) == null) { "built-in themes are read-only" }
        val written =
            try {
                dir.mkdirs()
                val temp = File(dir, "${theme.id}.json.tmp")
                temp.writeText(ThemeCodec.encode(theme))
                temp.renameTo(File(dir, "${theme.id}.json"))
            } catch (_: IOException) {
                false
            }
        if (written) customs.value = (loaded().filterNot { it.id == theme.id } + theme).sortedBy { it.name.lowercase() }
        return written
    }

    /** Removes the custom theme [id]. */
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        customs.value = loaded().filterNot { it.id == id }
    }

    private fun loaded(): List<Theme> = customs.value ?: read().also { customs.value = it }

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
}
