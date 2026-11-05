package com.scorchsoft.ssmarkdown.editor.preview

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.sheets.TableMenuSheet
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Delete table (Andrew, 2026-11-04): in the table toolbar's ⋯ sheet as well
 * as the strip's table menu, and from either it asks "Delete this table?"
 * first; only Delete sends it to the page. The other table ops go at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeleteTableTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `the table sheet offers Delete table`() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark = false) { TableMenuSheet { actions += it } } }
        rule.onNodeWithText("Delete table").performScrollTo().performClick()
        assertEquals(EditorAction.Table(TableOp.DeleteTable), actions.last())
    }

    @Test
    fun `Delete table asks first, Cancel keeps the table, Delete goes ahead`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        f.vm.onAction(EditorAction.Table(TableOp.DeleteTable))
        assertEquals(EditorOverlay.ConfirmDeleteTable, f.vm.uiState.value.overlay)

        f.vm.onAction(EditorAction.DismissOverlay)
        assertNull(f.vm.uiState.value.overlay)

        f.vm.onAction(EditorAction.Table(TableOp.DeleteTable))
        f.vm.onAction(EditorAction.DeleteTableConfirmed)
        assertNull(f.vm.uiState.value.overlay)
    }

    @Test
    fun `other table ops do not ask`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        f.vm.onAction(EditorAction.Table(TableOp.DeleteRow))
        assertNull(f.vm.uiState.value.overlay)
    }
}
