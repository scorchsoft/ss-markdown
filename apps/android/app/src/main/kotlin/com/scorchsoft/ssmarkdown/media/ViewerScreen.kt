package com.scorchsoft.ssmarkdown.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen
import kotlin.math.roundToInt

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_STEP = 1.5f
private const val PERCENT = 100
private val ThumbSize = 56.dp
private val ChipShape = RoundedCornerShape(20.dp)

/**
 * The image and diagram viewer (ux-spec "Viewer"; mockup Viewer): its own
 * pinch, pan and double-tap zoom, separate from the preview's text zoom;
 * a "250% · Fit" chip; Save and Share in the header; and a filmstrip of
 * every image in the document ("2 of 5").
 */
@Composable
fun ViewerScreen(
    state: ViewerUiState,
    onAction: (ViewerAction) -> Unit,
) {
    val item = state.current
    val kind = if (item?.type == MediaType.Diagram) R.string.viewer_diagram else R.string.viewer_picture
    SecondaryScreen(
        title = stringResource(R.string.viewer_title, stringResource(kind), state.index + 1, state.items.size),
        onBack = { onAction(ViewerAction.Back) },
        scroll = false,
        actions = {
            TextButton(onClick = { onAction(ViewerAction.Save) }) {
                Text(stringResource(R.string.viewer_save), style = SS.type.label, color = SS.colors.ink)
            }
            TextButton(onClick = { onAction(ViewerAction.Share) }) {
                Text(stringResource(R.string.viewer_share), style = SS.type.label, color = SS.colors.ink)
            }
        },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().background(SS.colors.chrome)) {
            ZoomableImage(state.images[state.index], state.index, item?.title.orEmpty())
        }
        Filmstrip(state, onAction)
    }
}

@Composable
private fun ZoomableImage(
    image: ViewerImage?,
    key: Int,
    description: String,
) {
    var scale by remember(key) { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var size by remember(key) { mutableStateOf(IntSize.Zero) }
    val transform =
        rememberTransformableState { centroid, zoom, pan, _ ->
            val centre = Offset(size.width / 2f, size.height / 2f)
            val next = PinchZoom(scale, offset).zoomed(zoom, pan, centroid, centre, MAX_SCALE)
            scale = next.scale
            offset = next.offset
        }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (image) {
            is ViewerImage.Loaded -> {
                decoded(image.bytes)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .onSizeChanged { size = it }
                                .pointerInput(key) {
                                    detectTapGestures(onDoubleTap = {
                                        scale = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                                        offset = Offset.Zero
                                    })
                                }.transformable(transform)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                    )
                } ?: Message(R.string.viewer_failed)
            }

            ViewerImage.Failed -> {
                Message(R.string.viewer_failed)
            }

            else -> {
                Message(R.string.viewer_loading)
            }
        }
        if (image is ViewerImage.Loaded) {
            ZoomChip(
                scale,
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                onStep = { factor ->
                    scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                    if (scale == MIN_SCALE) offset = Offset.Zero
                },
                onFit = {
                    scale = MIN_SCALE
                    offset = Offset.Zero
                },
            )
        }
    }
}

/**
 * "− 250% + · Fit": the zoom, buttons that do what pinch does (UX-20:
 * zoom must not be gesture-only), and the way back to the whole image.
 */
@Composable
private fun ZoomChip(
    scale: Float,
    modifier: Modifier,
    onStep: (Float) -> Unit,
    onFit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.background(SS.colors.bg, ChipShape).border(SS.dimens.hairline, SS.colors.border, ChipShape),
    ) {
        StepButton("−", stringResource(R.string.viewer_zoom_out), enabled = scale > MIN_SCALE) { onStep(1 / ZOOM_STEP) }
        Text(
            stringResource(R.string.zoom_chip, (scale * PERCENT).roundToInt()),
            style = SS.type.label,
            color = SS.colors.ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        StepButton("+", stringResource(R.string.viewer_zoom_in), enabled = scale < MAX_SCALE) { onStep(ZOOM_STEP) }
        TextButton(
            onClick = onFit,
        ) { Text(stringResource(R.string.viewer_fit), style = SS.type.label, color = SS.colors.ink) }
    }
}

@Composable
private fun StepButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(
            glyph,
            style = SS.type.bodyStrong,
            color = if (enabled) SS.colors.ink else SS.colors.inkMuted,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun Message(text: Int) {
    Text(stringResource(text), style = SS.type.body, color = SS.colors.inkMuted)
}

@Composable
private fun label(index: Int): String = stringResource(R.string.viewer_thumb, index + 1)

@Composable
private fun Filmstrip(
    state: ViewerUiState,
    onAction: (ViewerAction) -> Unit,
) {
    // The inset goes outside the strip's height, never inside it (UX-11):
    // inside, a 48 dp 3-button bar left the 56 dp thumbnails 8 dp.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(ThumbSize + 24.dp)
                .padding(12.dp),
    ) {
        itemsIndexed(state.items) { index, item ->
            val selected = index == state.index
            val shape = RoundedCornerShape(8.dp)
            val name = item.title.ifEmpty { label(index) }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(ThumbSize)
                        .background(SS.colors.bg, shape)
                        .border(
                            if (selected) 2.dp else SS.dimens.hairline,
                            if (selected) SS.colors.ink else SS.colors.border,
                            shape,
                        ).selectable(selected = selected, role = Role.Tab) { onAction(ViewerAction.Select(index)) }
                        // Named by the picture's title, and "selected" on the current one (UX-20).
                        .semantics { contentDescription = name },
            ) {
                val loaded = state.images[index] as? ViewerImage.Loaded
                loaded?.let { decoded(it.bytes) }?.let { Image(it, null, Modifier.fillMaxSize().padding(4.dp)) }
                    ?: Column(Modifier.clearAndSetSemantics {}) {
                        Text("${index + 1}", style = SS.type.caption, color = SS.colors.inkMuted)
                    }
            }
        }
    }
}

/** Decodes once per byte array; null when the bytes are not an image Android can draw (SVG pictures). */
@Composable
private fun decoded(bytes: ByteArray): ImageBitmap? =
    remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }

/**
 * The viewer's zoom: a [scale] about the picture's centre and an [offset].
 * Pure, so the pinch maths is a host test.
 */
internal data class PinchZoom(
    val scale: Float,
    val offset: Offset,
) {
    /**
     * After a pinch step of [zoom] and [pan] with the fingers at [centroid]
     * (layout coordinates; the layer scales about [centre]): the content
     * under the fingers stays under them, as in every photo viewer. Back
     * at 1× the picture recentres.
     */
    fun zoomed(
        zoom: Float,
        pan: Offset,
        centroid: Offset,
        centre: Offset,
        maxScale: Float,
    ): PinchZoom {
        val next = (scale * zoom).coerceIn(1f, maxScale)
        if (next == 1f) return PinchZoom(1f, Offset.Zero)
        val applied = next / scale
        val fromCentre = centroid - centre
        return PinchZoom(next, fromCentre - (fromCentre - offset) * applied + pan)
    }
}
