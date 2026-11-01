package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.ui.unit.dp

/**
 * Size tokens (docs/quality-bar.md §3, "Touch targets"). Targets are
 * floors, not suggestions: when space runs out the strip scrolls and the
 * bars wrap, but a control never shrinks below its token.
 */
object SSDimens {
    /** The spacing grid. Every gap and padding is a multiple of this. */
    val grid = 4.dp

    val iconButton = 48.dp

    /** A pill is drawn 40 dp tall inside a 48 dp hit area. */
    val pill = 40.dp
    val pillHitArea = 48.dp

    val segment = 44.dp

    /** Strip and symbol keys. The strip scrolls; never shrink the keys. */
    val key = 48.dp

    val row = 44.dp
    val rowPrimary = 48.dp
    val chip = 40.dp

    /** Bar heights from the design canvas, before system insets. */
    val topBar = 56.dp
    val bottomBar = 64.dp

    val hairline = 1.dp
}
