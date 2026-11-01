package com.scorchsoft.ssmarkdown.navigator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Test
    fun `matches only at word starts, contiguously`() {
        assertTrue(WordStartMatcher.matches("Save flow", "sav"))
        assertTrue(WordStartMatcher.matches("savings-plan.md", "sav"))
        assertTrue(WordStartMatcher.matches("Saving drafts", "draf"))
        assertTrue(WordStartMatcher.matches("saveFlow", "flow"))
        assertFalse(WordStartMatcher.matches("research-overview", "sav"))
        assertFalse(WordStartMatcher.matches("unsaved", "sav"))
    }

    @Test
    fun `results are grouped headings, then recent files, then the folder`() {
        val state =
            NavigatorUiState(
                query = "sav",
                outline = listOf(OutlineRow(3, 3, "Save flow"), OutlineRow(9, 2, "Releases")),
                drafts = listOf(FileRow("d", "Untitled", "", FileRowKind.Draft)),
                recent = listOf(FileRow("r", "savings-plan.md", "Downloads", FileRowKind.Recent)),
                folder = listOf(FileRow("f", "save-pipeline.md", "specs/", FileRowKind.Folder)),
            )
        val results = state.results
        assertEquals(listOf("Save flow"), results.headings.map { it.text })
        assertEquals(listOf("savings-plan.md"), results.recent.map { it.name })
        assertEquals(listOf("save-pipeline.md"), results.folder.map { it.name })
        assertTrue(state.searching)
    }

    @Test
    fun `nothing matching is an empty result, and closing actions close the sheet`() {
        assertTrue(NavigatorUiState(query = "xyz").results.isEmpty)
        assertEquals(null, NavigatorReducer.reduce(NavigatorUiState(), NavigatorAction.JumpToHeading(3)))
        assertEquals(
            NavigatorTab.Files,
            NavigatorReducer.reduce(NavigatorUiState(), NavigatorAction.SelectTab(NavigatorTab.Files))?.tab,
        )
    }
}
