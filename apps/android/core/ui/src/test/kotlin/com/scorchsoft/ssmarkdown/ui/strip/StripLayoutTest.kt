package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripLayoutTest {
    @Test
    fun codeKeysFollowTheSpecOrder() =
        assertEquals(
            "BlockStyle Bold Italic BulletList Task Code Link Indent Table Image Quote Redo Outdent",
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
    fun lastVisibleKeyIsCutAtHalfWithNoGapBeforeThePinnedKeys() {
        // Every width a phone or tablet gives the scrolling area, with and without edge padding.
        for (available in 120..900) {
            for (padding in listOf(0, 4)) {
                val room = (available - padding).toFloat()
                val viewport = PeekLayout.viewportWidth(available.dp, 48.dp, keyCount = 30, padding = padding.dp)
                val key = PeekLayout.keyWidth(available.dp, 48.dp, keyCount = 30, padding = padding.dp)
                // The area runs right up to the pinned group: no leftover gap (Andrew, 2026-11-02).
                assertEquals(available.toFloat(), viewport.value, 0.01f)
                assertTrue("a key is never under 48 dp ($available)", key.value >= 48f - 0.01f)
                // A phone or tablet's room grows keys by a fifth at most; only a sliver of a window more.
                val most = if (available >= 300) 1.2f else 1.4f
                assertTrue("keys grow by at most ${most}x ($available)", key.value <= 48f * most)
                // The last visible key shows half its width.
                val shown = room / key.value
                assertEquals("cut at about half ($available, $padding)", 0.5f, shown - shown.toInt(), 0.151f)
            }
        }
    }

    @Test
    fun andrewsPhoneShowsSixKeysAndHalfTheSeventh() {
        // 1080 px at 2.625 = 411.4 dp; Undo and Done are 2 x 48 dp. In 0.2.0 the area
        // stopped at five keys and a half (5.99 rounded down), leaving a 47 dp gap.
        val available = 411.4f.dp - 96.dp
        assertEquals(48f, PeekLayout.keyWidth(available, 48.dp, keyCount = 7, padding = 4.dp).value, 0.01f)
        assertEquals(available, PeekLayout.viewportWidth(available, 48.dp, keyCount = 7, padding = 4.dp))
    }

    @Test
    fun keysThatFitAreNotCut() {
        assertEquals(148, PeekLayout.viewportWidth(300.dp, 48.dp, 3, padding = 4.dp).value.toInt())
        assertEquals(48, PeekLayout.keyWidth(300.dp, 48.dp, 3, padding = 4.dp).value.toInt())
    }

    @Test
    fun aTinyAreaKeepsFullSizeKeys() = assertEquals(48, PeekLayout.keyWidth(60.dp, 48.dp, 12).value.toInt())

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
