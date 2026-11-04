package com.scorchsoft.ssmarkdown.nav

import android.app.Activity
import android.net.Uri
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.AppGraph
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.PendingLeave
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.MarkdownFiles
import com.scorchsoft.ssmarkdown.media.ExportFile
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private const val ZIP = "application/zip"

/** Holds what a picker was opened for until its answer comes back. */
private class PendingPick {
    var exportText: String? = null
    var zip: File? = null
    var importForHistory = false
    var images: List<ExportFile>? = null
}

/** The system pickers, each wired to where its answer goes. */
@Suppress("LongParameterList") // One launcher per picker; built in one place.
private class Pickers(
    val saveAs: ManagedActivityResultLauncher<String, Uri?>,
    val openFile: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val openFolder: ManagedActivityResultLauncher<Uri?, Uri?>,
    val exportText: ManagedActivityResultLauncher<String, Uri?>,
    val saveZip: ManagedActivityResultLauncher<String, Uri?>,
    val importZip: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val imagesZip: ManagedActivityResultLauncher<ZipWithStartFolder.Request, Uri?>,
    val image: ManagedActivityResultLauncher<String?, Uri?>,
    val pictureFolder: ManagedActivityResultLauncher<Uri?, Uri?>,
)

/** The views an effect may act on. */
private class EffectTargets(
    val view: View,
    val keyboard: SoftwareKeyboardController?,
    val activity: Activity?,
)

/**
 * Turns the view models' one-shot events into Android: the system
 * pickers (Save as, Open file, Open folder, export and import), the
 * clipboard, share sheets, haptics, the keyboard and navigation.
 */
@Composable
fun EditorEffectsHost(
    graph: AppGraph,
    platform: PlatformActions,
) {
    val targets = EffectTargets(LocalView.current, LocalSoftwareKeyboardController.current, LocalActivity.current)
    val scope = rememberCoroutineScope()
    val pending = remember { PendingPick() }
    val pickers = rememberPickers(graph, platform, scope, pending)
    LaunchedEffect(graph.editor) {
        graph.editor.events.collect { onEditorEvent(it, graph, platform, pickers, pending, targets) }
    }
    LaunchedEffect(graph.storage) {
        graph.storage.events.collect { onScreenEvent(it, graph, pickers, pending, fromHistory = false) }
    }
    LaunchedEffect(graph.history) {
        graph.history.events.collect { onScreenEvent(it, graph, pickers, pending, fromHistory = true) }
    }
}

@Suppress("LongMethod") // One launcher per picker, each a few lines; splitting would scatter them.
@Composable
private fun rememberPickers(
    graph: AppGraph,
    platform: PlatformActions,
    scope: CoroutineScope,
    pending: PendingPick,
): Pickers {
    val vm = graph.editor
    val markdown = ActivityResultContracts.CreateDocument(MarkdownFiles.CREATE_MIME_TYPE)
    return Pickers(
        saveAs = rememberLauncherForActivityResult(markdown) { uri -> vm.saves.onSaveLocation(uri?.toString()) },
        openFile =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    onFilePicked(graph, it)
                }
            },
        openFolder =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                vm.folder.onFolderPicked(it?.toString())
            },
        exportText =
            rememberLauncherForActivityResult(markdown) { uri ->
                val text = pending.exportText.also { pending.exportText = null }
                if (uri != null && text != null) scope.launch { platform.writeText(uri, text) }
            },
        saveZip =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
                val file = pending.zip.also { pending.zip = null }
                // Only once the copy has landed is the app-private export removed; until then it stays safe.
                if (uri != null && file != null) scope.launch { if (platform.copyFile(file, uri)) file.delete() }
            },
        imagesZip =
            rememberLauncherForActivityResult(ZipWithStartFolder()) { uri ->
                val files = pending.images.also { pending.images = null }
                if (uri != null && files != null) {
                    scope.launch {
                        val ok = graph.media.writeZip(uri, files)
                        onZipWritten(graph, uri, ok)
                    }
                }
            },
        image = rememberLauncherForActivityResult(OpenImage()) { uri -> vm.pictures.onImagePicked(uri?.toString()) },
        pictureFolder =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                vm.pictures.onFolderPicked(it?.toString())
            },
        importZip =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val forHistory = pending.importForHistory
                scope.launch {
                    val zip = uri?.let { platform.copyToCache(it) }
                    if (forHistory) {
                        graph.history.onImportPicked(
                            zip,
                        )
                    } else {
                        graph.storage.onImportPicked(
                            zip,
                            vm.active
                                ?.open
                                ?.uri
                                ?.value,
                        )
                    }
                }
            },
    )
}

