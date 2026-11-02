package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.OnLaunch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Share → Open raced the editor's start-up: the share sheet asked for its
 * file while the launch sequence was still recovering, and the launch
 * default ("reopen the last file") then opened over it. A file asked for
 * during start-up must win.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchOrderTest {
    @get:Rule val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()

    @Test
    fun `a file asked for while start-up runs is the one left open`() {
        val f = EditorFixture(temp.root, StandardTestDispatcher(scheduler))
        val last = f.file("last.md", "# Last\n")
        val shared = f.file("notes.md", "# Notes\n")
        f.settings.update { AppSettings(lastFile = last) }

        f.vm.start() // The Activity's first launch: recovery first, then the default.
        f.vm.start(LaunchRequest(shared, writable = true, source = null, fromAnotherApp = false))
        scheduler.advanceUntilIdle()

        assertEquals(
            shared,
            f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
        assertEquals(ViewMode.Code, f.vm.uiState.value.mode)
    }

    @Test
    fun `with nothing asked for, start-up reopens the last file`() {
        val f = EditorFixture(temp.root, StandardTestDispatcher(scheduler))
        val last = f.file("last.md", "# Last\n")
        f.settings.update { AppSettings(lastFile = last) }

        f.vm.start()
        scheduler.advanceUntilIdle()

        assertEquals(
            last,
            f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
    }

    @Test
    fun `a restored Activity comes back to the last file even when launches start a new draft`() {
        val f = EditorFixture(temp.root, StandardTestDispatcher(scheduler))
        val last = f.file("last.md", "# Last\n")
        f.settings.update { AppSettings(lastFile = last, onLaunch = OnLaunch.NewDraft) }

        // The process died in the background; Android rebuilds the Activity where the user left it.
        f.vm.start(restoring = true)
        scheduler.advanceUntilIdle()

        assertEquals(
            last,
            f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
    }
}
