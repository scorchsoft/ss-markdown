package com.scorchsoft.ssmarkdown.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.AppGraph
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.platform.PlatformActions

/**
 * The Preview's one-shot effects: rich text from the renderer, links,
 * image exports and the theme editor. PDF is not here: it prints through
 * the page itself (`PreviewSession.print`), bound to its document. Kept apart from
 * [EditorEffectsHost] so each file reads as one area.
 */
internal suspend fun onPreviewEvent(
    event: EditorEvent,
    graph: AppGraph,
    platform: PlatformActions,
) {
    val vm = graph.editor
    when (event) {
        is EditorEvent.CopyRichText -> {
            copyRich(graph, platform, event)
        }

        is EditorEvent.OpenLink -> {
            graph.media.openLink(event.url)
        }

        is EditorEvent.ShareFiles -> {
            graph.media.share(event.files)
        }

        is EditorEvent.CopyImage -> {
            if (graph.media.copyImage(event.file)) vm.message(MessageKind.ImageCopied)
        }

        is EditorEvent.SaveToDownloads -> {
            saved(graph, event.folder, graph.media.saveToDownloads(event.folder, event.files))
        }

        is EditorEvent.EditTheme -> {
            graph.themeEditor.open(event.themeId)
            vm.onAction(EditorAction.DismissOverlay)
            graph.screens.push(AppScreen.ThemeEditor)
        }

        else -> {
            Unit
        }
    }
}

/** HTML from the preview when it can give it; the plain Markdown always goes along. */
private suspend fun copyRich(
    graph: AppGraph,
    platform: PlatformActions,
    event: EditorEvent.CopyRichText,
) {
    val html =
        graph.editor.preview.view
            ?.exportHtml(event.fromLine, event.toLine)
    if (html != null) graph.media.copyHtml(event.text, html) else platform.copy(event.text)
}

private fun saved(
    graph: AppGraph,
    folder: String,
    count: Int,
) {
    val vm = graph.editor
    if (count > 0) vm.message(MessageKind.ImagesExported, folder) else vm.message(MessageKind.ImagesExportFailed)
}

/** The ZIP landed (or not); the folder it went to is offered first next time (ux-spec, J6). */
internal fun onZipWritten(
    graph: AppGraph,
    uri: Uri,
    ok: Boolean,
) {
    if (!ok) {
        graph.editor.message(MessageKind.ImagesExportFailed)
        return
    }
    graph.settings.update { it.copy(export = it.export.copy(zipFolder = uri.toString())) }
    graph.editor.message(MessageKind.ImageSaved, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
}

/**
 * "Create document" that starts in the folder the last ZIP went to, so
 * the folder picker is a confirmation the second time, not a hunt.
 */
internal class ZipWithStartFolder : ActivityResultContract<ZipWithStartFolder.Request, Uri?>() {
    /** The suggested file name and the remembered folder, if any. */
    data class Request(
        val name: String,
        val startIn: String?,
    )

    override fun createIntent(
        context: Context,
        input: Request,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, input.name)
            .apply { input.startIn?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it.toUri()) } }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data
}
