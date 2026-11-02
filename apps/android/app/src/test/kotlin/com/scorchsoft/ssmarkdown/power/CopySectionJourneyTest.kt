package com.scorchsoft.ssmarkdown.power

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.LiveEditor
import com.scorchsoft.ssmarkdown.fixtures.TapCounter
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * J10, "Copy one section into a chat or email" (docs/quality-bar.md):
 * long-press the heading → Copy section, and the clipboard holds exactly
 * that section's Markdown: from its heading up to the next heading of the
 * same or a higher level, code fences and all, nothing of its neighbours.
 *
 * Both long-presses are driven end to end through the real view model and
 * the real screen:
 *
 * - **Preview**: the page's `headingLongPress` message (what
 *   `gestures.ts` posts; the Playwright e2e `editing.test.ts` proves a
 *   right-click or long-press on an `h1` posts it with the source line) is
 *   decoded by the real bridge codec and delivered to the editor's
 *   Preview session, exactly as the WebView bridge delivers it.
 * - **Navigator**: tap the title, then a real long-press on the heading row.
 *
 * Then a real click on "Copy section" in the sheet. The resulting event
 * goes to [PlatformActions.copy], the call the app's effects host makes
 * for it, and the test reads the system clipboard back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CopySectionJourneyTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun editor(): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("guide.md", DOCUMENT))
        rule.setContent { Themed(dark = false) { LiveEditor(f) } }
        rule.waitForIdle()
        return f
    }

    /** Taps "Copy section" in the open section menu and returns what the clipboard then holds. */
    private fun copySection(
        f: EditorFixture,
        taps: TapCounter,
    ): String {
        val menu = f.vm.uiState.value.overlay
        assertTrue("the long-press did not open the section menu: $menu", menu is EditorOverlay.SectionMenu)
        assertEquals("Installing", (menu as EditorOverlay.SectionMenu).heading)
        taps.tap("Copy section")
        assertNull("Copy section left the sheet up", f.vm.uiState.value.overlay)
        val copied = f.events.filterIsInstance<EditorEvent.CopyText>().single()
        // What the effects host does with it (nav/EditorEffectsHost.kt).
        PlatformActions(context, Dispatchers.Unconfined).copy(copied.text)
        val clip =
            context.getSystemService(ClipboardManager::class.java).primaryClip ?: error("nothing on the clipboard")
        return clip.getItemAt(0).text.toString()
    }

    @Test
    fun `long-press a heading in Preview, Copy section, and the clipboard holds exactly that section`() {
        val f = editor()
        val taps = TapCounter(rule)
        val message =
            BridgeCodec.decode("""{"type":"headingLongPress","line":$HEADING_LINE,"level":2,"text":"Installing"}""")
                ?: error("the bridge dropped the page's message")
        rule.runOnIdle { message.deliverTo(f.vm.preview) }
        rule.waitForIdle()

        assertEquals(COPIED, copySection(f, taps))
        // Two taps, as J10 budgets them: the long-press and Copy section.
        assertEquals(2, taps.taps + 1)
    }

    @Test
    fun `long-press a heading in the Navigator, Copy section, and the clipboard holds exactly that section`() {
        val f = editor()
        val taps = TapCounter(rule)
        taps.tap("guide.md")
        rule
            .onAllNodes(hasText("Installing") and hasClickAction())[0]
            .performTouchInput { longClick() }
        rule.waitForIdle()

        assertEquals(COPIED, copySection(f, taps))
    }

    private companion object {
        /**
         * A section whose body holds a deeper heading, a fenced block with
         * a `#` line (not a heading) and straight quotes, between two
         * neighbours that must not leak in.
         */
        const val SECTION =
            "## Installing\n\n" +
                "Run \"setup\" first.\n\n" +
                "### On Linux\n\n" +
                "```sh\n# not a heading\n./install --quiet\n```\n\n"

        const val DOCUMENT =
            "# Guide\n\nIntro paragraph.\n\n" +
                SECTION +
                "## Using\n\nOpen a file.\n"

        /** The section's lines, joined: the blank line before "## Using" is in it, its line break is not. */
        val COPIED = SECTION.removeSuffix("\n")

        val HEADING_LINE = DOCUMENT.lines().indexOf("## Installing")
    }
}
