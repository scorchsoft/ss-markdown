package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import com.scorchsoft.ssmarkdown.ui.strip.StripPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorReducerTest {
    private val dirty =
        EditorUiState(
            save = SavePresentation(SaveButton.Save, SaveStatusLine.Unsaved, SaveTap.Save, showDirtyDot = true),
        )
    private val clean =
        EditorUiState(
            save = SavePresentation(SaveButton.AllSaved, SaveStatusLine.Opened(0), SaveTap.ShowAllSaved, false),
        )

    private fun reduce(
        state: EditorUiState,
        vararg actions: EditorAction,
    ) = actions.fold(state, EditorReducer::reduce)

    @Test
    fun `back closes the innermost thing first`() {
        val open =
            dirty.copy(
                overlay = EditorOverlay.Menu,
                navigator = NavigatorUiState(),
                find =
                    com.scorchsoft.ssmarkdown.find
                        .FindUiState(),
            )
        val once = reduce(open, EditorAction.BackPressed)
        assertNull(once.overlay)
        val twice = reduce(once, EditorAction.BackPressed)
        assertNull(twice.navigator)
        val thrice = reduce(twice, EditorAction.BackPressed)
        assertNull(thrice.find)
    }

    @Test
    fun `back on a dirty file asks Save, Don't save or Keep editing`() {
        val asked = reduce(dirty, EditorAction.BackPressed)
        assertEquals(EditorOverlay.DirtyPrompt(PendingLeave.Exit), asked.overlay)
        assertFalse(EditorReducer.backLeaves(asked))
        assertNull(reduce(asked, EditorAction.KeepEditing).overlay)
    }

    @Test
    fun `back on a clean file leaves without asking`() {
        assertTrue(EditorReducer.backLeaves(clean))
        assertEquals(clean, reduce(clean, EditorAction.BackPressed))
    }

    @Test
    fun `back closes the folder drawer before asking`() {
        val drawer = dirty.copy(folder = FolderUiState("docs", "tree", drawerOpen = true))
        val after = reduce(drawer, EditorAction.BackPressed)
        assertEquals(false, after.folder?.drawerOpen)
        assertNull(after.overlay)
    }

    @Test
    fun `zoom steps by ten, clamps, and the chip shows only away from the default`() {
        val zoomed = reduce(clean, EditorAction.StepZoom(Pane.Code, 2))
        assertEquals(120, zoomed.codeZoom)
        assertTrue(zoomed.showCodeZoomChip)
        assertFalse(zoomed.showPreviewZoomChip)
        assertEquals(EditorReducer.MAX_ZOOM, reduce(clean, EditorAction.StepZoom(Pane.Preview, 100)).previewZoom)
        assertFalse(reduce(zoomed, EditorAction.ResetZoom(Pane.Code)).showCodeZoomChip)
    }

    @Test
    fun `a pinch shows the pill only while fingers are down`() {
        val pinching = reduce(clean, EditorAction.ZoomChanged(Pane.Code, 112, pinching = true))
        assertEquals(112, pinching.pinchPercent)
        val settled = reduce(pinching, EditorAction.ZoomChanged(Pane.Code, 115, pinching = false))
        assertNull(settled.pinchPercent)
        assertEquals(115, settled.codeZoom)
    }

    @Test
    fun `the symbols page flips with braces and closes with the keyboard`() {
        val symbols = reduce(clean, EditorAction.StripKeyTapped(StripKey.Symbols))
        assertEquals(StripPage.Symbols, symbols.strip.page)
        assertEquals(StripPage.Symbols, reduce(symbols, EditorAction.StripKeyTapped(StripKey.Bold)).strip.page)
        assertEquals(StripPage.Formatting, reduce(symbols, EditorAction.ImeVisibilityChanged(false)).strip.page)
    }

    @Test
    fun `menus and sheets open and dismiss`() {
        assertEquals(EditorOverlay.Menu, reduce(clean, EditorAction.OpenMenu).overlay)
        assertNull(reduce(clean, EditorAction.CopyLongPressed).overlay)
        assertEquals(EditorOverlay.ConfirmUseMine, reduce(clean, EditorAction.AskUseMine).overlay)
        assertNull(reduce(clean, EditorAction.OpenMenu, EditorAction.DismissOverlay).overlay)
    }

    @Test
    fun `failure details open only from a failed-save banner`() {
        val failed = dirty.copy(banner = EditorBanner.SaveFailed(SaveProblem.StorageFull))
        assertEquals(
            EditorOverlay.SaveFailureDetails(SaveProblem.StorageFull),
            reduce(failed, EditorAction.ShowFailureDetails).overlay,
        )
        assertNull(reduce(dirty, EditorAction.ShowFailureDetails).overlay)
    }

    @Test
    fun `the find bar keeps its options`() {
        val bar =
            reduce(
                clean,
                EditorAction.Find(FindAction.Open),
                EditorAction.Find(FindAction.ToggleRegex),
                EditorAction.Find(FindAction.ToggleReplace),
            )
        assertTrue(bar.find?.options?.regex == true)
        assertTrue(bar.find?.replaceOpen == true)
        assertNull(reduce(bar, EditorAction.Find(FindAction.Close)).find)
    }

    @Test
    fun `recovery banner dismisses with Got it`() {
        val recovered = dirty.copy(banner = EditorBanner.Recovered(0, closedBySwipe = false))
        assertNull(reduce(recovered, EditorAction.RecoveryGotIt).banner)
    }
}
