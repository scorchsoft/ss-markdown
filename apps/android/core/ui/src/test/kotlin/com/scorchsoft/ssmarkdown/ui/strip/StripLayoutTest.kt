package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StripLayoutTest {
    @Test
    fun codeKeysFollowTheSpecOrder() =
        assertEquals(
            "BlockStyle Bold Italic BulletList NumberedList Task Code Link Outdent Indent Table Image Quote",
            StripLayout.codeKeys.joinToString(" "),
        )

    @Test
    fun pinnedKeysDependOnTheMode() {
        assertEquals(listOf(StripKey.Undo, StripKey.Redo, StripKey.Symbols), StripLayout.pinnedKeys(StripMode.Code))
        assertEquals(listOf(StripKey.Undo, StripKey.Redo, StripKey.Done), StripLayout.pinnedKeys(StripMode.Preview))
        assertEquals(listOf(StripKey.Undo, StripKey.Symbols), StripLayout.pinnedKeys(StripMode.Code, StripPage.Symbols))
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

    // ---- Two rows (Settings → Formatting bar)

    /** The two-row split of [state] on a [width] dp window: the top row is left of the pinned keys. */
    private fun split(
        state: FormattingStripState,
        width: Int,
    ): RowSplit {
        val pinned = (StripLayout.pinnedKeys(state.mode, state.page).size + StripLayout.tableKeys(state).size) * 48
        return StripLayout.splitRows(StripLayout.items(state), (width - pinned).dp, 48.dp)
    }

    private fun allVisible(
        state: FormattingStripState,
        width: Int,
    ) = split(state, width).allVisible(width.dp, 48.dp)

    @Test
    fun twoRowsShowEveryFormattingKeyOnA412DpPhone() {
        val code = FormattingStripState()
        assertEquals(RowSplit(top = 5, bottom = 8), split(code, 412))
        assertTrue(allVisible(code, 412))
        assertTrue(allVisible(FormattingStripState(page = StripPage.Symbols), 412))
        assertTrue(allVisible(FormattingStripState(mode = StripMode.Preview), 412))
    }

    @Test
    fun twoRowsAt360DpShowEveryPreviewKeyAndAllButOneCodeKey() {
        assertTrue(allVisible(FormattingStripState(mode = StripMode.Preview), 360))
        // 16 keys of 48 dp (13 scrolling, Undo, Redo and { }) need 768 dp, more than two 360 dp
        // rows, so Insert image is cut at half and Quote a short scroll away. Keys never shrink
        // under 48 dp (the touch target), so this is the narrowest honest answer.
        val code = FormattingStripState()
        assertEquals(RowSplit(top = 4, bottom = 9), split(code, 360))
        assertFalse(allVisible(code, 360))
        assertEquals(48f, PeekLayout.keyWidth(360.dp, 48.dp, 8).value, 0.01f)
        // From 384 dp (the next common phone width) every Code key and every symbol shows.
        for (width in 384..480) {
            assertTrue("code at $width", allVisible(code, width))
            assertTrue("symbols at $width", allVisible(FormattingStripState(page = StripPage.Symbols), width))
        }
    }

    @Test
    fun aNarrowOrZoomedWindowScrollsTheBottomRow() {
        // Display size "Largest" turns a 412 dp phone into about 320 dp. Keys stay 48 dp, so the
        // bottom row scrolls, its last visible key cut at about half.
        val split = split(FormattingStripState(), 320)
        assertEquals(RowSplit(top = 3, bottom = 10), split)
        assertFalse(split.allVisible(320.dp, 48.dp))
        val key = PeekLayout.keyWidth(320.dp, 48.dp, split.bottom)
        val shown = 320f / key.value
        assertEquals(0.5f, shown - shown.toInt(), 0.151f)
    }

    @Test
    fun tableKeysTakeTopRowRoomAndTheRestCarriesOn() {
        // ⋯, Prev and Next sit before Undo in the top row, so fewer formatting keys fit beside them.
        val table = FormattingStripState(inTable = true)
        assertEquals(RowSplit(top = 2, bottom = 11), split(table, 412))
        assertEquals(13, split(table, 412).let { it.top + it.bottom })
    }

    @Test
    fun everythingInTheTopRowNeedsNoSecondRow() =
        assertEquals(RowSplit(top = 11, bottom = 0), split(FormattingStripState(mode = StripMode.Preview), 800))

    @Test
    fun pairsAreNeverSplitAcrossTheRows() {
        // Outdent and Indent end the top row together or start the bottom row together, at any width.
        for (mode in StripMode.entries) {
            for (width in 200..1000) {
                val state = FormattingStripState(mode = mode)
                val keys = StripLayout.scrollingKeys(mode)
                val top = split(state, width).top
                val last = keys.getOrNull(top - 1)
                assertFalse("$mode at $width", last == StripKey.Outdent && keys.getOrNull(top) == StripKey.Indent)
            }
        }
        // Undo and Redo are pinned side by side, so they never part either.
        for (mode in StripMode.entries) {
            val pinned = StripLayout.pinnedKeys(mode)
            assertEquals(pinned.indexOf(StripKey.Undo) + 1, pinned.indexOf(StripKey.Redo))
        }
    }

    @Test
    fun twoRowsFallBackInLandscapeAndAboveAShortEditor() {
        assertEquals(StripRows.Two, StripLayout.rowsFor(StripRows.Two, landscape = false, roomAboveOneRow = 500.dp))
        assertEquals(StripRows.One, StripLayout.rowsFor(StripRows.Two, landscape = true, roomAboveOneRow = 500.dp))
        // About 200 dp must be left above two rows: 248 dp above one row is just enough.
        assertEquals(StripRows.Two, StripLayout.rowsFor(StripRows.Two, landscape = false, roomAboveOneRow = 248.dp))
        assertEquals(StripRows.One, StripLayout.rowsFor(StripRows.Two, landscape = false, roomAboveOneRow = 247.dp))
        assertEquals(StripRows.One, StripLayout.rowsFor(StripRows.One, landscape = false, roomAboveOneRow = 900.dp))
    }
}
