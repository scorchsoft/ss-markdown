package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The strip's line icons, drawn on the signed-off mockups' 24-unit grid
 * with a 2-unit round stroke. Paths are kept as SVG path data so they can
 * be compared with the mockups line for line. Tinted by the caller, so the
 * stroke colour here is only a placeholder.
 */
internal object StripIcons {
    val bulletList by lazy {
        icon(
            "M9 6h11",
            "M9 12h11",
            "M9 18h11",
            dot(4.5f, 6f),
            dot(4.5f, 12f),
            dot(4.5f, 18f),
        )
    }

    /** The bullet icon's three lines, with 1 2 3 drawn in the same stroke where its dots are. */
    val numberedList by lazy {
        icon(
            "M10 6h10",
            "M10 12h10",
            "M10 18h10",
            "M4 4.5l1.5-1v5",
            "M3.5 10.5a1.5 1.5 0 0 1 3 0c0 1-3 2.2-3 3.5h3",
            "M3.5 16.2h3l-1.6 1.6a1.3 1.3 0 1 1-1.4 2",
        )
    }
    val task by lazy { icon(ROUNDED_SQUARE, "M8 12l3 3 5-6") }
    val code by lazy { icon("M8 7l-5 5 5 5", "M16 7l5 5-5 5") }
    val link by lazy {
        icon("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    }
    val indent by lazy { icon("M4 6h16", "M10 12h10", "M10 18h10", "M4 10l3 2-3 2") }
    val outdent by lazy { icon("M4 6h16", "M10 12h10", "M10 18h10", "M7 10l-3 2 3 2") }
    val table by lazy {
        icon("M6 5h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M4 10h16", "M10 5v14")
    }
    val image by lazy {
        icon(
            "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
            "M4 16l5-5 4 4 3-3 4 4",
            dot(15.5f, 8.5f),
        )
    }
    val quote by lazy { icon("M6 7h4v4H6z", "M10 11c0 3-1.5 5-4 6", "M14 7h4v4h-4z", "M18 11c0 3-1.5 5-4 6") }
    val undo by lazy { icon("M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3") }
    val redo by lazy { icon("M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3") }
    val cursorLeft by lazy { icon("M19 12H5", "M11 6l-6 6 6 6") }
    val cursorRight by lazy { icon("M5 12h14", "M13 6l6 6-6 6") }
    val done by lazy { icon("M5 12l5 5L20 7") }
    val prevCell by lazy { icon("M14 6l-6 6 6 6", "M20 6v12") }
    val nextCell by lazy { icon("M10 6l6 6-6 6", "M4 6v12") }
    val more by lazy { icon(dot(5f, 12f), dot(12f, 12f), dot(19f, 12f)) }
    val saved by lazy { icon("M5 12l5 5L20 7") }

    private const val ROUNDED_SQUARE = "M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z"
    private const val GRID = 24f
    private const val STROKE = 2f
    private const val DOT_RADIUS = 0.8f
    private const val DOT_DIAMETER = 2 * DOT_RADIUS

    /** A filled-looking dot: a tiny circle, which the round stroke fills in. */
    private fun dot(
        x: Float,
        y: Float,
    ): String =
        "M${x - DOT_RADIUS} ${y}a$DOT_RADIUS $DOT_RADIUS 0 1 0 $DOT_DIAMETER 0" +
            "a$DOT_RADIUS $DOT_RADIUS 0 1 0 -$DOT_DIAMETER 0"

    private fun icon(vararg paths: String): ImageVector {
        val builder =
            ImageVector.Builder(
                defaultWidth = GRID.dp,
                defaultHeight = GRID.dp,
                viewportWidth = GRID,
                viewportHeight = GRID,
            )
        for (d in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
