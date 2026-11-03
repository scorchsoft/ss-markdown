package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.runtime.State
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.settings.SplitTyping
import kotlin.math.roundToInt

// Split's layout (ux-spec "Split while typing"): what it shows, and where each part goes.

/** What stacked or side-by-side Split shows. */
internal enum class SplitShow {
    /** Both panes and the divider. */
    Both,

    /** Typing in Code: the Code pane fills the page. */
    CodeOnly,

    /** Typing in Preview: the Preview pane fills the page. */
    PreviewOnly,

    /** Too short to stack: Code, with Preview folded into its one-line strip. */
    CodeAndStrip,
}

/**
 * What Split shows (ux-spec "Split while typing"). While a pane is being
 * typed in ([typing]) and the setting asks for it, that pane fills the page;
 * side by side there is room for both, so both always stay. A window too
 * short to stack keeps Code and folds Preview into its strip, unless the
 * page is the one being typed in, which then fills the page whatever the
 * setting: it must never be folded away under the caret.
 */
internal fun splitShow(
    typing: Pane?,
    setting: SplitTyping,
    sideBySide: Boolean,
    tooShortToStack: Boolean,
): SplitShow =
    when {
        sideBySide -> SplitShow.Both
        typing == Pane.Preview && (setting == SplitTyping.EditedPane || tooShortToStack) -> SplitShow.PreviewOnly
        typing == Pane.Code && setting == SplitTyping.EditedPane -> SplitShow.CodeOnly
        tooShortToStack -> SplitShow.CodeAndStrip
        else -> SplitShow.Both
    }

/** The parts of Split's layout. */
internal enum class SplitPart { Code, Divider, Preview, Strip }

/** Which pane filled the page last; plain, because only the layout reads it. */
internal class FillingPane {
    var pane: Pane = Pane.Code
}

/**
 * Where Split's parts go along its main axis (top to bottom, or start to
 * end side by side), in pixels.
 *
 * Both panes keep their normal sizes ([fraction] of the room either side of
 * the divider). The pane being typed in ([filling]) grows towards the whole
 * page as [progress] goes from 0 to 1, and the other slides out of view at
 * its own size: it is never resized to nothing, so it keeps its scroll and
 * comes back where it was. Too short to stack, Code sits above the folded
 * [strip] and the page waits out of view below.
 */
@Suppress("LongParameterList") // The layout's inputs, each named at the one call site.
internal class SplitGeometry(
    private val main: Int,
    private val divider: Int,
    fraction: Float,
    show: SplitShow,
    private val filling: Pane,
    progress: Float,
    private val strip: Int = 0,
) {
    private val codeNormal: Int = ((main - divider).coerceAtLeast(0) * fraction).roundToInt()
    private val previewNormal: Int = (main - divider - codeNormal).coerceAtLeast(0)
    private val folded = show == SplitShow.CodeAndStrip

    private val codeSize: Int =
        when {
            folded -> (main - strip).coerceAtLeast(0)
            filling == Pane.Code -> lerp(codeNormal, main, progress)
            else -> codeNormal
        }
    private val previewSize: Int =
        if (!folded && filling == Pane.Preview) lerp(previewNormal, main, progress) else previewNormal

    private val dividerAt: Int =
        when {
            folded -> main
            filling == Pane.Preview -> main - previewSize - divider
            else -> codeSize
        }

    /** The size of [part] along the main axis (the strip measures itself). */
    fun size(part: SplitPart): Int =
        when (part) {
            SplitPart.Code -> codeSize
            SplitPart.Preview -> previewSize
            SplitPart.Divider -> divider
            SplitPart.Strip -> strip
        }

    /** Where [part] starts along the main axis. */
    fun position(part: SplitPart): Int =
        when (part) {
            SplitPart.Code -> if (!folded && filling == Pane.Preview) dividerAt - codeSize else 0
            SplitPart.Divider -> dividerAt
            SplitPart.Preview -> dividerAt + divider
            SplitPart.Strip -> main - strip
        }

    private companion object {
        fun lerp(
            from: Int,
            to: Int,
            t: Float,
        ): Int = from + ((to - from) * t).roundToInt()
    }
}

/**
 * Measures and places Split's parts (laid out by `layoutId`) with
 * [SplitGeometry]: the panes at fixed sizes along the main axis, the folded
 * strip at its own height. [progress] is read here, in the layout phase, so
 * the typing animation relayouts without recomposing.
 */
internal fun splitMeasurePolicy(
    sideBySide: Boolean,
    fraction: Float,
    show: SplitShow,
    last: FillingPane,
    progress: State<Float>,
): MeasurePolicy =
    MeasurePolicy { measurables, constraints ->
        val cross = if (sideBySide) constraints.maxHeight else constraints.maxWidth
        // The folded strip (stacked only) measures its own height across the page.
        val strip =
            measurables
                .firstOrNull { it.layoutId == SplitPart.Strip }
                ?.measure(Constraints(minWidth = cross, maxWidth = cross))
        val geometry =
            SplitGeometry(
                main = if (sideBySide) constraints.maxWidth else constraints.maxHeight,
                divider = DividerHandle.roundToPx(),
                fraction = fraction,
                show = show,
                filling = last.pane,
                progress = progress.value,
                strip = strip?.height ?: 0,
            )
        val panes =
            measurables.filter { it.layoutId != SplitPart.Strip }.map { m ->
                val part = m.layoutId as SplitPart
                val size = geometry.size(part)
                part to m.measure(if (sideBySide) Constraints.fixed(size, cross) else Constraints.fixed(cross, size))
            }
        layout(constraints.maxWidth, constraints.maxHeight) {
            panes.forEach { (part, placeable) ->
                val at = geometry.position(part)
                if (sideBySide) placeable.place(at, 0) else placeable.place(0, at)
            }
            strip?.place(0, geometry.position(SplitPart.Strip))
        }
    }
