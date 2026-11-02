package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.memory.Admission
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint

/** What opening a document came to. */
sealed interface OpenOutcome {
    /**
     * Open, possibly read-only and possibly with recovered edits.
     *
     * @property document the shared entry; every window showing this URI gets the same one.
     * @property recovery what happened to an earlier session's unsaved edits, if there were any.
     */
    data class Opened(
        val document: OpenDocument,
        val recovery: Recovery? = null,
    ) : OpenOutcome

    /**
     * Too large for this phone's memory (robustness Part 2a).
     *
     * @property admission the numbers for the explanation.
     */
    data class TooLarge(
        val admission: Admission.Declined,
    ) : OpenOutcome

    /**
     * The provider failed: gone, no permission, or broken.
     *
     * @property failure what it said.
     * @property hasUnsavedEdits true when a journal for this URI is still waiting, untouched.
     */
    data class Failed(
        val failure: ProviderResult.Failure,
        val hasUnsavedEdits: Boolean,
    ) : OpenOutcome
}

/** What happened to unsaved edits from an earlier session (ux-spec "Recovery"). */
sealed interface Recovery {
    /**
     * Restored onto the unchanged file: "Your unsaved edits are back".
     *
     * @property editedAtMillis when the journal was last started.
     * @property damaged true if the journal had a damaged record and only the edits before it came back.
     */
    data class Restored(
        val editedAtMillis: Long,
        val damaged: Boolean,
    ) : Recovery

    /**
     * Restored, but the file changed meanwhile: the conflict sheet appears
     * instead of the banner. See [OpenDocument.conflict].
     *
     * @property editedAtMillis when the journal was last started.
     */
    data class Conflicted(
        val editedAtMillis: Long,
    ) : Recovery

    /**
     * The edits could not be applied and are kept exactly as they are: the
     * file changed and there is no base copy to replay onto, or the
     * journal is from a newer app version.
     *
     * @property reason a short, non-localised description for logs.
     */
    data class Kept(
        val reason: String,
    ) : Recovery
}

/**
 * A document with unsaved edits from an earlier session, from the launch
 * scan. Opening it runs the recovery.
 *
 * @property uri the document.
 * @property editedAtMillis when its journal was last started.
 * @property base the file the edits were made against.
 */
data class RecoveryOffer(
    val uri: DocumentUri,
    val editedAtMillis: Long,
    val base: BaseFingerprint,
)

/** Why an outside-change check ran (robustness §2a, "When we check"). */
enum class CheckReason(
    internal val thorough: Boolean,
) {
    /** Returning to the app or the file. */
    Resume(thorough = false),

    /** The few-second foreground poll, or a `ContentObserver` ping. */
    Periodic(thorough = false),

    /** Immediately before a save: always hashes. */
    BeforeSave(thorough = true),
}

/** What an outside-change check found. */
sealed interface OutsideChange {
    /** Nothing changed. */
    data object None : OutsideChange

    /** The buffer was clean and has been replaced by the disk version. See [DocumentEvent.Reloaded]. */
    data object Reloaded : OutsideChange

    /** The buffer has unsaved edits: a conflict is now pending. See [OpenDocument.conflict]. */
    data object Conflicted : OutsideChange

    /** The file is gone: "File no longer exists — Save as / Recreate". The text is kept. */
    data object Missing : OutsideChange

    /** The grant is gone: work stays in the journal; offer Save as. */
    data object PermissionLost : OutsideChange

    /**
     * The provider could not be asked.
     *
     * @property failure what it said.
     */
    data class Unavailable(
        val failure: ProviderResult.Failure,
    ) : OutsideChange

    /** The URI is not open. */
    data object NotOpen : OutsideChange
}
