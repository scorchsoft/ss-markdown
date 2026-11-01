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
import com.scorchsoft.ssmarkdown.about.LicencesScreen
import com.scorchsoft.ssmarkdown.conflict.CompareAction
import com.scorchsoft.ssmarkdown.conflict.CompareScreen
import com.scorchsoft.ssmarkdown.conflict.MergeAction
import com.scorchsoft.ssmarkdown.conflict.MergeResolveScreen
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorScreen
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.editor.panes.PreviewHostState
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.history.HistoryScreen
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import com.scorchsoft.ssmarkdown.settings.SettingsAction
import com.scorchsoft.ssmarkdown.settings.SettingsReducer
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * The app's composition: the editor always, with any other screen drawn
 * over it. Keeping the editor composed means the Code view (and, later,
 * the WebView) survives a visit to Settings or History with its cursor,
 * scroll and keyboard state intact.
 *
 * @param previewPane the Preview slot; `feature/preview` replaces the placeholder here.
 */
@Composable
fun AppRoot(
    graph: AppGraph,
    platform: PlatformActions,
    versionName: String,
    licences: List<Licence>,
    previewPane: @Composable (PreviewHostState) -> Unit = { PreviewPlaceholder(it) },
) {
    val screens by graph.screens.screens.collectAsState()
    Box(Modifier.fillMaxSize()) {
        EditorRoute(graph, previewPane)
        when (screens.last()) {
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
                AboutScreen(versionName, platform::openSite, {
                    graph.screens.push(AppScreen.Licences)
                }, { graph.screens.pop() })
            }

            AppScreen.Licences -> {
                LicencesScreen(licences, { graph.screens.pop() })
            }

            AppScreen.Merge -> {
                MergeRoute(graph.editor, graph.screens)
            }

            AppScreen.Compare -> {
                CompareRoute(graph.editor, graph.screens)
            }
        }
        if (screens.size > 1) BackHandler { graph.screens.pop() }
    }
    EditorEffectsHost(graph, platform)
}

@Composable
private fun EditorRoute(
    graph: AppGraph,
    previewPane: @Composable (PreviewHostState) -> Unit,
) {
    val vm = graph.editor
    val state by vm.uiState.collectAsState()
    val active by vm.activeDocument.collectAsState()
    val anchor by vm.anchorLine.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    // Remembered once: EditorPanes hosts this with movableContentOf, so its identity must never change.
    val codePane = remember(vm) { codePaneFor(vm) }
    val preview =
        PreviewHostState(
            document = active?.text,
            version = active?.text?.version ?: 0,
            anchorLine = anchor,
            zoomPercent = state.previewZoom,
            tapToEdit = settings.tapToEditPreview,
            onAnchorLineChanged = vm::onAnchorLineChanged,
            onZoomChanged = {
                percent,
                pinching,
                ->
                vm.onAction(EditorAction.ZoomChanged(Pane.Preview, percent, pinching))
            },
            onEdit = { start, end, text -> previewEdit(vm, start, end, text) },
            onHeadingLongPress = { vm.onAction(EditorAction.Navigator(NavigatorAction.HeadingMenu(it))) },
        )
    EditorScreen(
        state = state,
        onAction = vm::onAction,
        codePane = codePane,
        previewPane = previewPane,
        previewState = preview,
        appearance = settings.appearance,
    )
}

private fun codePaneFor(vm: EditorViewModel): @Composable (Modifier) -> Unit =
    { modifier ->
        val active by vm.activeDocument.collectAsState()
        val state by vm.uiState.collectAsState()
        CodePaneHost(active, state.codeWrap, state.codeZoom, vm::attach, vm::detach, modifier)
    }

/** A Preview edit becomes a minimal source change through the editor surface (one undo step). */
private fun previewEdit(
    vm: EditorViewModel,
    start: Int,
    end: Int,
    text: String,
) {
    val doc = vm.active ?: return
    val snapshot = doc.text.snapshot()
    if (end > snapshot.length || start > end) return
    doc.surface.apply(
        listOf(EditOp(start, snapshot.subSequence(start, end), text)),
        TextSelection.caret(start + text.length),
    )
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
