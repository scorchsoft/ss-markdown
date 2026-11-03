package com.scorchsoft.ssmarkdown.share

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * The share sheet is rebuilt by anything that recreates its Activity: a
 * rotation, a dark-mode switch, a window resize, a process death while it
 * waits. Its state lived in a per-Activity view model, and the shared text
 * was read only on a first create, so the rebuilt sheet came back empty,
 * with Append still enabled: the shared text was lost, and Append wrote a
 * blank append (a date heading, blank lines) into the chosen file.
 */
@RunWith(RobolectricTestRunner::class)
class ShareRecreateTest {
    private fun send(text: String) =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)

    @Test
    fun `a recreated share sheet still holds the shared text`() {
        val controller = Robolectric.buildActivity(ShareActivity::class.java, send("Quote to keep")).setup()
        ShadowLooper.idleMainLooper()
        assertEquals("Quote to keep", controller.get().sheetState.text)

        controller.recreate()
        ShadowLooper.idleMainLooper()

        assertEquals("Quote to keep", controller.get().sheetState.text)
    }

    /**
     * The sheet waits about 1.5 s on "Added to …" before it closes. A
     * rebuild in that window (a rotation, or a process death restored from
     * saved state) came back choosing, with Append enabled, and a second tap
     * appended the same text again.
     */
    @Test
    fun `a sheet rebuilt after its append finished stays done and cannot append again`() {
        // Robolectric's 16 MB memory class is under the test JVM's used heap, so the store would refuse to
        // open the new note.
        val am = RuntimeEnvironment.getApplication().getSystemService(ActivityManager::class.java)
        shadowOf(am).setMemoryClass(Int.MAX_VALUE / (1 shl 20))
        val controller = Robolectric.buildActivity(ShareActivity::class.java, send("Quote to keep")).setup()
        ShadowLooper.idleMainLooper()
        controller.get().viewModel.onAction(ShareAction.ChooseTarget(null))
        controller.get().viewModel.onAction(ShareAction.Confirm)
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.get().sheetState.phase !is SharePhase.Done && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        val done = controller.get().sheetState.phase
        assertTrue("the append finished: $done", done is SharePhase.Done)

        controller.recreate()
        ShadowLooper.idleMainLooper()

        assertEquals(done, controller.get().sheetState.phase)
        assertEquals("Quote to keep", controller.get().sheetState.text)
        // A stray Append on the rebuilt sheet writes nothing.
        controller.get().viewModel.onAction(ShareAction.Confirm)
        ShadowLooper.idleMainLooper()
        assertEquals(done, controller.get().sheetState.phase)
    }

    /**
     * A process death while the append is still writing: the rebuilt sheet
     * offered Append again, and if the first write had landed, a second tap
     * added the text twice. The sheet now saves that an append to its target
     * is in flight, and the rebuilt sheet checks the file before offering
     * Append (ShareDuplicateTest has the landed and not-landed cases).
     */
    @Test
    fun `a sheet rebuilt while its append was writing remembers it and says it didn't finish`() {
        val am = RuntimeEnvironment.getApplication().getSystemService(ActivityManager::class.java)
        shadowOf(am).setMemoryClass(Int.MAX_VALUE / (1 shl 20))
        val controller = Robolectric.buildActivity(ShareActivity::class.java, send("Quote to keep")).setup()
        ShadowLooper.idleMainLooper()
        controller.get().viewModel.onAction(ShareAction.ChooseTarget(null))
        controller.get().viewModel.onAction(ShareAction.Confirm)
        assertEquals(SharePhase.Working, controller.get().sheetState.phase)
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertTrue("an append in flight is saved", saved.getBoolean(ShareActivity.KEY_PENDING))
        assertNull("to a new note", saved.getString(ShareActivity.KEY_PENDING_TARGET))

        val rebuilt = Robolectric.buildActivity(ShareActivity::class.java, send("Quote to keep")).setup(saved)
        ShadowLooper.idleMainLooper()

        val state = rebuilt.get().sheetState
        assertEquals(SharePhase.Choosing, state.phase)
        assertTrue("the rebuilt sheet says the last Append didn't finish", state.interrupted)
        assertEquals("Quote to keep", state.text)
        // Let the first sheet's write finish, so nothing outlives the test.
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.get().sheetState.phase == SharePhase.Working && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }
}
