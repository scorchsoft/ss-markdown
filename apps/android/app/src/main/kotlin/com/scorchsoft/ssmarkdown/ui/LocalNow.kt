package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * "Now", for relative times ("Saved · 2 min ago"). Screens read it rather
 * than the system clock, so tests and screenshots see a fixed time and
 * only the running app ticks (see [TickingNow]).
 */
val LocalNow = staticCompositionLocalOf { System.currentTimeMillis() }

/** Provides a [LocalNow] that advances every 30 s while [content] is shown. */
@Composable
fun TickingNow(
    clock: () -> Long,
    content: @Composable () -> Unit,
) {
    val now by produceState(clock()) {
        while (true) {
            delay(TICK_MILLIS)
            value = clock()
        }
    }
    CompositionLocalProvider(LocalNow provides now, content = content)
}

private const val TICK_MILLIS = 30_000L
