package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.editor.LaunchRequest
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.IntentFields
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.platform.LaunchIntents
import com.scorchsoft.ssmarkdown.platform.OpenTokens
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.settings.ShareDefaults
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

/**
 * A Direct Share target or a launcher shortcut arrives with a shortcut id
 * (ADR 0017). `ShareActivity` and `MainActivity` are exported, so any app
 * can send any id: a valid one only preselects the file in our sheet (the
 * user still taps Append) or opens it in the editor; anything else changes
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DirectSharePreselectTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private class Fixture(
        val f: EditorFixture,
        val notes: String,
        val ideas: String,
        val readOnly: String,
    ) {
        val grants =
            mutableMapOf(DocumentUri(notes) to true, DocumentUri(ideas) to true, DocumentUri(readOnly) to false)
        val appended = mutableListOf<String>()
        val targets =
            DirectShareTargets(
                object : ShortcutPublisher {
                    override val maxPerActivity = 4

                    override fun current() = emptyList<ShortcutSpec>()

                    override fun push(spec: ShortcutSpec) = Unit

                    override fun remove(ids: List<String>) = Unit

                    override fun reportUsed(id: String) = Unit
                },
                f.settings,
                { grants.toMap() },
                f.scope,
                UnconfinedTestDispatcher(),
            )
        val share =
            ShareViewModel(
                f.store,
                f.drafts,
                f.history,
                f.settings,
                f.vm,
                f.scope,
                onAppended = { appended += it },
                dateHeading = { "1 Nov 2026" },
            )

        /** What ShareActivity does with an incoming SEND carrying [id]. */
        fun begin(id: String?) = share.begin(SNIPPET, "example.org", targets.resolve(id))
    }

    private fun fixture(): Fixture {
        val f = EditorFixture(temp.root, dispatcher)
        val notes = f.file("notes.md", "# Notes\n")
        val ideas = f.file("ideas.md", "# Ideas\n")
        val readOnly = f.file("readonly.md", "# Read only\n")
        f.settings.update {
            AppSettings(
                recentFiles =
                    listOf(
                        RecentFile(notes, "notes.md", "Documents", 3),
                        RecentFile(ideas, "ideas.md", "Documents", 2),
                        RecentFile(readOnly, "readonly.md", "Documents", 1),
                    ),
                share = ShareDefaults(lastTarget = notes),
            )
        }
        return Fixture(f, notes, ideas, readOnly)
    }

    @Test
    fun `a valid id preselects its file, first in the list, and nothing is written before Append`() {
        val x = fixture()

        x.begin(DirectShareIds.of(x.ideas))

        val state = x.share.state.value
        assertEquals(x.ideas, state.target)
        assertEquals(x.ideas, state.targets.first().uri)
        assertEquals(SharePhase.Choosing, state.phase)
        assertEquals("# Ideas\n", x.f.disk("ideas.md"))
        assertTrue(x.appended.isEmpty())

        x.share.onAction(ShareAction.Confirm)

        assertEquals("# Ideas\n\n$SNIPPET\n", x.f.disk("ideas.md"))
        assertEquals(SharePhase.Done("ideas.md", x.ideas), x.share.state.value.phase)
        assertEquals("reported for Direct Share ranking", listOf(x.ideas), x.appended)
        assertEquals("# Notes\n", x.f.disk("notes.md"))
    }

    @Test
    fun `an unknown or forged id falls back to the normal sheet`() {
        for (id in listOf(DirectShareIds.of("test:never-opened.md"), FORGED_URI, "recent:0000000000000000", "", null)) {
            val x = fixture()
            x.begin(id)
            val state = x.share.state.value
            assertEquals("id=$id", x.notes, state.target) // The last-used file, as without an id.
            assertEquals(x.notes, state.targets.first().uri)
            assertEquals(SharePhase.Choosing, state.phase)
        }
    }

    @Test
    fun `an id for a file we can no longer write falls back`() {
        val x = fixture()
        x.begin(DirectShareIds.of(x.readOnly))
        assertEquals(x.notes, x.share.state.value.target)

        val y = fixture()
        y.grants.remove(DocumentUri(y.ideas))
        y.begin(DirectShareIds.of(y.ideas))
        assertEquals(y.notes, y.share.state.value.target)
    }

    @Test
    fun `an id for a file forgotten from recents falls back`() {
        val x = fixture()
        x.f.settings.update { it.copy(recentFiles = it.recentFiles.filterNot { r -> r.uri == x.ideas }) }
        x.begin(DirectShareIds.of(x.ideas))
        assertEquals(x.notes, x.share.state.value.target)
    }

    // The launcher long-press: MainActivity with ACTION_OPEN_RECENT and the id.

    private val openRecent = IntentFields(LaunchIntents.ACTION_OPEN_RECENT, data = null, type = null)

    private fun launch(
        x: Fixture,
        id: String?,
    ): LaunchRequest? =
        LaunchIntents.request(
            openRecent,
            openToken = null,
            redeem = tokens::redeem,
            recentShortcut = { x.targets.resolve(id) },
            sourceApp = { "Launcher" },
        )

    private val tokens = OpenTokens(clock = { 0L })

    @Test
    fun `a launcher shortcut with a valid id opens its file as one the user chose`() {
        val x = fixture()
        val request = launch(x, DirectShareIds.of(x.ideas))
        assertEquals(LaunchRequest(x.ideas, writable = true, source = null, fromAnotherApp = false), request)

        x.f.vm.start(request)

        assertEquals(
            x.ideas,
            x.f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
    }

    @Test
    fun `a launcher shortcut with an unknown id just opens the app`() {
        val x = fixture()
        x.f.settings.update { it.copy(lastFile = x.notes) }
        val request = launch(x, DirectShareIds.of("test:never-opened.md"))
        assertNull(request)

        x.f.vm.start(request) // The launch default: reopen the last file.

        assertEquals(
            x.notes,
            x.f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
    }

    private companion object {
        const val SNIPPET = "A snippet from Chrome"

        /** A raw URI posing as an id (SEC-2: no URI is taken from an extra). */
        const val FORGED_URI = "test:ideas.md"
    }
}
