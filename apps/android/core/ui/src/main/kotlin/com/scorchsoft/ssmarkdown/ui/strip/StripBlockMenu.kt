package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.R
import com.scorchsoft.ssmarkdown.core.ui.SS

private val TickSize = 20.dp

/**
 * The ¶ key and its block-type menu (docs/ux-spec.md, "Formatting strip"):
 * Paragraph, Heading 1–3, the three lists, Quote and Code block, the
 * caret's current type ticked. Each row is 48 dp and a radio button to
 * TalkBack ("Heading 2, selected"); the key reads its current type
 * ("Block style, Paragraph"). The menu opens above the strip and never
 * takes focus ([StripMenu]), so the editor keeps its caret and the
 * keyboard stays up. The menu's open state is the only state
 * here: what a choice does is the editor's business.
 */
@Composable
internal fun BlockMenuKey(
    current: BlockChoice?,
    width: Dp,
    onBlock: (BlockChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = current?.let { stringResource(it.label) }
    val selectedLabel = stringResource(R.string.block_current)
    Box {
        StripKeyButton(StripKey.BlockStyle, pressed = false, enabled = true, width = width, state = currentLabel) {
            open = !open
        }
        StripMenu(open = open, onDismiss = { open = false }) {
            BlockChoice.entries.forEach { choice ->
                val isCurrent = choice == current
                DropdownMenuItem(
                    text = { Text(stringResource(choice.label), style = SS.type.body, color = SS.colors.ink) },
                    trailingIcon =
                        if (isCurrent) {
                            {
                                Icon(
                                    StripIcons.done,
                                    contentDescription = null,
                                    tint = SS.colors.ink,
                                    modifier = Modifier.size(TickSize),
                                )
                            }
                        } else {
                            null
                        },
                    onClick = {
                        open = false
                        onBlock(choice)
                    },
                    modifier =
                        Modifier
                            .heightIn(min = SS.dimens.key)
                            .noFocus()
                            .semantics {
                                role = Role.RadioButton
                                selected = isCurrent
                                if (isCurrent) stateDescription = selectedLabel
                            },
                )
            }
        }
    }
}
