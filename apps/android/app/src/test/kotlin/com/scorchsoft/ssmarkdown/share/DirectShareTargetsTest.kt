package com.scorchsoft.ssmarkdown.share

import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.MainActivity
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.platform.LaunchIntents
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.InMemorySettingsRepository
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.settings.ShareDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Direct Share targets for recent files (ADR 0017): which recents become
 * shortcuts, in what order, under what ids, and that the platform is only
 * called when something changed. The last test runs the real
 * `ShortcutManagerCompat` publisher on Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DirectShareTargetsTest {
    /** The platform, as a list of calls. */
    private class FakePublisher(
        override val maxPerActivity: Int = 15,
    ) : ShortcutPublisher {
        val published = linkedMapOf<String, ShortcutSpec>()
        val calls = mutableListOf<String>()
        var failNextPush = false
        var declineNextPush = false

        override fun current(): List<ShortcutSpec> = published.values.toList()

        override fun push(spec: ShortcutSpec): Boolean {
            calls += "push ${spec.id}"
            if (failNextPush) {
                failNextPush = false
                error("rate limited")
            }
            if (declineNextPush) {
                // What ShortcutManagerCompat does on API 29 while rate limiting: no throw, just false.
                declineNextPush = false
                return false
            }
            published[spec.id] = spec
            return true
        }

        override fun remove(ids: List<String>) {
            calls += "remove $ids"
            ids.forEach(published::remove)
        }

        override fun reportUsed(id: String) {
            calls += "used $id"
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val publisher = FakePublisher()
    private val grants = mutableMapOf<DocumentUri, Boolean>()

    private fun recent(
        name: String,
        location: String = "Documents",
        pinned: Boolean = false,
    ) = RecentFile("content://docs/$name", name, location, 0, pinned)

    private fun writable(vararg files: RecentFile) = files.forEach { grants[DocumentUri(it.uri)] = true }

    private fun targets(settings: InMemorySettingsRepository) =
        DirectShareTargets(publisher, settings, { grants.toMap() }, CoroutineScope(dispatcher), dispatcher)

    private fun ids() = publisher.published.keys.toList()

    @Test
    fun `only recents we may still write are offered`() {
        val a = recent("a.md")
        val readOnly = recent("readonly.md")
        val noGrant = recent("gone.md")
        writable(a)
        grants[DocumentUri(readOnly.uri)] = false
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a, readOnly, noGrant)))

        targets(settings).start()

        assertEquals(listOf(DirectShareIds.of(a.uri)), ids())
    }

    @Test
    fun `a file inside a folder the user granted counts as writable`() {
        val tree = "content://docs/tree/primary%3ANotes"
        val inFolder = RecentFile("$tree/document/primary%3ANotes%2Fa.md", "a.md", "Notes", 0)
        grants[DocumentUri(tree)] = true
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(inFolder)))

        targets(settings).start()

        assertEquals(listOf(DirectShareIds.of(inFolder.uri)), ids())
    }

    @Test
    fun `ranked like the share sheet, capped at four`() {
        val files = (1..7).map { recent("f$it.md") }
        val pinned = files[5].copy(pinned = true)
        val last = files[6]
        val recents = files.take(5) + pinned + last
        writable(*recents.toTypedArray())
        val settings =
            InMemorySettingsRepository(
                AppSettings(recentFiles = recents, share = ShareDefaults(lastTarget = last.uri)),
            )

        targets(settings).start()

        val expected = listOf(pinned, last, files[0], files[1]).map { DirectShareIds.of(it.uri) }
        assertEquals(
            expected,
            publisher.published.values
                .sortedBy { it.rank }
                .map { it.id },
        )
        assertEquals(
            ShortcutSpec(DirectShareIds.of(pinned.uri), "f6.md", "f6.md · Documents", 0),
            publisher.published[DirectShareIds.of(pinned.uri)],
        )
    }

    @Test
    fun `the platform's own cap wins when it is lower`() {
        val small = FakePublisher(maxPerActivity = 2)
        val recents = (1..5).map { recent("f$it.md") }
        writable(*recents.toTypedArray())
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = recents))

        DirectShareTargets(small, settings, { grants.toMap() }, CoroutineScope(dispatcher), dispatcher).start()

        assertEquals(2, small.published.size)
    }

    @Test
    fun `ids are short hashes, never the URI`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ASecret%2Fdiary.md"
        val id = DirectShareIds.of(uri)
        assertTrue(id.startsWith(DirectShareIds.PREFIX))
        assertEquals(DirectShareIds.PREFIX.length + 16, id.length)
        assertFalse(id.contains("diary") || id.contains("Secret") || id.contains("content"))
        assertEquals("stable", id, DirectShareIds.of(uri))
        assertTrue(DirectShareIds.of("$uri ").let { it != id })
    }

    @Test
    fun `turning the setting off removes them all, and on brings them back`() {
        val a = recent("a.md")
        val b = recent("b.md")
        writable(a, b)
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a, b)))
        targets(settings).start()
        assertEquals(2, ids().size)

        settings.update { it.copy(share = it.share.copy(directShare = false)) }
        assertTrue(ids().isEmpty())

        settings.update { it.copy(share = it.share.copy(directShare = true)) }
        assertEquals(2, ids().size)
    }

    @Test
    fun `a forgotten file's shortcut is removed`() {
        val a = recent("a.md")
        val b = recent("b.md")
        writable(a, b)
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a, b)))
        targets(settings).start()

        settings.update { it.copy(recentFiles = listOf(a)) }

        assertEquals(listOf(DirectShareIds.of(a.uri)), ids())
        assertTrue(publisher.calls.contains("remove [${DirectShareIds.of(b.uri)}]"))
    }

    @Test
    fun `a file whose grant was lost loses its shortcut on the next refresh`() {
        val a = recent("a.md")
        val b = recent("b.md")
        writable(a, b)
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a, b)))
        val targets = targets(settings)
        targets.start()

        grants.remove(DocumentUri(b.uri))
        targets.refresh()

        assertEquals(listOf(DirectShareIds.of(a.uri)), ids())
    }

    @Test
    fun `nothing is pushed again when nothing changed`() {
        val a = recent("a.md")
        writable(a)
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a)))
        val targets = targets(settings)
        targets.start()
        assertEquals(1, publisher.calls.size)

        // Settings that do not touch the shortcuts, a refresh, and a second process start.
        settings.update { it.copy(lineNumbers = false) }
        targets.refresh()
        targets(settings).start()

        assertEquals(listOf("push ${DirectShareIds.of(a.uri)}"), publisher.calls)
    }

    @Test
    fun `a refused push is retried on the next sync, and never reaches the user`() {
        val a = recent("a.md")
        writable(a)
        publisher.failNextPush = true
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a)))
        val targets = targets(settings)

        targets.start()
        assertTrue(ids().isEmpty())
        targets.refresh()

        assertEquals(listOf(DirectShareIds.of(a.uri)), ids())
    }

    @Test
    fun `a push the platform declines is retried on the next sync`() {
        val a = recent("a.md")
        writable(a)
        publisher.declineNextPush = true
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a)))
        val targets = targets(settings)

        targets.start()
        assertTrue(ids().isEmpty())
        targets.refresh()

        assertEquals(listOf(DirectShareIds.of(a.uri)), ids())
    }

    @Test
    fun `resolve maps an id back through our recents and grants, and nothing else`() {
        val a = recent("a.md")
        val readOnly = recent("readonly.md")
        writable(a)
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a, readOnly)))
        val targets = targets(settings)

        assertEquals(a.uri, targets.resolve(DirectShareIds.of(a.uri)))
        assertNull("unknown", targets.resolve(DirectShareIds.of("content://docs/never-opened.md")))
        assertNull("a URI posing as an id", targets.resolve(a.uri))
        assertNull("no write grant", targets.resolve(DirectShareIds.of(readOnly.uri)))
        assertNull(targets.resolve(null))
        settings.update { it.copy(share = it.share.copy(directShare = false)) }
        assertNull("setting off", targets.resolve(DirectShareIds.of(a.uri)))
    }

    @Test
    fun `a successful append is reported against its shortcut, unless the setting is off`() {
        val a = recent("a.md")
        writable(a)
        val settings = InMemorySettingsRepository(AppSettings(recentFiles = listOf(a)))
        val targets = targets(settings)

        targets.reportUsed(a.uri)
        settings.update { it.copy(share = it.share.copy(directShare = false)) }
        targets.reportUsed(a.uri)

        assertEquals(listOf("used ${DirectShareIds.of(a.uri)}"), publisher.calls.filter { it.startsWith("used") })
    }

    @Test
    fun `the real publisher makes long-lived share targets that open MainActivity by id`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val real = CompatShortcutPublisher(context)
        val spec = ShortcutSpec(DirectShareIds.of("content://docs/a.md"), "a.md", "a.md · Documents", 0)

        real.push(spec)

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        assertEquals(spec.id, shortcut.id)
        assertEquals("a.md", shortcut.shortLabel.toString())
        assertEquals("a.md · Documents", shortcut.longLabel.toString())
        assertEquals(setOf(CompatShortcutPublisher.CATEGORY_APPEND_TARGET), shortcut.categories)
        val intent: Intent = shortcut.intent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(LaunchIntents.ACTION_OPEN_RECENT, intent.action)
        assertEquals(spec.id, intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        assertNull("no URI travels with it", intent.data)
        assertEquals(listOf(spec), real.current())

        real.remove(listOf(spec.id))
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }
}
