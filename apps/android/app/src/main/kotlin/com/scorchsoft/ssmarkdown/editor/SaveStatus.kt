package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.files.save.isTimedOut

/** Why a save did not happen; the "<reason>" in "Couldn't save · <reason>". */
enum class SaveProblem {
    StorageFull,
    PermissionLost,
    FileMissing,
    VerifyFailed,
    BackupFailed,
    ProviderError,

    /** The storage app took too long; nothing is marked saved, and Retry or Save as… are offered. */
    TimedOut,
    ChangedOutside,
    ReadOnly,
    ;

    companion object {
        /** The problem behind [outcome], or null when it saved. */
        fun of(outcome: SaveOutcome): SaveProblem? =
            when (outcome) {
                is SaveOutcome.Saved -> null
                is SaveOutcome.Conflict -> ChangedOutside
                is SaveOutcome.Missing -> FileMissing
                is SaveOutcome.PermissionLost -> PermissionLost
                is SaveOutcome.StorageFull -> StorageFull
                is SaveOutcome.VerifyFailed -> VerifyFailed
                SaveOutcome.ReadOnly -> ReadOnly
                is SaveOutcome.BackupFailed -> if (outcome.storageFull) StorageFull else BackupFailed
                is SaveOutcome.ProviderError -> if (outcome.isTimedOut) TimedOut else ProviderError
            }
    }
}

/**
 * What the fixed 96 dp Save slot shows (docs/ux-spec.md, the Save-state
 * table). Red means "needs you": only [Save], [SaveAsRequired] and [Retry]
 * are red.
 */
enum class SaveButton {
    /** Muted ink check-in-circle, "All saved". */
    AllSaved,

    /** Red "Save" pill. */
    Save,

    /** Pill with a spinner, disabled. */
    Saving,

    /** Outline "Save as…": an untitled draft, already safe. */
    SaveAsDraft,

    /** Red "Save as…": a read-only file with edits. */
    SaveAsRequired,

    /** Outline "Save as…": a read-only file without edits. */
    SaveAsReadOnly,

    /** Red "Retry" after a failed save. */
    Retry,
}

/** The status line under the title when no breadcrumb replaces it. */
sealed interface SaveStatusLine {
    /** "Saved · 2 min ago" or "Autosaved · just now". */
    data class Saved(
        val atMillis: Long,
        val autosaved: Boolean,
    ) : SaveStatusLine

    /** "Unsaved". */
    data object Unsaved : SaveStatusLine

    /** "Saving…". */
    data object Saving : SaveStatusLine

    /** "Draft · kept on this phone". */
    data object Draft : SaveStatusLine

    /** "Unsaved · read-only" (with the source app when known). */
    data class UnsavedReadOnly(
        val source: String?,
    ) : SaveStatusLine

    /** The "Read-only" badge (with the source app when known). */
    data class ReadOnly(
        val source: String?,
    ) : SaveStatusLine

    /** "Couldn't save · <reason>", in the error colour. */
    data class Failed(
        val problem: SaveProblem,
    ) : SaveStatusLine

    /** "Autosave paused · <reason>". */
    data class AutosavePaused(
        val problem: SaveProblem,
    ) : SaveStatusLine

    /** Opened, never saved by us and not edited: "Opened · just now". */
    data class Opened(
        val atMillis: Long,
    ) : SaveStatusLine
}

/** What tapping the Save slot does in each state. */
enum class SaveTap { ShowAllSaved, Save, SaveAs, Retry, OpenReason, Nothing }

/**
 * Everything the save state depends on, as plain facts. The view model
 * gathers them from the document store and the autosave scheduler.
 */
data class SaveFacts(
    val isDraft: Boolean,
    val isDirty: Boolean,
    val readOnly: Boolean,
    val saving: Boolean,
    val autosave: Boolean,
    val lastFailure: SaveProblem? = null,
    val autosavePausedBy: SaveProblem? = null,
    val lastSavedAtMillis: Long? = null,
    val lastSaveWasAutosave: Boolean = false,
    val openedAtMillis: Long = 0,
    val readOnlySource: String? = null,
)

/**
 * The Save slot and status line for one moment.
 *
 * @property showDirtyDot the red dot beside the name; in autosave only while a save is pending or failed.
 */
data class SavePresentation(
    val button: SaveButton,
    val status: SaveStatusLine,
    val tap: SaveTap,
    val showDirtyDot: Boolean,
)

/**
 * The Save-state table as a pure function, so every row of
 * docs/ux-spec.md is a unit test rather than a screenshot.
 */
object SaveStateMachine {
    /** The presentation for [facts]; earlier rules win. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per row of the spec's table.
    fun present(facts: SaveFacts): SavePresentation {
        val dot = facts.isDirty
        if (facts.saving) return SavePresentation(SaveButton.Saving, SaveStatusLine.Saving, SaveTap.Nothing, dot)
        if (facts.isDraft) {
            return SavePresentation(SaveButton.SaveAsDraft, SaveStatusLine.Draft, SaveTap.SaveAs, false)
        }
        if (facts.readOnly) {
            return if (facts.isDirty) {
                SavePresentation(
                    SaveButton.SaveAsRequired,
                    SaveStatusLine.UnsavedReadOnly(facts.readOnlySource),
                    SaveTap.SaveAs,
                    true,
                )
            } else {
                SavePresentation(
                    SaveButton.SaveAsReadOnly,
                    SaveStatusLine.ReadOnly(facts.readOnlySource),
                    SaveTap.SaveAs,
                    false,
                )
            }
        }
        facts.autosavePausedBy?.takeIf { facts.autosave }?.let {
            return SavePresentation(SaveButton.Save, SaveStatusLine.AutosavePaused(it), SaveTap.OpenReason, true)
        }
        facts.lastFailure?.takeIf { facts.isDirty }?.let {
            return SavePresentation(SaveButton.Retry, SaveStatusLine.Failed(it), SaveTap.Retry, true)
        }
        if (facts.isDirty) {
            // In autosave the pill stays too, so Save can force the pending save at once.
            return SavePresentation(SaveButton.Save, SaveStatusLine.Unsaved, SaveTap.Save, true)
        }
        val status =
            facts.lastSavedAtMillis?.let { SaveStatusLine.Saved(it, facts.lastSaveWasAutosave) }
                ?: SaveStatusLine.Opened(facts.openedAtMillis)
        return SavePresentation(SaveButton.AllSaved, status, SaveTap.ShowAllSaved, false)
    }
}
