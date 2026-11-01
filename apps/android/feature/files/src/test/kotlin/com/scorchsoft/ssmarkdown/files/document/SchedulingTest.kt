package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Autosave and the outside-change poll, on virtual time. */
class SchedulingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri = provider.put("content://docs/note.md", "text\n")

    private fun TestScope.fixture() = StoreFixture(tmp.root, provider, StandardTestDispatcher(testScheduler))

    @Test
    fun `autosave waits two quiet seconds`() =
        runTest {
            val store = fixture().store
            val autosave = AutosaveScheduler(backgroundScope, store, isEnabled = { true }).also { it.start() }
            val doc = store.open(uri).let { (it as OpenOutcome.Opened).document }

            doc.replace(0, 0, "a")
            advanceTimeBy(1_500)
            doc.replace(1, 0, "b")
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals("text\n", provider.text(uri))

            advanceTimeBy(2)
            runCurrent()
            assertEquals("abtext\n", provider.text(uri))
            assertFalse(doc.isDirty)
            assertNull(autosave.pausedBy(uri))
        }

    @Test
    fun `autosave pauses on a conflict and never overwrites`() =
        runTest {
            val store = fixture().store
            val autosave = AutosaveScheduler(backgroundScope, store, isEnabled = { true }).also { it.start() }
            val doc = store.open(uri).let { (it as OpenOutcome.Opened).document }
            doc.replace(0, 0, "mine ")
            provider.externalWrite(uri, "theirs\n".toByteArray())

            advanceTimeBy(2_001)
            runCurrent()

            assertTrue(autosave.pausedBy(uri) is SaveOutcome.Conflict)
            assertEquals("theirs\n", provider.text(uri))
            doc.replace(0, 0, "more ")
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("theirs\n", provider.text(uri))
        }

    @Test
    fun `autosave on leave saves at once`() =
        runTest {
            val store = fixture().store
            val autosave = AutosaveScheduler(backgroundScope, store, isEnabled = { true })
            val doc = store.open(uri).let { (it as OpenOutcome.Opened).document }
            doc.replace(0, 0, "x")

            autosave.onLeave(uri)
            runCurrent()

            assertEquals("xtext\n", provider.text(uri))
        }

    @Test
    fun `the foreground poll picks up an outside change`() =
        runTest {
            val store = fixture().store
            val results = mutableListOf<OutsideChange>()
            val monitor = OutsideChangeMonitor(backgroundScope, store, onResult = { _, change -> results += change })
            val doc = store.open(uri).let { (it as OpenOutcome.Opened).document }
            monitor.watch(uri)
            provider.externalWrite(uri, "changed\n".toByteArray())

            advanceTimeBy(OutsideChangeMonitor.DEFAULT_INTERVAL_MILLIS + 1)
            runCurrent()

            assertEquals(OutsideChange.Reloaded, results.first())
            assertEquals("changed\n", doc.document.text.toString())
            monitor.unwatch(uri)
        }
}
