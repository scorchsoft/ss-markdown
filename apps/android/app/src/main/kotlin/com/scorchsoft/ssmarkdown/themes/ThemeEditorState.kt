package com.scorchsoft.ssmarkdown.themes

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.ThemeSize
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import com.scorchsoft.ssmarkdown.theme.codec.ThemeDecodeResult
import com.scorchsoft.ssmarkdown.theme.contrast.ContrastCheck
import com.scorchsoft.ssmarkdown.theme.contrast.ContrastReport
import com.scorchsoft.ssmarkdown.theme.contrast.withContrastFixed
import kotlin.math.roundToLong

/** The theme editor's tabs (mockup ThemeEditor). */
enum class ThemeTab { Type, Colours, Spacing, Json }

/** The three font roles a theme sets. */
enum class FontRole { Body, Heading, Code }

/**
 * The theme editor (ux-spec "Themes"; mockups ThemeEditor and MoreStates).
 *
 * @property theme the theme as edited: always valid, because every edit is checked before it lands.
 * @property savedId the id it is stored under, if it has been saved (a rename changes the id).
 * @property json the JSON tab's text, which may be mid-edit and invalid.
 * @property problems why the last edit was refused, each pinned to a line and key where possible.
 * @property contrast the pairs that fall short of AA, with a Fix it.
 * @property banner an import that failed, in the mockup's words.
 * @property dirty edits not yet saved with "Use this theme".
 * @property saveFailed the last "Use this theme" could not write the file.
 */
@Immutable
data class ThemeEditorUiState(
    val theme: Theme = BuiltInThemes.DEFAULT,
    val savedId: String? = null,
    val tab: ThemeTab = ThemeTab.Type,
    val variant: ThemeVariant = ThemeVariant.LIGHT,
    val json: String = "",
    val problems: List<String> = emptyList(),
    val contrast: List<ContrastCheck> = emptyList(),
    val banner: String? = null,
    val dirty: Boolean = false,
    val editingCss: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** The built-in this theme inherits from, for "Custom · based on Reader". */
    val baseName: String get() = BuiltInThemes.byId(theme.base ?: "")?.name ?: BuiltInThemes.DEFAULT.name
}

/** What the theme editor can do. */
sealed interface ThemeEditorAction {
    data class SelectTab(
        val tab: ThemeTab,
    ) : ThemeEditorAction

    data class SelectVariant(
        val variant: ThemeVariant,
    ) : ThemeEditorAction

    data class Rename(
        val name: String,
    ) : ThemeEditorAction

    /** A colour typed as `#RRGGBB` or `#RGB`; anything else is refused with a message. */
    data class SetColor(
        val key: PaletteKey,
        val hex: String,
    ) : ThemeEditorAction

    data class SetFont(
        val role: FontRole,
        val family: String,
    ) : ThemeEditorAction

    /** Base size in px steps. */
    data class StepBase(
        val steps: Int,
    ) : ThemeEditorAction

    /** Heading scale in 0.05 steps. */
    data class StepScale(
        val steps: Int,
    ) : ThemeEditorAction

    /** Line height in 0.05 steps. */
    data class StepLineHeight(
        val steps: Int,
    ) : ThemeEditorAction

    /** The JSON tab's text changed; it applies as soon as it is a valid theme. */
    data class EditJson(
        val text: String,
    ) : ThemeEditorAction

    data class EditCss(
        val css: String,
    ) : ThemeEditorAction

    data class ShowCss(
        val show: Boolean,
    ) : ThemeEditorAction

    /** "Fix it": nudge each failing colour to the nearest one that passes AA. */
    data object FixContrast : ThemeEditorAction

    data class PickFont(
        val role: FontRole,
    ) : ThemeEditorAction

    data object Import : ThemeEditorAction

    data object Export : ThemeEditorAction

    data object Duplicate : ThemeEditorAction

    /** "Use this theme": save it and make it the preview theme. */
    data object Use : ThemeEditorAction

    data object DismissBanner : ThemeEditorAction

    data object Back : ThemeEditorAction
}

/**
 * The editor's pure transitions. Every edit builds a candidate theme and
 * runs it through [ThemeCodec] (encode, then decode), the same validator
 * an imported file meets, so the editor can never hold a theme that
 * would not survive Export and Import. A refused edit leaves the theme
 * as it was and says why.
 */
@Suppress("TooManyFunctions") // One small, validated edit per field.
object ThemeEditorReducer {
    private const val SCALE_STEP = 0.05
    private const val HUNDREDTHS = 100.0

    /** A fresh editor on [theme] (already a custom theme), stored under [savedId] if saved before. */
    fun start(
        theme: Theme,
        savedId: String?,
    ): ThemeEditorUiState {
        // Through the codec once, so a fresh copy gets its custom id and every field is normalised.
        val normalised = (ThemeCodec.decode(ThemeCodec.encode(theme)) as? ThemeDecodeResult.Decoded)?.theme ?: theme
        return settle(ThemeEditorUiState(savedId = savedId), normalised, dirty = false)
    }

    /** A custom copy of [source], named "<name> copy" and based on its base (or itself, for a built-in). */
    fun copyOf(source: Theme): Theme = source.copy(name = "${source.name} copy", base = source.base ?: source.id)

