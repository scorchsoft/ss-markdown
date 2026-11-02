package com.scorchsoft.ssmarkdown.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.scorchsoft.ssmarkdown.AppGraph
import com.scorchsoft.ssmarkdown.about.AboutScreen
import com.scorchsoft.ssmarkdown.about.Licence
import com.scorchsoft.ssmarkdown.about.LicencesRoute
import com.scorchsoft.ssmarkdown.conflict.CompareAction
import com.scorchsoft.ssmarkdown.conflict.CompareScreen
import com.scorchsoft.ssmarkdown.conflict.MergeAction
import com.scorchsoft.ssmarkdown.conflict.MergeResolveScreen
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorScreen
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.editor.panes.PreviewHostState
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPaneHost
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.editor.preview.PreviewSession
import com.scorchsoft.ssmarkdown.editor.sheets.DisplayFacts
import com.scorchsoft.ssmarkdown.history.HistoryScreen
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import com.scorchsoft.ssmarkdown.preview.PreviewView
import com.scorchsoft.ssmarkdown.settings.SettingsAction
import com.scorchsoft.ssmarkdown.settings.SettingsReducer
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.themes.ThemeControls
import com.scorchsoft.ssmarkdown.themes.isDark
import com.scorchsoft.ssmarkdown.themes.rememberPreviewLook

/**
 * The app's composition: the editor always, with any other screen drawn
 * over it. Keeping the editor composed means the Code view (and, later,
 * the WebView) survives a visit to Settings or History with its cursor,
 * scroll and keyboard state intact.
 *
 * @param previewView the Activity's one Preview WebView; null draws a stand-in (tests without a WebView).
 * @param previewPane overrides the Preview slot entirely, for tests.
 */
@Composable
fun AppRoot(
    graph: AppGraph,
    platform: PlatformActions,
    versionName: String,
    licences: () -> List<Licence>,
    previewView: PreviewView? = null,
    previewPane: (@Composable (PreviewHostState) -> Unit)? = null,
) {
    val screens by graph.screens.screens.collectAsState()
    // Remembered once: EditorPanes hosts this with movableContentOf, so its identity must never change.
    val pane =
        remember(previewView, previewPane) {
            previewPane ?: previewView?.let { view -> previewPaneFor(view, graph.editor.preview) }
                ?: { PreviewPlaceholder(it) }
        }
    Box(Modifier.fillMaxSize()) {
        EditorRoute(graph, pane)
        ScreenOver(screens.last(), graph, platform, versionName, licences)
        if (screens.size > 1) BackHandler { graph.screens.pop() }
    }
    EditorEffectsHost(graph, platform)
}

/** Whatever screen is on top of the editor, drawn over it. */
@Composable
private fun ScreenOver(
    screen: AppScreen,
    graph: AppGraph,
    platform: PlatformActions,
    versionName: String,
    licences: () -> List<Licence>,
) {
    when (screen) {
        AppScreen.Editor -> {
            Unit
        }

        AppScreen.Settings -> {
            SettingsRoute(graph)
        }

        AppScreen.Storage -> {
            StorageRoute(graph)
        }

        AppScreen.History -> {
            HistoryRoute(graph)
        }

        AppScreen.About -> {
            AboutScreen(versionName, platform::openSite, platform::openSource, {
                graph.screens.push(AppScreen.Licences)
            }, { graph.screens.pop() })
        }

        AppScreen.Licences -> {
            LicencesRoute(licences(), { graph.screens.pop() })
        }

        AppScreen.Merge -> {
            MergeRoute(graph.editor, graph.screens)
        }

        AppScreen.Compare -> {
            CompareRoute(graph.editor, graph.screens)
        }

        AppScreen.Viewer -> {
            ViewerRoute(graph)
        }

        AppScreen.ThemeEditor -> {
            ThemeEditorRoute(graph, platform)
        }

        AppScreen.FontPicker -> {
            FontPickerRoute(graph)
        }
    }
}

@Composable
private fun SettingsRoute(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsState()
    val breakdown = graph.accountant.lastKnown
    LaunchedEffect(Unit) { graph.accountant.measure() }
    SettingsScreen(
        settings = settings,
        storageSummary =
            breakdown?.let {
                com.scorchsoft.ssmarkdown.storage
                    .bytesText(it.totalBytes)
            },
        onAction = { action ->
            when (action) {
                SettingsAction.Back -> graph.screens.pop()
                SettingsAction.OpenStorage -> graph.screens.push(AppScreen.Storage)
                SettingsAction.OpenAbout -> graph.screens.push(AppScreen.About)
                else -> graph.settings.update { SettingsReducer.reduce(it, action) }
            }
        },
    )
}

@Composable
private fun StorageRoute(graph: AppGraph) {
    val vm = graph.storage
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    StorageScreen(state, vm::onAction)
}

@Composable
private fun HistoryRoute(graph: AppGraph) {
    val vm = graph.history
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    HistoryScreen(state, vm::onAction)
}

@Composable
private fun MergeRoute(
    vm: EditorViewModel,
    screens: ScreenStack,
) {
    val state by vm.conflicts.merge.collectAsState()
    val merge = state ?: return
    MergeResolveScreen(merge, onAction = { action ->
        when (action) {
            is MergeAction.Choose -> vm.conflicts.chooseOverlap(action.choice)
            is MergeAction.Show -> vm.conflicts.showOverlap(action.index)
            is MergeAction.EditByHand -> vm.conflicts.editOverlap(action.editing)
            MergeAction.Done -> vm.conflicts.finishMerge().also { screens.popToEditor() }
            MergeAction.Back -> screens.pop()
        }
    })
}

@Composable
private fun CompareRoute(
    vm: EditorViewModel,
    screens: ScreenStack,
) {
    val state by vm.conflicts.compare.collectAsState()
    val compare = state ?: return
    CompareScreen(compare, onAction = { action ->
        when (action) {
            is CompareAction.SetView -> vm.conflicts.setCompareView(action.view)
            CompareAction.KeepTheirs -> vm.onAction(EditorAction.KeepTheirs).also { screens.popToEditor() }
            CompareAction.Merge -> screens.pop().also { vm.onAction(EditorAction.MergeMine) }
            CompareAction.Back -> screens.pop()
        }
    })
}
