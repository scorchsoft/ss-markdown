package com.scorchsoft.ssmarkdown.fontpicker

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.fonts.FontCategory
import com.scorchsoft.ssmarkdown.themes.FontRole

/** Where a font in the picker comes from. */
enum class FontOrigin { BuiltIn, Downloaded, Imported, GoogleFonts }

/**
 * One row of the font picker.
 *
 * @property styles how many weights and slopes it has; null when unknown (imports).
 * @property approxKb the download's rough size, for Google Fonts rows.
 */
@Immutable
data class FontOption(
    val family: String,
    val category: FontCategory?,
    val origin: FontOrigin,
    val styles: Int? = null,
    val approxKb: Int? = null,
)

/**
 * The font picker (mockup FontPicker): what is on this phone, then Google
 * Fonts to download, filtered by search and category.
 *
 * @property role which of the theme's fonts is being chosen.
 * @property themeName the theme being edited, for the header.
 * @property current the family the role has now: its row is marked.
 * @property downloading families downloading now, with progress 0–1.
 * @property problem the last download or import failure, in one sentence with what is used meanwhile.
 */
@Immutable
data class FontPickerUiState(
    val role: FontRole = FontRole.Body,
    val themeName: String = "",
    val current: String = "",
    val query: String = "",
    val category: FontCategory? = null,
    val onPhone: List<FontOption> = emptyList(),
    val google: List<FontOption> = emptyList(),
    val downloading: Map<String, Float> = emptyMap(),
    val problem: String? = null,
)

/** What the font picker can do. */
sealed interface FontPickerAction {
    data class Search(
        val query: String,
    ) : FontPickerAction

    /** A category chip; null is All. */
    data class Filter(
        val category: FontCategory?,
    ) : FontPickerAction

    /** A font on this phone: use it. */
    data class Choose(
        val family: String,
    ) : FontPickerAction

    /** "Get": download a Google Fonts family, then use it. */
    data class Download(
        val family: String,
    ) : FontPickerAction

    data class Cancel(
        val family: String,
    ) : FontPickerAction

    /** "Or import a .ttf / .otf". */
    data object Import : FontPickerAction

    data object DismissProblem : FontPickerAction

    data object Back : FontPickerAction
}

/** The picker's one-shot effects. */
sealed interface FontPickerEvent {
    /** The system picker for a font file. */
    data object PickFile : FontPickerEvent

    /** Chosen, or Back. */
    data object Close : FontPickerEvent
}
