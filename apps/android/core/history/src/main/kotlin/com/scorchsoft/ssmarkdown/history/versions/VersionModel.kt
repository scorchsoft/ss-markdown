package com.scorchsoft.ssmarkdown.history.versions

/**
 * A version's identity within one document's history: its sequence
 * number. Sequence numbers only ever go up, whatever the wall clock does
 * (docs/edge-cases.md, "Clock or timezone change"), so they order
 * versions; times are for display.
 *
 * @property seq the monotonic sequence number, unique within the document.
 */
@JvmInline
public value class VersionId(
    public val seq: Long,
) {
    override fun toString(): String = "v$seq"
}

/**
 * Why a version was recorded: the label History shows (docs/ux-spec.md, "Version history").
 *
 * @property code the byte stored in the index. Never reuse or renumber one.
 */
@Suppress("MagicNumber") // The codes are the on-disk format, listed once, here.
public enum class VersionKind(
    internal val code: Int,
) {
    /** The file as found on disk when it was opened. */
    Opened(1),

    /** Written by an explicit save. */
    Saved(2),

    /** Written by autosave. */
    Autosaved(3),

    /** Unsaved work captured at a checkpoint (5 minutes of editing, backgrounding, before a risky action). */
    Checkpoint(4),

    /** The file was changed by another app; recorded as its own node in the graph. */
    ChangedOutside(5),

    /** The text just before a restore replaced it. */
    BeforeRestore(6),

    /** Text appended from the share sheet or the Navigator. */
    Appended(7),
    ;

    internal companion object {
        fun of(code: Int): VersionKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One line of history: everything about a version except its text.
 *
 * @property id its sequence-number identity.
 * @property branch the line it belongs to: [MAIN_BRANCH] for the document itself, others for imports.
 * @property wallTimeMillis when it was recorded, by the device clock. Display only.
 * @property kind why it was recorded.
 * @property label a user's name for it (a pinned version's name), or null.
 * @property pinned true when the user chose "Keep forever"; no clean-up removes it unless asked to.
 * @property parent the version it was derived from, or null for a root.
 *   Outside changes and imports point at their own parents, so the history is a graph, not a line.
 * @property changes lines added and removed relative to the version recorded before it on its branch.
 * @property textLength the length of its text in UTF-16 units.
 * @property sha256Hex SHA-256 of the text's WTF-8 bytes; every restore is verified against it.
 * @property storedBytes bytes this version occupies in the pack (its keyframe or delta).
 * @property keyframe true when it is stored whole rather than as a delta.
 */
public data class VersionInfo(
    public val id: VersionId,
    public val branch: Int,
    public val wallTimeMillis: Long,
    public val kind: VersionKind,
    public val label: String?,
    public val pinned: Boolean,
    public val parent: VersionId?,
    public val changes: LineChangeSummary,
    public val textLength: Int,
    public val sha256Hex: String,
    public val storedBytes: Int,
    public val keyframe: Boolean,
) {
    /** Constants shared by every version. */
    public companion object {
        /** The branch that is the document itself; imports get their own. */
        public const val MAIN_BRANCH: Int = 0
    }
}

/**
 * Identity of a document's history: its URI. The content fingerprint
 * half of the key (docs/robustness.md §3, "Identity") is the head
 * version's hash, used by [VersionStore.findByFingerprint] to reattach
 * history after a file moves.
 *
 * @property uri the document's content URI (or path), exactly as the file layer names it.
 */
@JvmInline
public value class DocumentKey(
    public val uri: String,
) {
    /** The display name: the last path segment of the URI, decoded loosely. */
    public val displayName: String
        get() = uri.substringAfterLast('/').substringAfterLast("%2F").ifEmpty { uri }
}

/** Where version history gets the time. Injected so tests control it. */
public fun interface VersionClock {
    /** Milliseconds since the epoch. */
    public fun nowMillis(): Long

    /** The real clock. */
    public companion object {
        /** `System.currentTimeMillis()`. */
        public val SYSTEM: VersionClock = VersionClock { System.currentTimeMillis() }
    }
}
