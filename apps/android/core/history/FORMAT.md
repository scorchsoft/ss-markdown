# Recovery journal format, version 2

The journal is the first defence in `docs/robustness.md` §1: an
append-only log of edits, one file per open document that has unsaved
changes or undo history worth keeping. Since version 2 it also carries
the editor's undo and redo steps, so Undo survives a process death
(`docs/adr/0016-undo-survives-process-death.md`).
This file is the byte-level contract. The code in
`src/main/kotlin/.../history/journal/` is the reference implementation.

All integers are **little-endian**, as in LevelDB. Strings are
**WTF-8**: UTF-8 for any valid text, plus lone surrogates encoded as
three bytes, so every Java/Kotlin `String` round-trips exactly.

## File header

| Field | Type | Notes |
|---|---|---|
| magic | 4 bytes | `SSMJ` |
| formatVersion | u16 | `2` (version 1 is still read). Read before anything else; a newer version is left untouched. |
| bodyLength | u32 | Bytes of the body that follows (at most 64 KiB). |
| createdAt | i64 | Epoch millis when this file was written. |
| baseSize | i64 | Size in bytes of the file the edits start from. |
| baseLastModified | i64 | That file's `lastModified`. Informational: not part of the base match. |
| baseSha256 | 32 bytes | SHA-256 of that file's bytes. |
| keyLength | u32 | Bytes of the document key. |
| documentKey | WTF-8 | Opaque to the journal; usually the content URI. |
| headerCrc | u32 | Masked CRC32C of everything from `formatVersion` to the end of the body. |

A journal is only replayed onto a base whose size and SHA-256 match.

## Records

Framing follows LevelDB's `doc/log_format.md`:

```
[length u32][crc u32][type u8][seq u64][payload: length bytes]
```

- `length` is the payload length, at most 1 GiB.
- `crc` is the **masked** CRC32C (`java.util.zip.CRC32C`) of type, seq
  and payload. Masking, from LevelDB, is `rotr(crc, 15) + 0xa282ead8`;
  it keeps a CRC strong over data that itself contains CRCs, as a
  snapshot can.
- `seq` goes up by exactly one per record within a file. Compaction
  carries the sequence on, so a writer's numbers never go backwards.

| Type | Name | Payload | Since |
|---|---|---|---|
| 1 | Op | `offset u32` (UTF-16 code units), `deletedLength u32`, `deleted`, `insertedLength u32`, `inserted` | 1 |
| 2 | Snapshot | the whole text; replaces everything before it, undo history included | 1 |
| 3 | Marker | `kind u8`, then a body that depends on the kind (below) | 1 |
| 4 | History | the undo and redo steps (below); replaces the history before it, changes no text | 2 |

An Op is applied only if `deleted` matches the text at `offset`. A
mismatch means the wrong base or a lost record, and is reported as
corruption, never applied.

Marker kinds:

| Kind | Name | Body | Since |
|---|---|---|---|
| 1 | Saved | a label to the end of the payload | 1 |
| 2 | Checkpoint | a label to the end of the payload | 1 |
| 3 | StepEnd | nothing: the newest undo step is closed | 2 |
| 4 | Undo | `opCount u32` (at least 1): the next `opCount` Ops are an undo | 2 |
| 5 | Redo | `opCount u32` (at least 1): the next `opCount` Ops are a redo | 2 |

## Undo history (version 2)

The journal records the undo stack's own decisions, never timestamps,
so replay never has to guess where one step ends. The editor reports
them in order with the ops (`UndoListener`), and replay follows the
same rules as `UndoStack`:

- **Op.** Applied to the text. Unless it belongs to an Undo or Redo, it
  joins the newest undo step while that step is open, else starts a new
  one, and it clears the redo steps.
- **StepEnd.** Closes the newest step. Written just before the op that
  starts the next step, and only when the step before could still have
  grown, so a typing run costs one marker.
- **Undo `n`.** Closes the newest step; the next `n` Ops are the inverse
  edits of one step, newest first. The step they undo moves to the redo
  steps (rebuilt from those Ops, so it is right even when the step is
  older than the history kept).