/** Keeps access across restarts; a provider that refuses write still opens (read-only, Save as…). */
private fun onFilePicked(
    graph: AppGraph,
    uri: Uri,
) {
    val document = DocumentUri(uri.toString())
    if (!graph.permissions.take(document, write = true)) graph.permissions.take(document, write = false)
    graph.editor.saves.requestLeave(PendingLeave.OpenOther(document.value))
}

// One line per event; the targets all come from one composable.
@Suppress("CyclomaticComplexMethod", "LongParameterList")
private suspend fun onEditorEvent(
    event: EditorEvent,
    graph: AppGraph,
    platform: PlatformActions,
    pickers: Pickers,
    pending: PendingPick,
    targets: EffectTargets,
) {
    when (event) {
        is EditorEvent.PickSaveLocation -> {
            pickers.saveAs.launch(event.suggestedName)
        }

        EditorEvent.PickFileToOpen -> {
            pickers.openFile.launch(MarkdownFiles.MIME_TYPES)
        }

        EditorEvent.PickFolder -> {
            pickers.openFolder.launch(null)
        }

        is EditorEvent.PickExportLocation -> {
            pending.exportText = event.text
            pickers.exportText.launch(event.suggestedName)
        }

        is EditorEvent.CopyText -> {
            platform.copy(event.text)
        }

        is EditorEvent.ShareAsFile -> {
            platform.shareAsFile(event.name, event.text)
        }

        is EditorEvent.ShareText -> {
            platform.shareText(event.text, event.richText)
        }

        is EditorEvent.Open -> {
            graph.screens.push(event.screen)
        }

        EditorEvent.HideKeyboard -> {
            targets.keyboard?.hide()
            KeyboardReturn.hide(targets.view)
        }

        EditorEvent.ReturnKeyboard -> {
            KeyboardReturn.toFocusedText(targets.view)
        }

        EditorEvent.Tick -> {
            platform.haptic(targets.view, error = false)
        }

        EditorEvent.ErrorTick -> {
            platform.haptic(targets.view, error = true)
        }

        EditorEvent.Finish -> {
            targets.activity?.finish()
        }

        is EditorEvent.SaveZip -> {
            pending.images = event.files
            pickers.imagesZip.launch(ZipWithStartFolder.Request("${event.docName}-images.zip", event.initialFolder))
        }

        is EditorEvent.PickImage -> {
            pickers.image.launch(event.initialFolder)
        }

        is EditorEvent.PickPictureFolder -> {
            pickers.pictureFolder.launch(event.initialFolder?.toUri())
        }

        else -> {
            onPreviewEvent(event, graph)
        }
    }
}

private fun onScreenEvent(
    event: ScreenEvent,
    graph: AppGraph,
    pickers: Pickers,
    pending: PendingPick,
    fromHistory: Boolean,
) {
    when (event) {
        ScreenEvent.Back -> {
            graph.screens.pop()
        }

        is ScreenEvent.SaveZip -> {
            pending.zip = event.file
            pickers.saveZip.launch(event.suggestedName)
        }

        ScreenEvent.PickImportZip -> {
            pending.importForHistory = fromHistory
            pickers.importZip.launch(arrayOf(ZIP))
        }

        is ScreenEvent.Open -> {
            graph.screens.push(event.screen)
        }

        ScreenEvent.ToEditor -> {
            graph.screens.popToEditor()
        }
    }
}
