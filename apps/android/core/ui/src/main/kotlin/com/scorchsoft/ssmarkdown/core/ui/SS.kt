package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** `SS.colors` / `SS.type` / `SS.dimens`: the design-system entry point. */
object SS {
    val colors: SSColors
        @Composable @ReadOnlyComposable
        get() = LocalSSColors.current
    val type: SSType get() = SSType
    val dimens: SSDimens get() = SSDimens
}
