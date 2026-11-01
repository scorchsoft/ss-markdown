package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorReducer
import com.scorchsoft.ssmarkdown.editor.preview.PreviewSession
import com.scorchsoft.ssmarkdown.preview.MarkdownPreview
import com.scorchsoft.ssmarkdown.preview.PreviewView
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewSettings
import kotlin.math.roundToInt

private const val PERCENT = 100f

/**
 * The real Preview pane: the Activity's one [PreviewView], placed here
 * and kept in step with the editor.
 *
 * - The view is attached to the [session] the first time the pane is
 *   shown, and stays attached while the Activity lives, so switching
 *   modes or rotating moves the same WebView (robustness §5a, rule 2).
 * - The theme's page colour is painted before anything loads, so a dark
 *   theme never flashes white.
 * - Pinch is observed, never consumed: the view reflows its own text and
 *   this reports the size so it is stored per file (ux-spec "Zoom").
 * - A paused renderer shows Retry and a way to Code instead of a blank pane.
 */
@Composable
fun PreviewPaneHost(
    state: PreviewHostState,
    view: PreviewView,
    session: PreviewSession,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(view) {
        session.attach(view)
        onDispose { }
    }
    // Landing on the anchor whenever the pane comes into view (a mode switch, a rotation into Split).
    LaunchedEffect(Unit) { session.scrollTo(state.anchorLine()) }
    state.look?.let { look ->
        LaunchedEffect(look) { view.setTheme(look.themeJson, look.dark, look.background, look.fonts) }
    }
    LaunchedEffect(state.tapToEdit, state.pageMode, state.noWrap, state.zoomPercent, state.readOnly) {
        view.applySettings(
            PreviewSettings(
                tapToEdit = state.tapToEdit,
                pageMode = state.pageMode,
                noWrap = state.noWrap,
                zoom = state.zoomPercent / PERCENT,
                readOnly = state.readOnly,
            ),
        )
    }
    val current = rememberUpdatedState(state)
    val background = state.look?.background?.let(::Color) ?: SS.colors.bg
    Box(modifier.fillMaxSize().background(background).observePinch(current)) {
        if (state.renderer == RendererState.PAUSED) {
            PausedPreview(state.onRetry, state.onShowCode)
        } else {
            MarkdownPreview(view, Modifier.fillMaxSize())
        }
    }
}

/**
 * Watches touches on the pane without consuming them: the first finger
 * makes this pane lead in Split, and two fingers report the pinch's zoom
 * (the same factor the view applies to its own text) while they move and
 * once more when they lift.
 */
private fun Modifier.observePinch(state: State<PreviewHostState>): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.value.onTouched()
            val start = state.value.zoomPercent
            var factor = 1f
            var pinched = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    factor *= event.calculateZoom()
                    pinched = true
                    state.value.onZoomChanged(percentOf(start, factor), true)
                }
            } while (event.changes.any { it.pressed })
            if (pinched) state.value.onZoomChanged(percentOf(start, factor), false)
        }
    }

private fun percentOf(
    start: Int,
    factor: Float,
): Int = (start * factor).roundToInt().coerceIn(EditorReducer.MIN_ZOOM, EditorReducer.MAX_ZOOM)

/** "Preview paused": the renderer died twice in a minute. Code still works, and says so. */
@Composable
private fun PausedPreview(
    onRetry: () -> Unit,
    onShowCode: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.preview_paused),
            style = SS.type.bodyStrong,
            color = SS.colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.preview_paused_body),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.preview_retry), color = SS.colors.ink) }
            OutlinedButton(
                onClick = onShowCode,
            ) { Text(stringResource(R.string.preview_use_code), color = SS.colors.ink) }
        }
    }
}
