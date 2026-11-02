package com.scorchsoft.ssmarkdown.nav

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.scorchsoft.ssmarkdown.AppGraph
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerEvent
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerScreen
import com.scorchsoft.ssmarkdown.media.ViewerAction
import com.scorchsoft.ssmarkdown.media.ViewerScreen
import com.scorchsoft.ssmarkdown.platform.BoundedRead
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import com.scorchsoft.ssmarkdown.themes.FontRole
import com.scorchsoft.ssmarkdown.themes.ThemeEditorEvent
import com.scorchsoft.ssmarkdown.themes.ThemeEditorScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val JSON = "application/json"
private val FONT_TYPES =
    arrayOf(
        "font/ttf",
        "font/otf",
        "font/sfnt",
        "application/x-font-ttf",
        "application/x-font-otf",
        "application/octet-stream",
    )

/** The full-screen viewer over the editor; Back returns to the page it came from. */
@Composable
fun ViewerRoute(graph: AppGraph) {
    val vm = graph.editor.viewer
    val state by vm.state.collectAsState()
    ViewerScreen(state) { action ->
        if (action == ViewerAction.Back) graph.screens.pop() else vm.onAction(action)
    }
}

/** The theme editor, with its own import, export and font picker launches. */
@Composable
fun ThemeEditorRoute(
    graph: AppGraph,
    platform: PlatformActions,
) {
    val vm = graph.themeEditor
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { PendingJson() }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON)) { uri ->
            val json = pending.json.also { pending.json = null }
            if (uri != null && json != null) scope.launch { platform.writeText(uri, json) }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val text =
                    withContext(graph.io) {
                        try {
                            // Bounded (SEC-8): a theme is at most ThemeCodec.MAX_BYTES; anything bigger is not one.
                            context.contentResolver
                                .openInputStream(uri)
                                ?.use { BoundedRead.bytes(it, ThemeCodec.MAX_BYTES) }
                                ?.decodeToString()
                        } catch (_: IOException) {
                            null
                        } catch (_: SecurityException) {
                            null
                        }
                    }
                vm.onImported(displayName(context, uri), text)
            }
        }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ThemeEditorEvent.ExportJson -> {
                    pending.json = event.json
                    export.launch(event.suggestedName)
                }

                ThemeEditorEvent.PickImport -> {
                    import.launch(arrayOf(JSON, "text/plain"))
                }

                is ThemeEditorEvent.PickFont -> {
                    openFontPicker(graph, event.role)
                }

                ThemeEditorEvent.Close -> {
                    graph.screens.pop()
                }
            }
        }
    }
    ThemeEditorScreen(state, vm::onAction)
}

/** The font picker for [role] of the theme being edited, marking the font it has now. */
private fun openFontPicker(
    graph: AppGraph,
    role: FontRole,
) {
    val theme = graph.themeEditor.state.value.theme
    val current =
        when (role) {
            FontRole.Body -> theme.fonts.body
            FontRole.Heading -> theme.fonts.heading
            FontRole.Code -> theme.fonts.code
        }
    graph.fontPicker.open(role, theme.name, current)
    graph.screens.push(AppScreen.FontPicker)
}

/** The theme JSON waiting for its Export location. */
private class PendingJson {
    var json: String? = null
}

/** Choose a font; Back or a choice returns to the theme editor. */
@Composable
fun FontPickerRoute(graph: AppGraph) {
    val vm = graph.fontPicker
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            vm.onFilePicked(displayName(context, uri)) {
                context.contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
            }
        }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                FontPickerEvent.PickFile -> pick.launch(FONT_TYPES)
                FontPickerEvent.Close -> graph.screens.pop()
            }
        }
    }
    BackHandler { graph.screens.pop() }
    FontPickerScreen(state, vm::onAction)
}

/** The picked file's display name, for messages; its last path segment when the provider will not say. */
private fun displayName(
    context: android.content.Context,
    uri: Uri,
): String =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } ?: uri.lastPathSegment.orEmpty()
