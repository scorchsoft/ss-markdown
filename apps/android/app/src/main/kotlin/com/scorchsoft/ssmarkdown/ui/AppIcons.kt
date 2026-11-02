package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, as SVG path data on the mockups' 24-unit grid
 * with a 2-unit round stroke (the same drawing rules as the strip's
 * icons). No icon library: a dozen paths are cheaper than a dependency.
 */
object AppIcons {
    /**
     * Two equal sheets, the back one showing as an L: same 2-unit corners,
     * 4 units apart on both axes, and the L stops 4 units short of the front
     * sheet's far edge each way, so it reads as one sheet behind another.
     */
    val copy by lazy {
        icon("M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M4 16V6a2 2 0 0 1 2-2h10")
    }
    val more by lazy { icon(dot(12f, 5f), dot(12f, 12f), dot(12f, 19f)) }
    val check by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M8 12.5l3 3 5-6") }
    val menu by lazy { icon("M4 6h16", "M4 12h16", "M4 18h16") }
    val close by lazy { icon("M6 6l12 12", "M18 6L6 18") }
    val back by lazy { icon("M19 12H5", "M11 6l-6 6 6 6") }
    val chevronDown by lazy { icon("M6 9l6 6 6-6") }
    val chevronUp by lazy { icon("M6 15l6-6 6 6") }
    val chevronRight by lazy { icon("M9 6l6 6-6 6") }
    val code by lazy { icon("M8 7l-5 5 5 5", "M16 7l5 5-5 5") }
    val split by lazy { icon("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M4 12h16") }
    val preview by lazy {
        icon(
            "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        )
    }
    val search by lazy { icon("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4") }
    val reset by lazy { icon("M4 12a8 8 0 1 0 2.3-5.7", "M4 4v4h4") }

    /** Save as…: a page with a plus, for the large-font Save-as pill (UX2-1). */
    val saveAs by lazy {
        icon("M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M12 11v6", "M9 14h6")
    }

    /** Caution: a triangle with a bang (UX2-6: tone is never told by colour alone). */
    val caution by lazy {
        icon(
            "M10.3 4.2L2.6 18a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z",
            "M12 10v4",
            "M12 17.5h.01",
        )
    }

    /** Error: an octagon with a bang, the stop sign's shape. */
    val error by lazy {
        icon(
            "M8.3 2.5h7.4l5.8 5.8v7.4l-5.8 5.8H8.3l-5.8-5.8V8.3z",
            "M12 7.5v5.5",
            "M12 16.5h.01",
        )
    }

    val info by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 11v5", "M12 8h.01") }
    val swap by lazy { icon("M4 8h14", "M15 5l3 3-3 3", "M20 16H6", "M9 13l-3 3 3 3") }
    val folder by lazy { icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val file by lazy { icon("M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M14 3v5h5") }
    val pin by lazy { icon("M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7") }
    val arrowUp by lazy { icon("M12 19V5", "M6 11l6-6 6 6") }

    private const val GRID = 24f
    private const val STROKE = 2f
    private const val DOT_RADIUS = 0.9f
    private const val DOT_DIAMETER = 2 * DOT_RADIUS

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
