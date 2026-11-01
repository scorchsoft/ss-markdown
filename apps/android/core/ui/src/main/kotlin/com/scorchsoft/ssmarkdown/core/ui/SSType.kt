package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The UI type ladder: 11 / 12 / 13 / 15 / 18 / 26 sp (docs/quality-bar.md
 * §3). Six sizes and no more; weight varies, size does not.
 *
 * The family is the system sans for now. Inter and JetBrains Mono are
 * bundled in iteration 2, when the editor first renders code; swapping
 * [sans] and [mono] is then the whole change.
 */
object SSType {
    private val sans = FontFamily.Default
    private val mono = FontFamily.Monospace

    /** 11 sp: line numbers, badges. */
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)

    /** 12 sp: the status line under the title. */
    val meta = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)

    /** 13 sp: segments, pills and buttons. */
    val label = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)

    /** 15 sp: body copy, menu rows. */
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)

    /** 15 sp semibold: the document title in the top bar. */
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)

    /** 18 sp: sheet and dialog titles. */
    val title = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)

    /** 26 sp: the one large heading a screen may have. */
    val display =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.01).em,
        )

    /** Source text: 12.5 sp at a 1.6 line height. */
    val code = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 20.sp)
}
