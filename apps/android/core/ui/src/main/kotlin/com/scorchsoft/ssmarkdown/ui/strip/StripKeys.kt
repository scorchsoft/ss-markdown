package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.SSColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val KeyShape = RoundedCornerShape(8.dp)
private val IconSize = 18.dp

/** Hold-to-repeat timing: the platform's key-repeat feel, a pause then a steady rate. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

/**
 * How a pressed toggle key looks (UX-5). The tint alone is 1.1:1 on the
 * strip, below the 3:1 WCAG 1.4.11 asks of a state indicator, so the face
 * also gets an [ring] outline all the way round (never a one-sided bar),
 * which is ink: 14:1 or more on the strip in both themes.
 */
internal object PressedKeyFace {
    val ringWidth = 1.5.dp

    fun fill(colors: SSColors): Color = colors.segmentTrack

    fun ring(colors: SSColors): Color = colors.ink
}

/**
 * One 48 dp strip key. A toggle key is a checkbox to TalkBack ("Bold,
 * checked"), the Android twin of `aria-pressed`, and shows a filled tint
 * inside an ink ring when pressed ([PressedKeyFace]): never an accent
 * colour, since red means "needs you".
 */
@Composable
internal fun StripKeyButton(
    key: StripKey,
    pressed: Boolean,
    enabled: Boolean,
    width: Dp = SS.dimens.key,
    // What TalkBack reads after the label, such as the ¶ key's current block type.
    state: String? = null,
    onClick: () -> Unit,
) {
    val colors = SS.colors
    val label = stringResource(key.label)
    val tint = if (enabled) colors.ink else colors.inkMuted.copy(alpha = DISABLED_ALPHA)
    val action =
        if (key.toggles) {
            Modifier.toggleable(value = pressed, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        } else {
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(width, SS.dimens.key)
                .semantics {
                    contentDescription = label
                    state?.let { stateDescription = it }
                }.then(action),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(KeyFace)
                    .clearAndSetSemantics {}
                    .then(
                        if (pressed) {
                            Modifier
                                .background(PressedKeyFace.fill(colors), KeyShape)
                                .border(PressedKeyFace.ringWidth, PressedKeyFace.ring(colors), KeyShape)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            KeyGlyph(key, tint)
        }
    }
}

/** The drawn face of a key: a letter for H, B and I as in the mockups, a line icon for the rest. */
@Composable
private fun KeyGlyph(
    key: StripKey,
    tint: Color,
) {
    val letter =
        when (key) {
            StripKey.Heading -> {
                "H" to TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            StripKey.BlockStyle -> {
                "¶" to TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            StripKey.Bold -> {
                "B" to TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }

            StripKey.Italic -> {
                "I" to TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp)
            }

            StripKey.Symbols -> {
                "{ }" to SS.type.code.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            else -> {
                null
            }
        }
    if (letter != null) {
        Text(letter.first, style = letter.second, color = tint)
    } else {
        Icon(iconFor(key), contentDescription = null, tint = tint, modifier = Modifier.size(IconSize))
    }
}

/** The line icon for each key that has one; keys drawn as letters are absent, and anything else shows Done's tick. */
private val keyIcons: Map<StripKey, () -> ImageVector> =
    mapOf(
        StripKey.BulletList to { StripIcons.bulletList },
        StripKey.Task to { StripIcons.task },
        StripKey.Code to { StripIcons.code },
        StripKey.Link to { StripIcons.link },
        StripKey.Indent to { StripIcons.indent },
        StripKey.Outdent to { StripIcons.outdent },
        StripKey.Table to { StripIcons.table },
        StripKey.Quote to { StripIcons.quote },
        StripKey.Undo to { StripIcons.undo },
        StripKey.Redo to { StripIcons.redo },
        StripKey.PrevCell to { StripIcons.prevCell },
        StripKey.NextCell to { StripIcons.nextCell },
        StripKey.TableMenu to { StripIcons.more },
    )

private fun iconFor(key: StripKey): ImageVector = keyIcons[key]?.invoke() ?: StripIcons.done

/**
 * The pinned `{ }` key. It is outlined so it reads as a page switch, and
 * inverted (ink fill) while the symbols page is showing, matching the
 * mockup's "Back to formatting" state. To TalkBack it is a checkbox with
 * a stable name, "Symbols, checked" (UX-28): a label that flipped as well
 * as the state read "Back to formatting, checked", which says neither.
 */
@Composable
internal fun SymbolsToggleKey(
    showingSymbols: Boolean,
    onClick: () -> Unit,
) {
    val colors = SS.colors
    val label = stringResource(StripKey.Symbols.label)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(SS.dimens.key)
                .semantics { contentDescription = label }
                .toggleable(value = showingSymbols, role = Role.Checkbox, onValueChange = { onClick() }),
    ) {
        val face = Modifier.size(KeyFace).clearAndSetSemantics {}
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                if (showingSymbols) {
                    face.background(colors.ink, KeyShape)
                } else {
                    face.border(SS.dimens.hairline, colors.border, KeyShape)
                },
        ) {
            Text(
                "{ }",
                style = SS.type.code.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = if (showingSymbols) colors.bg else colors.ink,
            )
        }
    }
}

/** A key on the symbols page: the char itself, in the code face, read by name. */
@Composable
internal fun SymbolKeyButton(
    symbol: SymbolKey,
    width: Dp = SS.dimens.key,
    onClick: () -> Unit,
) {
    val label = stringResource(symbol.label)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(width, SS.dimens.key)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            symbol.text,
            style = SS.type.code.copy(fontSize = 15.sp),
            color = SS.colors.ink,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * A cursor key that repeats while held: one step on touch, then steady
 * steps after a pause. TalkBack gets a plain button that steps once per
 * double-tap, which is what a screen-reader user expects.
 */
@Composable
internal fun CursorKey(
    step: CursorStep,
    onStep: (CursorStep) -> Unit,
    width: Dp = SS.dimens.key,
) {
    val label = stringResource(if (step == CursorStep.Left) StripLabels.cursorLeft else StripLabels.cursorRight)
    val currentOnStep by rememberUpdatedState(onStep)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(width, SS.dimens.key)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        currentOnStep(step)
                        true
                    }
                }.pointerInput(step) {
                    coroutineScope {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            currentOnStep(step)
                            val repeat =
                                launch {
                                    delay(REPEAT_DELAY_MS)
                                    while (isActive) {
                                        currentOnStep(step)
                                        delay(REPEAT_INTERVAL_MS)
                                    }
                                }
                            waitForUpOrCancellation()
                            repeat.cancel()
                        }
                    }
                },
    ) {
        val icon = if (step == CursorStep.Left) StripIcons.cursorLeft else StripIcons.cursorRight
        Icon(icon, contentDescription = null, tint = SS.colors.ink, modifier = Modifier.size(IconSize))
    }
}

private val KeyFace = 40.dp
private const val DISABLED_ALPHA = 0.5f
