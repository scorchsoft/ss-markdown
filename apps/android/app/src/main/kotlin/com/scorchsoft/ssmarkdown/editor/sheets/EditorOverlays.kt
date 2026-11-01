package com.scorchsoft.ssmarkdown.editor.sheets

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
private const val MESSAGE_MILLIS = 3_000L

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
            ConflictSheet(name, overlay.summary, onAction)
        }

        EditorOverlay.ConfirmUseMine -> {
            UseMineDialog(onAction)
        }

        is EditorOverlay.DirtyPrompt -> {
            LeaveSheet(name, onAction)
        }

        EditorOverlay.Display -> {
            DisplaySheet(state, display, onAction, themeControls)
        }

        is EditorOverlay.SectionMenu -> {
            SectionMenuSheet(overlay, onAction)
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

        EditorOverlay.BigFileInfo -> {
            BigFileInfoDialog { onAction(EditorAction.DismissOverlay) }
        }

        is EditorOverlay.SaveFailureDetails -> {
            SaveFailureDialog(
                overlay.problem,
            ) { onAction(EditorAction.DismissOverlay) }
        }

        else -> {
            Unit
        }
    }
}

/** A short confirmation at the foot of the page, with Undo when it can be undone. Never a toast. */
@Composable
fun MessageBar(
    message: UserMessage,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message.id) {
        delay(MESSAGE_MILLIS)
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

@Suppress("CyclomaticComplexMethod") // One string per message kind.
@Composable
private fun messageText(message: UserMessage): String =
    when (message.kind) {
        MessageKind.SavedTo -> stringResource(R.string.msg_saved_to, message.argument)
        MessageKind.AllSaved -> stringResource(R.string.msg_all_saved)
        MessageKind.Copied -> stringResource(R.string.msg_copied)
        MessageKind.CopiedRichText -> stringResource(R.string.msg_copied_rich)
        MessageKind.CopyTooLarge -> stringResource(R.string.msg_copy_too_large)
        MessageKind.EditsDiscarded -> stringResource(R.string.msg_edits_discarded)
        MessageKind.SectionCopied -> stringResource(R.string.msg_section_copied)
        MessageKind.ReplacedAll -> stringResource(R.string.msg_replaced_all, message.argument)
        MessageKind.Restored -> stringResource(R.string.msg_restored)
        MessageKind.Renamed -> stringResource(R.string.msg_renamed)
        MessageKind.Deleted -> stringResource(R.string.msg_deleted)
        MessageKind.Duplicated -> stringResource(R.string.msg_duplicated)
        MessageKind.ActionFailed -> stringResource(R.string.msg_failed)
        MessageKind.PreviewPending -> stringResource(R.string.msg_preview_pending)
        MessageKind.AutosaveThisFileOn -> stringResource(R.string.msg_autosave_file_on)
        MessageKind.AutosaveThisFileOff -> stringResource(R.string.msg_autosave_file_off)
        MessageKind.PreviewRestarted -> stringResource(R.string.msg_preview_restarted)
        MessageKind.EditRefused -> stringResource(R.string.msg_edit_refused)
        MessageKind.LinkBlocked -> stringResource(R.string.msg_link_blocked)
        MessageKind.ImagesExported -> stringResource(R.string.msg_images_exported, message.argument)
        MessageKind.ImagesExportFailed -> stringResource(R.string.msg_images_failed)
        MessageKind.ImageSaved -> stringResource(R.string.msg_image_saved, message.argument)
        MessageKind.ImageCopied -> stringResource(R.string.msg_image_copied)
        MessageKind.NothingToExport -> stringResource(R.string.msg_nothing_to_export)
    }

/** "This 180 MB file needs about 400 MB, and this phone allows 256 MB" in place of the editor. */
@Composable
fun TooLargeMessage(info: TooLargeInfo) {
    val context = LocalContext.current

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column {
            Text(
                stringResource(
                    R.string.too_large,
                    size(info.fileBytes),
                    size(info.neededBytes),
                    size(info.allowedBytes),
                ),
                style = SS.type.body,
                color = SS.colors.ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}
