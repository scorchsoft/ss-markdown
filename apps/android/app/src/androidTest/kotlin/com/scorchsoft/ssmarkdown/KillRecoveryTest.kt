package com.scorchsoft.ssmarkdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.memory.AndroidMemorySource
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.FileDocumentProvider
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Kill and recovery on a device (J4): edits made and flushed (as onPause
 * does) come back in a store built afresh over the same app storage,
 * which is what a new process sees after the old one was killed. The
 * file on disk is never touched.
 */
@RunWith(AndroidJUnit4::class)
class KillRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "kill-test").apply { deleteRecursively() }
    private val file = File(root, "notes.md")

    private fun store(): DocumentStore {
        val provider = FileDocumentProvider { uri -> if (uri.value == URI) file else null }
        val journals = JournalStore(File(root, "journal"), FlushScheduler { _, _ -> Cancellable { } })
        val memory =
            AndroidMemorySource(
                context.getSystemService(android.app.ActivityManager::class.java),
                context.getSystemService(android.os.storage.StorageManager::class.java),
                context.filesDir,
            )
        return DocumentStore(
            provider,
            journals,
            BaseCopyStore(File(root, "base")),
            MemoryGovernor(memory),
            Dispatchers.IO,
        )
    }

    @Test
    fun flushedEditsSurviveARestart() =
        runBlocking {
            root.mkdirs()
            file.writeText("on disk\n")
            withContext(Dispatchers.Main) {
                val before = store()
                val doc =
                    (
                        before.open(
                            com.scorchsoft.ssmarkdown.files.provider
                                .DocumentUri(URI),
                        ) as OpenOutcome.Opened
                    ).document
                doc.replace(0, 0, "typed ")
                before.flushJournals()
            }
            val after = store()
            val scan = withContext(Dispatchers.Main) { after.scanForRecovery() }
            assertEquals(listOf(URI), scan.offers.map { it.uri.value })
            val reopened = withContext(Dispatchers.Main) { after.open(scan.offers.single().uri) } as OpenOutcome.Opened
            assertEquals(
                "typed on disk\n",
                reopened.document.document.text
                    .toString(),
            )
            assertTrue(reopened.recovery is Recovery.Restored)
            assertEquals("on disk\n", file.readText())
        }

    private companion object {
        const val URI = "test:notes.md"
    }
}
