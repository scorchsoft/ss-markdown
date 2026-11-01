package com.scorchsoft.ssmarkdown.viewstate

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** R7: each file reopens with its own view mode, zoom, cursor and scroll, even after the process has gone. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun store() = JsonViewStateStore(File(temp.root, "view-state.json"))

    @Test
    fun `mode, zoom and position survive a restart`() {
        val first = EditorFixture(temp.root, dispatcher, viewStates = store())
        val uri = first.file("a.md", "# A\n\nlong\n".repeat(50))
        first.vm.openDocument(uri)
        first.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        first.vm.onAction(EditorAction.StepZoom(Pane.Preview, 2))
        first.vm.onAnchorLineChanged(40)
        first.vm.active
            ?.surface
            ?.select(12, 15)
        first.vm.onPause()

        val second = EditorFixture(temp.root, dispatcher, viewStates = store())
        second.vm.openDocument(uri)

        val state = second.vm.uiState.value
        assertEquals(ViewMode.Preview, state.mode)
        assertEquals(120, state.previewZoom)
        assertEquals(40, second.vm.anchorLine.value)
        val saved = JsonViewStateStore(File(temp.root, "view-state.json")).read(uri)
        assertEquals(12, saved?.selectionStart)
        assertEquals(15, saved?.selectionEnd)
    }

    @Test
    fun `each file keeps its own view`() {
        val f = EditorFixture(temp.root, dispatcher)
        val a = f.file("a.md", "a\n")
        val b = f.file("b.md", "b\n")
        f.vm.openDocument(a)
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        f.vm.openDocument(b)
        assertEquals(ViewMode.Code, f.vm.uiState.value.mode)
        f.vm.openDocument(a)
        assertEquals(ViewMode.Split, f.vm.uiState.value.mode)
    }

    @Test
    fun `a damaged store is forgotten, never fatal`() {
        val file = File(temp.root, "view-state.json").apply { writeText("{not json") }
        val store = JsonViewStateStore(file)
        assertNull(store.read("test:a.md"))
        store.write("test:a.md", FileViewState(mode = ViewMode.Code))
        assertEquals(ViewMode.Code, JsonViewStateStore(file).read("test:a.md")?.mode)
    }

    @Test
    fun `the store keeps only the newest files`() {
        val store = JsonViewStateStore(File(temp.root, "v.json"), maxFiles = 2)
        store.write("a", FileViewState(anchorLine = 1))
        store.write("b", FileViewState(anchorLine = 2))
        store.write("c", FileViewState(anchorLine = 3))
        val reread = JsonViewStateStore(File(temp.root, "v.json"))
        assertNull(reread.read("a"))
        assertEquals(3, reread.read("c")?.anchorLine)
    }
}
