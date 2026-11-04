package com.scorchsoft.ssmarkdown.editor.sheets

import android.text.format.Formatter
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.editor.TooLargeInfo
import com.scorchsoft.ssmarkdown.editor.UserMessage
import kotlinx.coroutines.delay

private val MessageShape = RoundedCornerShape(18.dp)
private const val MESSAGE_MILLIS = 3_000

/** With a screen reader on, a message offering Undo stays at least this long, as Material's do. */
private const val SCREEN_READER_UNDO_MILLIS = 10_000

/** The sheet or dialog for [EditorUiState.overlay]; menus anchor to their buttons instead. */
@Composable
fun EditorOverlays(
    state: EditorUiState,
    display: DisplayFacts,
    onAction: (EditorAction) -> Unit,
    themeControls: @Composable () -> Unit,
) {
    val name = state.title.ifEmpty { stringResource(R.string.untitled) }
    when (val overlay = state.overlay) {
        is EditorOverlay.Conflict -> {
            ConflictSheet(name, overlay.summary, onAction, historyOn = state.historyOn)
        }

        EditorOverlay.ConfirmUseMine -> {
            UseMineDialog(onAction, historyOn = state.historyOn)
        }

        is EditorOverlay.DirtyPrompt -> {
            if (overlay.draft) DraftLeaveSheet(onAction) else LeaveSheet(name, onAction, historyOn = state.historyOn)
        }

        is EditorOverlay.Display -> {
            DisplaySheet(state, display, onAction, themeControls, highlight = overlay.highlight)
        }

        is EditorOverlay.SectionMenu -> {
            SectionMenuSheet(overlay, onAction)
        }

        EditorOverlay.BigFileInfo -> {
            BigFileInfoDialog { onAction(EditorAction.DismissOverlay) }
        }

        is EditorOverlay.NotKept -> {
            NotKeptDialog(overlay, onAction)
        }

        is EditorOverlay.SaveFailureDetails -> {
            SaveFailureDialog(
                overlay.problem,
            ) { onAction(EditorAction.DismissOverlay) }
        }

        else -> {
            EditingOverlays(overlay, onAction)
        }
    }
}

/** The sheets of editing and Preview: the Link prompt, the link chip, media, tables and image export. */
@Composable
private fun EditingOverlays(
    overlay: EditorOverlay?,
    onAction: (EditorAction) -> Unit,
) {
    when (overlay) {
        is EditorOverlay.LinkPrompt -> {
            LinkPromptSheet(overlay, onAction)
        }

        is EditorOverlay.LinkChip -> {
            LinkChipSheet(overlay, onAction)
        }

        is EditorOverlay.MediaMenu -> {
            MediaMenuSheet(overlay, onAction)
        }

        is EditorOverlay.TableMenu -> {
            TableMenuSheet(onAction)
        }

        is EditorOverlay.ExportImages -> {
            ExportImagesSheet(overlay.state, onAction)
        }

        is EditorOverlay.CopyPicture -> {
            CopyPictureDialog(onAction)
        }

        EditorOverlay.ConfirmDeleteTable -> {
            DeleteTableDialog(onAction)
        }

        else -> {
            Unit
        }
    }
}

/**
 * Insert image picked a picture outside the document's folder: copy it into
 * `images/` beside the file, or Cancel (docs/ux-spec.md, "Pictures"). A
 * raw `content://` link is never offered: it would stop working.
 */
@Composable
private fun CopyPictureDialog(onAction: (EditorAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.copy_picture_title), style = SS.type.title, color = SS.colors.ink) },
        text = { Text(stringResource(R.string.copy_picture_body), style = SS.type.body, color = SS.colors.ink) },
        confirmButton = {
            TextButton(onClick = { onAction(EditorAction.CopyPictureIn) }) {
                Text(stringResource(R.string.copy_picture_confirm), style = SS.type.label, color = SS.colors.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(EditorAction.DismissOverlay) }) {
                Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink)
            }
        },
    )
}

/** "Delete this table?" before either table menu removes the whole table; Undo still brings it back. */
@Composable
private fun DeleteTableDialog(onAction: (EditorAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
        title = {
            Text(
                stringResource(R.string.table_delete_confirm_title),
                style = SS.type.title,
                color = SS.colors.ink,
            )
        },
        text = {
            Text(
                stringResource(R.string.table_delete_confirm_body),
                style = SS.type.body,
                color = SS.colors.ink,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAction(EditorAction.DeleteTableConfirmed) }) {
                Text(stringResource(R.string.table_delete_table), style = SS.type.label, color = SS.colors.needsYouText)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(EditorAction.DismissOverlay) }) {
                Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink)
            }
        },
    )
}

/**
 * A short confirmation at the foot of the page, with Undo when it can be
 * undone. Never a toast. It stays as long as the user's accessibility
 * timeout asks (UX-10, WCAG 2.2.1): see [messageTimeoutMillis].
 */
