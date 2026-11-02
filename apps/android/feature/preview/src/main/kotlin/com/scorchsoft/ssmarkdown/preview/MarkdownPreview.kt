package com.scorchsoft.ssmarkdown.preview

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The preview in Compose. The [view] is passed in rather than created here so
 * the host can keep one WebView alive across documents and layout changes
 * (Code ↔ Split ↔ Preview); this only places it.
 */
@Composable
fun MarkdownPreview(
    view: PreviewView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = {
            // A reused view may still be attached to the previous composition's parent.
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
    )
}

/** A [PreviewView] owned by this composition, released when it leaves. */
@Composable
fun rememberPreviewView(): PreviewView {
    val context = LocalContext.current
    val view = remember(context) { PreviewView(context) }
    DisposableEffect(view) { onDispose { view.release() } }
    return view
}
