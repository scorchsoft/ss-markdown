package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class StripLayoutTest {
    @Test
    fun codeKeysFollowTheSpecOrder() =
        assertEquals(
            "Heading Bold Italic BulletList Task Code Link Indent Table Quote Redo Outdent",
            StripLayout.codeKeys.joinToString(" "),
        )

    @Test
    fun pinnedKeysDependOnTheMode() {
        assertEquals(listOf(StripKey.Undo, StripKey.Symbols), StripLayout.pinnedKeys(StripMode.Code))
        assertEquals(listOf(StripKey.Undo, StripKey.Done), StripLayout.pinnedKeys(StripMode.Preview))
    }

    @Test
    fun tableKeysAppearOnlyInATableOnTheFormattingPage() {
        assertEquals(emptyList<StripKey>(), StripLayout.tableKeys(FormattingStripState()))
        assertEquals(
            listOf(StripKey.TableMenu, StripKey.PrevCell, StripKey.NextCell),
            StripLayout.tableKeys(FormattingStripState(inTable = true)),
        )
        assertEquals(
            listOf(StripKey.TableMenu, StripKey.PrevCell, StripKey.NextCell),
            StripLayout.tableKeys(FormattingStripState(mode = StripMode.Preview, inTable = true)),
        )
        assertEquals(
            emptyList<StripKey>(),
            StripLayout.tableKeys(FormattingStripState(inTable = true, page = StripPage.Symbols)),
        )
    }

    @Test
    fun symbolsFollowTheSpecOrder() =
        assertEquals("` [ ] ( ) # * > | - = !", StripLayout.symbols.joinToString(" ") { it.text })

    @Test
    fun lastVisibleKeyIsCutAtHalf() {
        // 300 dp for 12 keys of 48: five whole keys, then half of the sixth.
        val width = PeekLayout.viewportWidth(300.dp, 48.dp, keyCount = 12)
        assertEquals(5 * 48 + 24, width.value.toInt())
        // With edge padding, the cut still lands mid-key.
        val padded = PeekLayout.viewportWidth(300.dp, 48.dp, keyCount = 12, padding = 4.dp)
        assertEquals(24, ((padded.value - 4) % 48).toInt())
    }

    @Test
    fun keysThatFitAreNotCut() = assertEquals(144, PeekLayout.viewportWidth(300.dp, 48.dp, 3).value.toInt())

    @Test
    fun aTinyAreaStillPeeks() = assertEquals(24, PeekLayout.viewportWidth(30.dp, 48.dp, 12).value.toInt())

    @Test
    fun pagingHasNoHiddenLock() {
        assertEquals(StripPage.Symbols, StripPaging.afterKey(StripPage.Formatting, StripKey.Symbols))
        assertEquals(StripPage.Formatting, StripPaging.afterKey(StripPage.Symbols, StripKey.Symbols))
        assertEquals(StripPage.Symbols, StripPaging.afterKey(StripPage.Symbols, StripKey.Undo))
        assertEquals(StripPage.Symbols, StripPaging.afterTyped(StripPage.Symbols, "[]()1"))
        assertEquals(StripPage.Formatting, StripPaging.afterTyped(StripPage.Symbols, "a"))
        assertEquals(StripPage.Formatting, StripPaging.afterTyped(StripPage.Formatting, "!"))
    }
}