    /** [state] after [action]. Actions with effects (import, save, fonts) return it unchanged. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table: one short branch per action.
    fun reduce(
        state: ThemeEditorUiState,
        action: ThemeEditorAction,
    ): ThemeEditorUiState {
        val t = state.theme
        return when (action) {
            is ThemeEditorAction.SelectTab -> {
                state.copy(tab = action.tab, json = ThemeCodec.encode(t), problems = emptyList())
            }

            is ThemeEditorAction.SelectVariant -> {
                state.copy(variant = action.variant)
            }

            is ThemeEditorAction.Rename -> {
                edit(state, t.copy(name = action.name.trim().ifEmpty { t.name }))
            }

            is ThemeEditorAction.SetColor -> {
                setColor(state, action)
            }

            is ThemeEditorAction.SetFont -> {
                edit(state, withFont(t, action.role, action.family))
            }

            is ThemeEditorAction.StepBase -> {
                edit(
                    state,
                    t.copy(
                        size =
                            t.size.copy(
                                base =
                                    clampBase(t.size.base + action.steps),
                            ),
                    ),
                )
            }

            is ThemeEditorAction.StepScale -> {
                edit(state, t.copy(size = t.size.copy(headingScale = stepScale(t.size, action.steps))))
            }

            is ThemeEditorAction.StepLineHeight -> {
                edit(state, t.copy(size = t.size.copy(lineHeight = stepLine(t.size, action.steps))))
            }

            is ThemeEditorAction.EditJson -> {
                editJson(state, action.text)
            }

            is ThemeEditorAction.EditCss -> {
                edit(state, t.copy(css = action.css))
            }

            is ThemeEditorAction.ShowCss -> {
                state.copy(editingCss = action.show)
            }

            ThemeEditorAction.FixContrast -> {
                edit(state, t.withContrastFixed())
            }

            ThemeEditorAction.DismissBanner -> {
                state.copy(banner = null, saveFailed = false)
            }

            else -> {
                state
            }
        }
    }

    /** An imported file: it replaces the theme being edited, or explains in one line why it can't. */
    fun imported(
        state: ThemeEditorUiState,
        fileName: String,
        text: String,
    ): ThemeEditorUiState =
        when (val result = ThemeCodec.decode(text)) {
            is ThemeDecodeResult.Decoded -> settle(state.copy(banner = null), result.theme, dirty = true)
            is ThemeDecodeResult.Invalid -> state.copy(banner = result.headline(fileName))
        }

    private fun setColor(
        state: ThemeEditorUiState,
        action: ThemeEditorAction.SetColor,
    ): ThemeEditorUiState {
        val color =
            ThemeColor.parse(action.hex)
                ?: return state.copy(problems = listOf("“${action.key.json}” isn't a colour: use #RRGGBB"))
        val t = state.theme
        val edited =
            when (state.variant) {
                ThemeVariant.LIGHT -> t.copy(light = t.light.with(action.key, color))
                ThemeVariant.DARK -> t.copy(dark = t.dark.with(action.key, color))
            }
        return edit(state, edited)
    }

    private fun editJson(
        state: ThemeEditorUiState,
        text: String,
    ): ThemeEditorUiState =
        when (val result = ThemeCodec.decode(text)) {
            is ThemeDecodeResult.Decoded -> settle(state, result.theme, dirty = true).copy(json = text)
            is ThemeDecodeResult.Invalid -> state.copy(json = text, problems = result.problems.map { it.describe() })
        }

    /** Validates [candidate] through the codec; on success it becomes the theme. */
    private fun edit(
        state: ThemeEditorUiState,
        candidate: Theme,
    ): ThemeEditorUiState =
        when (val result = ThemeCodec.decode(ThemeCodec.encode(candidate))) {
            is ThemeDecodeResult.Decoded -> settle(state, result.theme, dirty = true)
            is ThemeDecodeResult.Invalid -> state.copy(problems = result.problems.map { it.describe() })
        }

    private fun settle(
        state: ThemeEditorUiState,
        theme: Theme,
        dirty: Boolean,
    ): ThemeEditorUiState =
        state.copy(
            theme = theme,
            json = ThemeCodec.encode(theme),
            problems = emptyList(),
            contrast = ContrastReport.of(theme).failures,
            dirty = state.dirty || dirty,
        )

    private fun withFont(
        theme: Theme,
        role: FontRole,
        family: String,
    ): Theme =
        when (role) {
            FontRole.Body -> theme.copy(fonts = theme.fonts.copy(body = family))
            FontRole.Heading -> theme.copy(fonts = theme.fonts.copy(heading = family))
            FontRole.Code -> theme.copy(fonts = theme.fonts.copy(code = family))
        }

    private fun clampBase(base: Double): Double = base.coerceIn(ThemeSize.BASE_RANGE)

    private fun stepScale(
        size: ThemeSize,
        steps: Int,
    ): Double = round2(size.headingScale + steps * SCALE_STEP).coerceIn(ThemeSize.HEADING_SCALE_RANGE)

    private fun stepLine(
        size: ThemeSize,
        steps: Int,
    ): Double = round2(size.lineHeight + steps * SCALE_STEP).coerceIn(ThemeSize.LINE_HEIGHT_RANGE)

    private fun round2(value: Double): Double = (value * HUNDREDTHS).roundToLong() / HUNDREDTHS
}
