package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction

/**
 * The section menu (ux-spec "Navigator"; J10 in two taps): Copy section ·
 * Copy as rich text · Select section · Export section…, with Select text
 * instead as its last row.
 */
@Composable
fun SectionMenuSheet(
    menu: EditorOverlay.SectionMenu,
    onAction: (EditorAction) -> Unit,
) {
    val nav = { action: NavigatorAction -> onAction(EditorAction.Navigator(action)) }
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        SheetTitle(menu.heading)
        SheetBody(
            menu.nextHeading?.let {
                pluralStringResource(
                    R.plurals.section_lines_up_to,
                    menu.lineCount,
                    menu.lineCount,
                    it,
                )
            }
                ?: pluralStringResource(R.plurals.section_lines, menu.lineCount, menu.lineCount),
            muted = true,
        )
        SheetButton(stringResource(R.string.section_copy), Emphasis.Ink, {
            nav(NavigatorAction.CopySection(menu.headingLine, richText = false))
        })
        SheetButton(stringResource(R.string.section_copy_rich), Emphasis.Outline, {
            nav(NavigatorAction.CopySection(menu.headingLine, richText = true))
        })
        SheetButton(stringResource(R.string.section_select), Emphasis.Outline, {
            nav(NavigatorAction.SelectSection(menu.headingLine))
        })
        SheetButton(stringResource(R.string.section_export), Emphasis.Outline, {
            nav(NavigatorAction.ExportSection(menu.headingLine))
        })
        SheetButton(
            stringResource(R.string.section_select_text),
            Emphasis.Quiet,
            { onAction(EditorAction.DismissOverlay) },
        )
    }
}
