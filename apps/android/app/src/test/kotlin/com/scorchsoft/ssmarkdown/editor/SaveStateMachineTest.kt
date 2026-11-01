package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.save.FileIntegrity
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of the Save-state table in docs/ux-spec.md, as facts in and a presentation out. */
class SaveStateMachineTest {
    private val clean = SaveFacts(isDraft = false, isDirty = false, readOnly = false, saving = false, autosave = false)

    @Test
    fun `clean shows the muted tick and the last save time`() {
        val p = SaveStateMachine.present(clean.copy(lastSavedAtMillis = 5))
        assertEquals(SaveButton.AllSaved, p.button)
        assertEquals(SaveStatusLine.Saved(5, autosaved = false), p.status)
        assertEquals(SaveTap.ShowAllSaved, p.tap)
        assertFalse(p.showDirtyDot)
    }

    @Test
    fun `clean in autosave says Autosaved`() {
        val p = SaveStateMachine.present(clean.copy(autosave = true, lastSavedAtMillis = 5, lastSaveWasAutosave = true))
        assertEquals(SaveStatusLine.Saved(5, autosaved = true), p.status)
        assertEquals(SaveButton.AllSaved, p.button)
    }

    @Test
    fun `never saved and untouched reads Opened`() {
        assertEquals(SaveStatusLine.Opened(7), SaveStateMachine.present(clean.copy(openedAtMillis = 7)).status)
    }

    @Test
    fun `unsaved is a red Save pill, Unsaved and the dot`() {
        val p = SaveStateMachine.present(clean.copy(isDirty = true))
        assertEquals(SaveButton.Save, p.button)
        assertEquals(SaveStatusLine.Unsaved, p.status)
        assertEquals(SaveTap.Save, p.tap)
        assertTrue(p.showDirtyDot)
    }

    @Test
    fun `saving is a disabled spinner pill`() {
        val p = SaveStateMachine.present(clean.copy(isDirty = true, saving = true))
        assertEquals(SaveButton.Saving, p.button)
        assertEquals(SaveStatusLine.Saving, p.status)
        assertEquals(SaveTap.Nothing, p.tap)
    }

    @Test
    fun `an untitled draft is an outline Save as, never red, never a dot`() {
        val p = SaveStateMachine.present(clean.copy(isDraft = true, isDirty = true))
        assertEquals(SaveButton.SaveAsDraft, p.button)
        assertEquals(SaveStatusLine.Draft, p.status)
        assertEquals(SaveTap.SaveAs, p.tap)
        assertFalse(p.showDirtyDot)
    }

    @Test
    fun `read-only with edits is a red Save as`() {
        val p = SaveStateMachine.present(clean.copy(readOnly = true, isDirty = true, readOnlySource = "Gmail"))
        assertEquals(SaveButton.SaveAsRequired, p.button)
        assertEquals(SaveStatusLine.UnsavedReadOnly("Gmail"), p.status)
        assertTrue(p.showDirtyDot)
    }

    @Test
    fun `read-only without edits shows the badge and an outline Save as`() {
        val p = SaveStateMachine.present(clean.copy(readOnly = true))
        assertEquals(SaveButton.SaveAsReadOnly, p.button)
        assertEquals(SaveStatusLine.ReadOnly(null), p.status)
    }

    @Test
    fun `a failed save is a red Retry with the reason`() {
        val p = SaveStateMachine.present(clean.copy(isDirty = true, lastFailure = SaveProblem.StorageFull))
        assertEquals(SaveButton.Retry, p.button)
        assertEquals(SaveStatusLine.Failed(SaveProblem.StorageFull), p.status)
        assertEquals(SaveTap.Retry, p.tap)
    }

    @Test
    fun `paused autosave is a red Save that opens the reason`() {
        val p =
            SaveStateMachine.present(
                clean.copy(isDirty = true, autosave = true, autosavePausedBy = SaveProblem.ChangedOutside),
            )
        assertEquals(SaveButton.Save, p.button)
        assertEquals(SaveStatusLine.AutosavePaused(SaveProblem.ChangedOutside), p.status)
        assertEquals(SaveTap.OpenReason, p.tap)
    }

    @Test
    fun `a pause only matters while autosave is on`() {
        val p = SaveStateMachine.present(clean.copy(isDirty = true, autosavePausedBy = SaveProblem.ChangedOutside))
        assertEquals(SaveStatusLine.Unsaved, p.status)
    }

    @Test
    fun `save outcomes map to the problem the status line names`() {
        assertEquals(SaveProblem.ReadOnly, SaveProblem.of(SaveOutcome.ReadOnly))
        assertEquals(SaveProblem.StorageFull, SaveProblem.of(SaveOutcome.StorageFull(FileIntegrity.Untouched)))
        assertEquals(SaveProblem.StorageFull, SaveProblem.of(SaveOutcome.BackupFailed(storageFull = true)))
        assertEquals(SaveProblem.BackupFailed, SaveProblem.of(SaveOutcome.BackupFailed(storageFull = false)))
        assertEquals(SaveProblem.FileMissing, SaveProblem.of(SaveOutcome.Missing(FileIntegrity.Untouched)))
        assertEquals(SaveProblem.PermissionLost, SaveProblem.of(SaveOutcome.PermissionLost(FileIntegrity.Restored)))
    }
}
