package com.scorchsoft.ssmarkdown.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.chrome.EditorBannerView
import com.scorchsoft.ssmarkdown.editor.chrome.LandscapeBar
import com.scorchsoft.ssmarkdown.editor.chrome.ModeBar
import com.scorchsoft.ssmarkdown.editor.chrome.TopBar
import com.scorchsoft.ssmarkdown.editor.panes.EditorPanes
import com.scorchsoft.ssmarkdown.editor.panes.PreviewHostState
import com.scorchsoft.ssmarkdown.editor.sheets.EditorOverlays
import com.scorchsoft.ssmarkdown.editor.sheets.MessageBar
import com.scorchsoft.ssmarkdown.editor.sheets.ThemeControlsPlaceholder
import com.scorchsoft.ssmarkdown.editor.sheets.TooLargeMessage
import com.scorchsoft.ssmarkdown.find.FindBar
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.folder.FolderPanel
import com.scorchsoft.ssmarkdown.navigator.NavigatorSheet
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.ui.strip.FormattingStrip
import com.scorchsoft.ssmarkdown.ui.strip.StripDocument
import com.scorchsoft.ssmarkdown.ui.strip.StripMode

/** The folder panel docks from this width (ux-spec "Layouts"). */
private val DockedWidth = 840.dp
private val PanelWidth = 300.dp
private val DrawerWidth = 320.dp

/** A window this short is a phone on its side: one 48 dp bar, no bottom bar. */
private val LandscapeMaxHeight = 480.dp

/** Above this font scale the switcher goes icon-only and Copy moves into ⋮. */
private const val LARGE_FONT_SCALE = 1.5f

/**
 * The editor screen: a pure `(state, onAction)` composable, so it renders
 * under Robolectric in every state. The panes are slots: [codePane] is the
 * `MarkdownEditor` in the app and a stand-in in tests; [previewPane] is
 * the `feature/preview` host, and [themeControls] the `core/theme`
 * pickers inside Display. Both default to placeholders.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    codePane: @Composable (Modifier) -> Unit,
    previewPane: @Composable (PreviewHostState) -> Unit,
    previewState: PreviewHostState,
    appearance: Appearance,
    modifier: Modifier = Modifier,
    themeControls: @Composable () -> Unit = { ThemeControlsPlaceholder() },
) {
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { onAction(EditorAction.ImeVisibilityChanged(imeVisible)) }
    BackHandler(enabled = !EditorReducer.backLeaves(state)) { onAction(EditorAction.BackPressed) }
    val largeFont = LocalDensity.current.fontScale > LARGE_FONT_SCALE
    BoxWithConstraints(modifier.fillMaxSize().background(SS.colors.bg)) {
        val landscape = maxWidth > maxHeight && maxHeight < LandscapeMaxHeight
        val docked = maxWidth >= DockedWidth && state.folder != null
        val layout = ScreenLayout(landscape, docked, imeVisible, largeFont)
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            if (docked && !state.folder.panelCollapsed) {
                FolderPanel(
                    state.folder,
                    { onAction(EditorAction.Folder(it)) },
                    Modifier.width(PanelWidth).statusBarsPadding(),
                )
                VerticalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
            }
            EditorColumn(state, onAction, layout, codePane, previewPane, previewState, Modifier.weight(1f))
        }
        if (!docked) Drawer(state, onAction)
    }
    EditorOverlays(state, appearance, onAction, themeControls)
}

/** The layout facts every part of the screen agrees on. */
private data class ScreenLayout(
    val landscape: Boolean,
    val docked: Boolean,
    val imeVisible: Boolean,
    val largeFont: Boolean,
)

@Suppress("LongParameterList") // The screen's slots, passed straight through.
@Composable
private fun EditorColumn(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    layout: ScreenLayout,
    codePane: @Composable (Modifier) -> Unit,
    previewPane: @Composable (PreviewHostState) -> Unit,
    previewState: PreviewHostState,
    modifier: Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Header(state, onAction, layout)
        state.banner?.let { EditorBannerView(it, state.title, onAction) }
        state.find?.let { FindBar(it, { action -> onAction(EditorAction.Find(action)) }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val tooLarge = state.tooLarge
            if (tooLarge != null) {
                TooLargeMessage(tooLarge)
            } else {
                EditorPanes(state, onAction, codePane, previewPane, previewState, layout.imeVisible)
            }
            state.navigator?.let { nav ->
                // Below the header, so the title, the unsaved dot and Save stay visible.
                NavigatorSheet(nav, { onAction(EditorAction.Navigator(it)) }, Modifier.heightIn(max = 560.dp))
            }
            state.message?.let { MessageBar(it, onAction, Modifier.align(Alignment.BottomCenter)) }
        }
        Footer(state, onAction, layout)
    }
}

@Composable
private fun Header(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    layout: ScreenLayout,
) {
    val folderButton = state.folder != null && !layout.docked
    when {
        // Landscape with the keyboard: the header steps aside; the strip carries name, dot and Save.
        layout.landscape && layout.imeVisible -> Box(Modifier.statusBarsPadding())

        layout.landscape -> LandscapeBar(state, onAction, folderButton, layout.largeFont, Modifier.statusBarsPadding())

        else -> TopBar(state, onAction, folderButton, layout.largeFont, Modifier.statusBarsPadding())
    }
}

@Composable
private fun Footer(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    layout: ScreenLayout,
) {
    when {
        layout.imeVisible -> {
            val strip =
                state.strip.copy(
                    mode = if (state.mode == ViewMode.Preview) StripMode.Preview else StripMode.Code,
                    canUndo = state.canUndo || state.strip.canUndo,
                    document = if (layout.landscape) StripDocument(state.title, state.isDirty) else null,
                )
            FormattingStrip(
                state = strip,
                onKey = { onAction(EditorAction.StripKeyTapped(it)) },
                onSymbol = { onAction(EditorAction.SymbolTapped(it)) },
                onCursor = { onAction(EditorAction.CursorStepped(it)) },
                onSave = { onAction(EditorAction.SaveTapped) },
                modifier = Modifier.imePadding(),
            )
        }

        layout.landscape -> {
            Box(Modifier.navigationBarsPadding())
        }

        else -> {
            ModeBar(
                mode = state.mode,
                onSelect = { onAction(EditorAction.SelectMode(it)) },
                iconOnly = layout.largeFont,
                modes = if (state.isMarkdown) ViewMode.entries else listOf(ViewMode.Code),
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

/** The modal folder drawer on phones and small tablets. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val folder = state.folder ?: return
    if (!folder.drawerOpen) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onAction(EditorAction.Folder(FolderAction.CloseDrawer))
            },
    ) {
        // The panel swallows taps on its empty areas, so only the scrim closes the drawer.
        val panel =
            Modifier
                .width(DrawerWidth)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .statusBarsPadding()
                .navigationBarsPadding()
        FolderPanel(folder, { onAction(EditorAction.Folder(it)) }, panel)
    }
}

private const val SCRIM_ALPHA = 0.32f
