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
import com.scorchsoft.ssmarkdown.settings.PreviewWrap
import com.scorchsoft.ssmarkdown.settings.SettingsAction
import com.scorchsoft.ssmarkdown.settings.SettingsReducer
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.themes.ThemeControls
import com.scorchsoft.ssmarkdown.themes.isDark
import com.scorchsoft.ssmarkdown.themes.rememberPreviewLook
import java.util.concurrent.Executor

@Composable
internal fun EditorRoute(
    graph: AppGraph,
    previewPane: @Composable (PreviewHostState) -> Unit,
) {
    val vm = graph.editor
    val state by vm.uiState.collectAsState()
    val active by vm.activeDocument.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    // Remembered once: EditorPanes hosts this with movableContentOf, so its identity must never change.
    val codePane = remember(vm) { codePaneFor(vm, graph.pasteExecutor) }
    val look = rememberPreviewLook(graph.themes, settings.previewThemeId, settings.appearance, graph.fonts.faceSource)
    val preview =
        PreviewHostState(
            document = active?.text,
            version = active?.text?.version ?: 0,
            // Read when the pane lands, outside composition: observing it here recomposed the whole
            // screen on every frame of a fling (PERF-13).
            anchorLine = vm.anchorLine::value,
            zoomPercent = state.previewZoom,
            tapToEdit = settings.tapToEditPreview,
            onAnchorLineChanged = { vm.onPaneTopLine(Pane.Preview, it) },
            onZoomChanged = {
                percent,
                pinching,
                ->
                vm.onAction(EditorAction.ZoomChanged(Pane.Preview, percent, pinching))
            },
            onEdit = { start, end, text -> previewEdit(vm, start, end, text) },
            onHeadingLongPress = { vm.onAction(EditorAction.Navigator(NavigatorAction.HeadingMenu(it))) },
            look = look,
            pageMode = settings.previewWrap == PreviewWrap.Page,
            noWrap = settings.previewWrap == PreviewWrap.NoWrap,
            readOnly = active?.open?.readOnly != null,
            renderer = state.preview.renderer,
            onTouched = { vm.onAction(EditorAction.PaneTouched(Pane.Preview)) },
            onRetry = { vm.onAction(EditorAction.RetryPreview) },
            onShowCode = { vm.onAction(EditorAction.SelectMode(ViewMode.Code)) },
        )
    EditorScreen(
        state = state,
        onAction = vm::onAction,
        codePane = codePane,
        previewPane = previewPane,
        previewState = preview,
        appearance = settings.appearance,
        themeControls = { DisplayThemeControls(graph, vm::onAction) },
        display =
            DisplayFacts(
                settings.appearance,
                graph.themes
                    .byId(settings.previewThemeId)
                    .size.base,
                settings.previewWrap,
            ),
        stripRows = settings.formattingBar,
    )
}

@Composable
private fun DisplayThemeControls(
    graph: AppGraph,
    onAction: (EditorAction) -> Unit,
) {
    val settings by graph.settings.settings.collectAsState()
    val customs by graph.themes.themes.collectAsState()
    val all = remember(customs) { graph.themes.all() }
    ThemeControls(
        themes = all,
        selectedId = settings.previewThemeId,
        dark = isDark(settings.appearance),
        onSelect = { onAction(EditorAction.SetPreviewTheme(it)) },
        onEdit = { onAction(EditorAction.EditTheme(it)) },
    )
}

internal fun previewPaneFor(
    view: PreviewView,
    session: PreviewSession,
): @Composable (PreviewHostState) -> Unit = { state -> PreviewPaneHost(state, view, session) }

private fun codePaneFor(
    vm: EditorViewModel,
    background: Executor,
): @Composable (Modifier) -> Unit =
    { modifier ->
        val active by vm.activeDocument.collectAsState()
        val state by vm.uiState.collectAsState()
        CodePaneHost(active, state.codeWrap, state.codeZoom, vm::attach, vm::detach, background, modifier)
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
