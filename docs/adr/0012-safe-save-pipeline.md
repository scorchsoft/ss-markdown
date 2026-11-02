# ADR 0012: Saves back up, record their intent, write, then verify

- **Status:** Accepted
- **Date:** 2026-11-02 (recording decisions made in iterations 1–3 and the kill test)
- **Deciders:** Scorchsoft

## Context

User files are reached through the Storage Access Framework. A `content://`
URI cannot be written to a temporary name and renamed, so the usual
atomic-rename save is not available. Some providers ignore truncation in
`"w"` mode and leave stale bytes; some hang; other apps edit the same
file. The kill test (KILL-1) showed that a kill in the middle of a write
could leave a file made of old and new bytes, which backup and verify
alone cannot repair after a restart.

## Options

1. **Write in place and hope.** What most editors do on Android.
2. **Write a sibling file and swap.** Needs folder access and rename,
   which a single-file grant does not give.
3. **A pipeline that makes every step recoverable**: precondition,
   backup, a durable intent record, write, read-back verify, and a
   launch-time scan that repairs an interrupted write.

## Decision

Option 3, in `feature/files/…/files/save/SavePipeline.kt`, in this order:

1. **Precondition**: the disk must hash to the base we loaded, or the
   outcome is a conflict and nothing is written.
2. **Backup**: the last saved bytes are in `BaseCopyStore` first.
3. **Intent**: the new bytes and the digests of old and new go to
   `PendingSaves`, synced, before the file is opened.
4. **Write** in `"wt"` mode, falling back to `"rwt"` plus truncate.
5. **Verify**: read back; length and SHA-256 must match. Only then is the
   document clean and its journal ended.

Any failure after the file was opened writes the backup back. A save past
its `SaveTimeout` ends as `TimedOut` with the file untouched or restored.
On launch, before any journal is read, `InterruptedSaves` finds intent
records: a file made only of old and new bytes goes back to the old ones
(the journal brings the edits back); a completed write is recognised as
ours; anything else is another app's edit and is never touched.

## Consequences

- The file on disk is always the old version or the new one, never a
  mixture: `InterruptedSaveTest`, `SavePipelineTest` (300 random one-shot
  faults), `SaveTimeoutTest` and `RandomKillTest` (many kills at a random
  byte of a write).
- Each save costs a backup, a synced intent and a full read-back: about
  three times the bytes of a plain write. That is the price of rubric #1,
  and it runs off the main thread.
- Power loss (whether `fsync` reaches flash) cannot be proved on the host
  and is device-only.
- `docs/robustness.md` §2 and §2a are the full contract.
