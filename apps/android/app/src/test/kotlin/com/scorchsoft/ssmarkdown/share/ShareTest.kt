package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.AppendPrefix
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTest {
    private fun apply(
        existing: String,
        insertion: Insertion,
    ) = existing.substring(0, insertion.offset) + insertion.text + existing.substring(insertion.offset)

    @Test
    fun `bottom appends after a blank line without doubling one`() {
        assertEquals(
            "# Notes\n\nquote\n",
            apply("# Notes\n", AppendPlanner.plan("# Notes\n", "quote", AppendPosition.Bottom, AppendPrefix.None, "")),
        )
        assertEquals(
            "a\n\nb\n",
            apply("a\n\n", AppendPlanner.plan("a\n\n", "b", AppendPosition.Bottom, AppendPrefix.None, "")),
        )
        assertEquals(
            "a\n\nb\n",
            apply("a", AppendPlanner.plan("a", "b\n\n", AppendPosition.Bottom, AppendPrefix.None, "")),
        )
    }

    @Test
    fun `top inserts before everything else`() {
        assertEquals(
            "b\n\na\n",
            apply("a\n", AppendPlanner.plan("a\n", "b", AppendPosition.Top, AppendPrefix.None, "")),
        )
    }

    @Test
    fun `the date prefix is an H3`() {
        val plan = AppendPlanner.plan("", "b", AppendPosition.Bottom, AppendPrefix.DateHeading, "3 Nov 2026, 14:02")
        assertEquals("### 3 Nov 2026, 14:02\n\nb\n", plan.text)
    }

    @Test
    fun `the sheet remembers choices and labels its button by them`() {
        val state = ShareUiState(text = "x", target = "content://a")
        assertEquals(null, ShareReducer.reduce(state, ShareAction.ChooseTarget(null)).target)
        assertEquals(
            AppendPosition.Top,
            ShareReducer.reduce(state, ShareAction.SetPosition(AppendPosition.Top)).position,
        )
        assertEquals(AppendPrefix.DateHeading, ShareReducer.reduce(state, ShareAction.TogglePrefix).prefix)
    }

    @Test
    fun `the source is the shared link's host`() {
        assertEquals("example.org", ShareReducer.sourceOf("Read https://www.example.org/articles/x", null))
        assertEquals("Subject", ShareReducer.sourceOf("no link", "Subject"))
    }
}
