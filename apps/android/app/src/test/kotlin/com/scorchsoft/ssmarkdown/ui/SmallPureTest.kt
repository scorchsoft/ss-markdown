package com.scorchsoft.ssmarkdown.ui

import com.scorchsoft.ssmarkdown.about.Licences
import com.scorchsoft.ssmarkdown.editor.StripController
import com.scorchsoft.ssmarkdown.folder.FolderNames
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.FormatState
import com.scorchsoft.ssmarkdown.markdown.edit.InlineFormat
import com.scorchsoft.ssmarkdown.markdown.edit.ListKind
import com.scorchsoft.ssmarkdown.nav.AppScreen
import com.scorchsoft.ssmarkdown.nav.ScreenStack
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallPureTest {
    @Test
    fun `relative times read as the status line says them`() {
        assertEquals(RelativeTime.JustNow, RelativeTime.between(0, 59_000))
        assertEquals(RelativeTime.Minutes(2), RelativeTime.between(0, 120_000))
        assertEquals(RelativeTime.Hours(1), RelativeTime.between(0, 3_600_000))
        assertEquals(RelativeTime.Days(4), RelativeTime.between(0, 4 * 86_400_000L))
        assertEquals(RelativeTime.JustNow, RelativeTime.between(10, 0))
    }

    @Test
    fun `folder names come from the tree id`() {
        assertEquals("product-docs", FolderNames.nameOf("content://x/tree/primary%3ADocuments%2Fproduct-docs"))
        assertEquals("notes copy.md", FolderNames.copyName("notes.md"))
        assertTrue(FolderNames.isMarkdown("A.MD"))
        assertFalse(FolderNames.isMarkdown("todo.txt"))
    }

    @Test
    fun `licences parse and skip comments and bad lines`() {
        val list = Licences.parse("# c\nA | MIT | x\nbroken line\nB | Apache License 2.0 | y\n")
        assertEquals(listOf("A", "B"), list.map { it.name })
    }

    @Test
    fun `strip keys map to edit commands and show their state`() {
        assertEquals(EditCommand.ToggleInline(InlineFormat.Bold), StripController.commandFor(StripKey.Bold))
        assertEquals(EditCommand.ToggleList(ListKind.Task), StripController.commandFor(StripKey.Task))
        assertNull(StripController.commandFor(StripKey.Undo))
        val pressed = StripController.pressedKeys(FormatState(inline = setOf(InlineFormat.Bold), headingLevel = 2))
        assertEquals(setOf(StripKey.Bold, StripKey.Heading), pressed)
    }

    @Test
    fun `the back stack never pops the editor`() {
        val stack = ScreenStack()
        assertFalse(stack.pop())
        stack.push(AppScreen.Settings)
        stack.push(AppScreen.Settings)
        stack.push(AppScreen.Storage)
        assertEquals(listOf(AppScreen.Editor, AppScreen.Settings, AppScreen.Storage), stack.screens.value)
        assertTrue(stack.pop())
        stack.popToEditor()
        assertEquals(AppScreen.Editor, stack.current)
    }
}
