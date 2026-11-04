package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * True once the keyboard has gone after the menu was asked for, or after
 * [KEYBOARD_WAIT_MILLIS] if it has not. Opening ⋮ puts the keyboard away;
 * shown at once, the menu was laid out in the room above the leaving
 * keyboard and stayed that small until touched (Andrew, 2026-11-04). It
 * waits for the room instead and opens once, at its full size. A keyboard
 * that stays (a hardware keyboard's bar, a slow IME) only delays it by the
 * wait, and the rows are then held above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun keyboardGoneOrWaited(expanded: Boolean): Boolean {
    val imeUp = WindowInsets.isImeVisible
    var waited by remember(expanded) { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(KEYBOARD_WAIT_MILLIS)
            waited = true
        }
    }
    // Once open it stays open: a keyboard that comes back only shortens the rows.
    var opened by remember(expanded) { mutableStateOf(false) }
    if (!imeUp || waited) opened = true
    return opened
}

/** The longest the menu waits for the keyboard: about its slide-out animation. */
private const val KEYBOARD_WAIT_MILLIS = 400L
