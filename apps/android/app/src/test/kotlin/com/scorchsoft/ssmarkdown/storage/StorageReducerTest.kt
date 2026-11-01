package com.scorchsoft.ssmarkdown.storage

import com.scorchsoft.ssmarkdown.history.versions.ClearPreview
import com.scorchsoft.ssmarkdown.history.versions.ClearRequest
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The StorageConfirm board and the history-off flow, as pure transitions. */
class StorageReducerTest {
    private val preview = ClearPreview(versions = 642, files = 11, bytes = 18_400_000)

    @Test
    fun `every history clear asks with export first ticked`() {
        val confirm = StorageReducer.confirmFor(ClearKind.NotOpened, preview, days = 90)
        assertTrue(confirm.exportFirst)
        assertEquals(642, confirm.versions)
        assertEquals(ClearRequest.NotOpenedFor(90), confirm.request)
    }

    @Test
    fun `the cache is rebuildable, so it needs no export`() {
        val confirm = StorageReducer.confirmFor(ClearKind.Cache, null)
        assertFalse(confirm.exportFirst)
        assertNull(confirm.request)
    }

    @Test
    fun `unticking export makes it a permanent clear, and pins are kept by default`() {
        val state = StorageUiState(confirm = StorageReducer.confirmFor(ClearKind.All, preview))
        val unticked = StorageReducer.reduce(state, StorageAction.ToggleExportFirst)
        assertFalse(unticked.confirm?.exportFirst ?: true)
        assertEquals(ClearRequest.Everything(includePinned = false), unticked.confirm?.request)
        val pins = StorageReducer.reduce(unticked, StorageAction.ToggleIncludePinned)
        assertEquals(ClearRequest.Everything(includePinned = true), pins.confirm?.request)
        assertNull(StorageReducer.reduce(pins, StorageAction.CancelConfirm).confirm)
    }

    @Test
    fun `turning history off asks first, and delete asks again`() {
        val on = StorageUiState()
        val asking = StorageReducer.reduce(on, StorageAction.ToggleKeepHistory)
        assertEquals(TurnOffStep.Choose, asking.turnOff)
        assertTrue(asking.policy.recording)
        val sure = StorageReducer.reduce(asking, StorageAction.TurnOffAskDelete)
        assertEquals(TurnOffStep.ConfirmDelete, sure.turnOff)
        val off = StorageReducer.reduce(sure, StorageAction.TurnOffConfirmDelete)
        assertFalse(off.policy.recording)
        assertNull(off.turnOff)
        assertTrue(StorageReducer.reduce(off, StorageAction.ToggleKeepHistory).policy.recording)
    }

    @Test
    fun `limits change the policy and close the picker`() {
        val state = StorageUiState(picker = StoragePicker.Limit)
        val none = StorageReducer.reduce(state, StorageAction.SetLimit(StorageLimit.None))
        assertEquals(StorageLimit.None, none.policy.totalLimit)
        assertNull(none.limitBytes)
        assertNull(none.picker)
        assertEquals(30, StorageReducer.reduce(state, StorageAction.SetForget(30)).policy.forgetUnusedAfterDays)
    }

    @Test
    fun `defaults are 1 GB, 100 MB and never forget`() {
        val policy = StorageUiState().policy
        assertEquals(StorageLimit.Bytes(1_000_000_000), policy.totalLimit)
        assertEquals(StorageLimit.Bytes(100_000_000), policy.perFileLimit)
        assertNull(policy.forgetUnusedAfterDays)
    }
}