- **Redo `n`.** The same in the other direction: the next `n` Ops re-apply
  the next undone step, which moves back to the undo steps.
- **Snapshot.** Forgets the history. Version 2 never writes one without a
  History record straight after it.
- **History.** Replaces both stacks. Payload:

  ```
  flags u8              bit 0: the newest undo step is still open
  undoCount u32         then each undo step, oldest first:
    opCount u32           then opCount Op payloads (as type 1), in the order applied
  redoCount u32         then each redo step, the next to redo last, the same way
  ```

  Every count is checked against the bytes left before anything is
  allocated, and a payload with bytes left over is damage.

A step still open when the file ends counts as closed, as it does in a
restarted editor. An Undo or Redo the file ends inside of (its marker
and some of its Ops landed) is **rolled back**: its Ops are reversed, so
the text and the history are the ones from just before it. A torn tail
inside an ordinary step loses only the torn op.

**The tail cap.** History is kept to the newest steps whose edits add
up to at most 8 MiB (`JournalHistory.TAIL_MAX_BYTES`, the editor's
`UndoStack.HISTORY_TAIL_MAX_BYTES`), each edit counting
`12 + 2 × (deleted + inserted chars)`: undo steps from the newest back,
then redo steps from the next one to redo, each side ending at the
first step that does not fit. Replay applies the same cap to what it
rebuilds. The in-memory stack keeps up to 32 MB; after a restart, steps
beyond the tail are gone.

## Clean journals (version 2)

A journal whose records are History records only is **clean**: its
header's base is exactly the document's text, and it holds undo history
and no unsaved edits. A verified save leaves one (instead of deleting
the journal) while the document has undo steps, so Undo survives the
save and a later death. The first edit after it is appended as usual.
The launch scan tells clean journals apart by their record frames alone
(no payload is read) and does not offer them for recovery; opening the
document restores the history if the file still matches the base, and
deletes the journal if it does not. A clean journal for a document the
app does not reopen at launch is deleted once the launch has settled.
A journal with no records at all is not clean: it is a run killed before
its first edit landed, and is treated as it always was.

## Reading rules

- **Torn tail, legal.** If the file ends inside a record, or the rest
  of the file is zeros, replay stops there and succeeds with the text up
  to the last whole record. This is what a crash mid-append leaves.
- **Damage, reported.** A whole record with a bad CRC, an out-of-range
  length, a sequence gap, an unparseable payload or an Op that does not
  fit stops replay with `Corrupt`, carrying the text up to the last good
  record. As in LevelDB, a damaged length that points past the end of
  the file cannot be told apart from a torn tail.
- **Unknown version, read-only.** A newer `formatVersion` is reported
  as `Unsupported` and the file is never changed, moved or deleted.
- **Unparseable files are quarantined, never deleted.** They move to
  `quarantine/` in the journal directory.

## Writing rules

- Appends are buffered and synced in groups (`FlushPolicy`; by default
  after 1 s idle, at least every 5 s while typing, at once for 256 KiB).
- A failed write is truncated back to the last whole batch before
  anything else is appended, so a fragment never sits mid-file.
- Creating, resuming and compacting a journal all write `<name>.new`,
  `fsync` it, rename it over `<name>` and `fsync` the directory: the
  `android.util.AtomicFile` protocol. A `.new` beside its journal is an
  interrupted compaction and is deleted; a `.new` alone is quarantined.

## Older and newer versions

- **Version 1** (the 0.1.0 release candidate) has no History records and
  no marker kinds 3 to 5. It is still replayed, text only, with no undo
  history: its ops say nothing reliable about steps.
  `src/test/resources/journal/v1-notes.ssmj` is a checked-in version 1
  file, replayed by `UndoHistoryJournalTest`.
- **Downgrade.** A build that only knows version 1 reports a version 2
  journal as `Unsupported`: the document opens read-only and the journal
  is held, untouched, until the app is updated again. This is accepted
  (ADR 0016); it is the rule above, applied to ourselves.

## Changing the format

Bump `formatVersion`, keep a reader for every older version, and add a
migration test with a checked-in file of the old version. Never reuse a
record type or marker kind number.