@Composable
fun MessageBar(
    message: UserMessage,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(message.id) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val timeout =
            messageTimeoutMillis(
                undo = message.undo,
                screenReader = manager?.isTouchExplorationEnabled == true,
            ) { millis, flags -> manager?.getRecommendedTimeoutMillis(millis, flags) ?: millis }
        delay(timeout)
        onAction(EditorAction.MessageShown)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .padding(16.dp)
                .background(SS.colors.ink, MessageShape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Text(
            messageText(message),
            style = SS.type.label,
            color = SS.colors.bg,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (message.undo) {
            TextButton(onClick = { onAction(EditorAction.MessageUndo) }) {
                Text(stringResource(R.string.undo), style = SS.type.label, color = SS.colors.bg)
            }
        }
    }
}

/**
 * How long a message stays: 3 s, or the user's "Time to take action"
 * setting via [recommend] (`AccessibilityManager.getRecommendedTimeoutMillis`),
 * which a message with Undo asks for as controls and the rest as text. A
 * screen-reader user needs time to reach Undo, so with one running a
 * message with Undo stays at least [SCREEN_READER_UNDO_MILLIS].
 */
internal fun messageTimeoutMillis(
    undo: Boolean,
    screenReader: Boolean,
    recommend: (millis: Int, flags: Int) -> Int,
): Long {
    val flags =
        if (undo) {
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
        } else {
            AccessibilityManager.FLAG_CONTENT_TEXT
        }
    val recommended = recommend(MESSAGE_MILLIS, flags).toLong()
    return if (undo && screenReader) maxOf(recommended, SCREEN_READER_UNDO_MILLIS.toLong()) else recommended
}

@Suppress("CyclomaticComplexMethod") // One string per message kind.
@Composable
private fun messageText(message: UserMessage): String =
    when (message.kind) {
        MessageKind.SavedTo -> stringResource(R.string.msg_saved_to, message.argument)
        MessageKind.AllSaved -> stringResource(R.string.msg_all_saved)
        MessageKind.Copied -> stringResource(R.string.msg_copied)
        MessageKind.CopiedFormatted -> stringResource(R.string.msg_copied_formatted)
        MessageKind.CopiedFormattedShortened -> stringResource(R.string.msg_copied_formatted_shortened)
        MessageKind.FormattedFailed -> stringResource(R.string.msg_formatted_failed)
        MessageKind.CopyTooLarge -> stringResource(R.string.msg_copy_too_large)
        MessageKind.PasteTooLarge -> stringResource(R.string.msg_paste_too_large)
        MessageKind.EditsDiscarded -> stringResource(R.string.msg_edits_discarded)
        MessageKind.EditsDiscardedNoHistory -> stringResource(R.string.msg_edits_discarded_no_history)
        MessageKind.EditsDiscardedNotKept -> stringResource(R.string.msg_edits_discarded_not_kept)
        MessageKind.DraftDeleted -> stringResource(R.string.msg_draft_deleted)
        MessageKind.SectionCopied -> stringResource(R.string.msg_section_copied)
        MessageKind.SectionCopiedFormatted -> stringResource(R.string.msg_section_copied_formatted)
        MessageKind.ReplacedAll -> stringResource(R.string.msg_replaced_all, message.argument)
        MessageKind.Restored -> stringResource(R.string.msg_restored)
        MessageKind.Renamed -> stringResource(R.string.msg_renamed)
        MessageKind.SaveBeforeRename -> stringResource(R.string.msg_save_before_rename)
        MessageKind.Deleted -> stringResource(R.string.msg_deleted)
        MessageKind.Duplicated -> stringResource(R.string.msg_duplicated)
        MessageKind.ActionFailed -> stringResource(R.string.msg_failed)
        MessageKind.PreviewPending -> stringResource(R.string.msg_preview_pending)
        MessageKind.AutosaveThisFileOn -> stringResource(R.string.msg_autosave_file_on)
        MessageKind.AutosaveThisFileOff -> stringResource(R.string.msg_autosave_file_off)
        MessageKind.PreviewRestarted -> stringResource(R.string.msg_preview_restarted)
        MessageKind.EditRefused -> stringResource(R.string.msg_edit_refused)
        MessageKind.LinkBlocked -> stringResource(R.string.msg_link_blocked)
        MessageKind.LinkCopied -> stringResource(R.string.msg_link_copied)
        MessageKind.ImagesExported -> stringResource(R.string.msg_images_exported, message.argument)
        MessageKind.ImagesExportFailed -> stringResource(R.string.msg_images_failed)
        MessageKind.ImageSaved -> stringResource(R.string.msg_image_saved, message.argument)
        MessageKind.ImageCopied -> stringResource(R.string.msg_image_copied)
        MessageKind.NothingToExport -> stringResource(R.string.msg_nothing_to_export)
        MessageKind.PdfFailed -> stringResource(R.string.msg_pdf_failed)
        MessageKind.ExportStopped -> stringResource(R.string.msg_export_stopped)
        MessageKind.PictureFolderGranted -> stringResource(R.string.msg_picture_folder_granted, message.argument)
        MessageKind.PictureFolderUnrelated -> stringResource(R.string.msg_picture_folder_unrelated)
        MessageKind.PictureTooLarge -> stringResource(R.string.msg_picture_too_large)
        MessageKind.PictureNotImage -> stringResource(R.string.msg_picture_not_image)
        MessageKind.PictureFailed -> stringResource(R.string.msg_picture_failed)
        MessageKind.PicturesStayedWithDraft -> stringResource(R.string.msg_pictures_stayed)
        MessageKind.PictureInsertStopped -> stringResource(R.string.msg_picture_insert_stopped)
        MessageKind.PictureCopiedNotLinked -> stringResource(R.string.msg_picture_copied_not_linked, message.argument)
    }

/** "This 180 MB file needs about 400 MB, and this phone allows 256 MB" in place of the editor. */
@Composable
fun TooLargeMessage(info: TooLargeInfo) {
    val context = LocalContext.current

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column {
            Text(
                if (info.fitsWhenFreed) {
                    stringResource(
                        R.string.too_large_now,
                        size(info.fileBytes),
                        size(info.neededBytes),
                        size(info.usableNowBytes),
                    )
                } else {
                    stringResource(
                        R.string.too_large,
                        size(info.fileBytes),
                        size(info.neededBytes),
                        size(info.allowedBytes),
                    )
                },
                style = SS.type.body,
                color = SS.colors.ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}
