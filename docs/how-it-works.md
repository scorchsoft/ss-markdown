# How SS Markdown stays fast on huge files, and why you never lose work

These are user-facing explanations of the engineering that sets SS
Markdown apart. The precise specs live in `robustness.md` and
`performance-techniques.md`.

## The text engine: a piece tree (as in VS Code)

**The usual approach, and why it lags.** Most mobile editors, including
popular open-source ones (see `research-findings.md`), keep your
document in the platform's standard text field: one long string laid
out from top to bottom. That's fine for a shopping list. On a large
file, though, every keystroke in the middle means shuffling megabytes of
text along by one character and re-measuring the whole document. That's
the typing lag and freezing people complain about in reviews of those
apps.

**What SS Markdown does instead.** It uses a **piece tree**, the
structure Microsoft built for VS Code's editor in 2018:

1. **The file you opened is never modified.** It sits in an "original"
   buffer, read once and stored in 64K-character chunks.
2. **What you type is appended** to a separate "added" buffer.
3. **The document is a list of pieces**, each pointing into one of those
   buffers, a bit like a playlist.

```
Original buffer:  "The quick fox"
Added buffer:     "brown "
Pieces:           [original "The quick "] [added "brown "] [original "fox"]
Reads as:         "The quick brown fox"
```

Typing appends a few characters and splits one piece. **Nothing is
copied, whatever the file size.**

**Why a tree.** The pieces live in a balanced tree. Every branch
remembers how many characters and line breaks sit to its left, so
"jump to line 80,000" takes about 20 steps instead of a walk through
the whole file. Microsoft measured a 35 MB file falling from about
600 MB of memory in their previous design to roughly the size of the
file.

**What it buys you:**
- **Typing feels the same** in a 20 KB note and a 20 MB log.
- **Opening a file doesn't make extra copies of it.** Big files open
  quickly and use less memory.
- **Background work never stalls typing.** Syntax colouring, saving,
  the preview and history diffs read cheap frozen snapshots of the
  document on other threads.
- **Your line endings are kept.** Windows (`\r\n`) and Unix (`\n`)
  endings are remembered and written back exactly, so an open-and-save
  never changes a byte you didn't touch.
- **Cheap undo.** Each edit is recorded as "removed this, inserted
  that", so undo costs memory in proportion to what you changed, not to
  the file size.

**Only what's on screen is drawn.** Both the editor and the preview
render just the lines near the viewport, as code editors like
CodeMirror do. A million-line file costs no more to scroll than a short
note.

## Never losing work

There are three independent layers, and each one assumes the others
might fail:

1. **Recovery journal.** Every edit is appended to a checksummed log
   within about a second, kept in private app storage. If the app
   crashes, the phone dies or Android kills the app in the background,
   your edits come back the next time you open it.
2. **Safe saves.** Before overwriting, the last saved version is backed
   up. After writing, the file is read back and checked. It counts as
   saved only once that check passes. If another app changed the file in
   the meantime, nothing is overwritten and you choose what happens.
3. **Version history.** Saved and unsaved checkpoints are stored as
   compressed snapshots plus line-by-line diffs, the same trade-off git
   and Mercurial make. That gives a long, restorable history for a few
   megabytes.

## From keystroke to disk (for contributors)

The path one typed character takes, with the files that carry it. Paths
are from the repository root; `…` stands for `src/main/kotlin/com/scorchsoft/ssmarkdown`.
`docs/robustness.md` §1 and §2 are the contract each step keeps.

1. **The view.** The keyboard talks to
   `apps/android/feature/editor/…/editor/CodeInputConnection.kt`
   (`commitText`, `setComposingText`, …), owned by the Code pane's view,
   `apps/android/feature/editor/…/editor/MarkdownCodeView.kt`.
2. **The editing session.** Each verb becomes one edit in
   `apps/android/feature/editor/…/editor/EditingSession.kt` (with
   `SessionKeys.kt`, `SessionReads.kt` and `SessionHistory.kt` beside it),
   which also batches the cursor reports back to the keyboard. Main thread
   only.
3. **The text.** The edit is applied to the piece tree,
   `apps/android/core/text/…/text/TextDocument.kt`. Before applying an op it
   tells every `EditObserver` (`apps/android/core/text/…/text/EditObserver.kt`)
   the op's exact characters.
4. **The open document.** `apps/android/feature/files/…/files/document/OpenDocument.kt`
   holds the observer that journals; it hands each op to
   `apps/android/feature/files/…/files/document/JournalLink.kt`, which queues
   it in main-thread order. `DocumentStore.kt` in the same folder owns every
   open document and the dirty state the header shows.
5. **The journal thread.** `apps/android/feature/files/…/files/document/JournalExecutor.kt`
   (`DedicatedJournalThread`) is the only thread that writes the journal,
   through `apps/android/core/history/…/history/journal/JournalStore.kt`,
   `JournalWriter.kt` and `RecordCodec.kt` (format: `apps/android/core/history/FORMAT.md`).
   `FlushPolicy.kt` and `FlushScheduler.kt` group-commit an `fsync` after
   about a second of idle typing, on pause and before risky steps. From
   here the keystroke survives a kill (ADR 0011).
6. **Save.** Save, autosave and Save as go through
   `apps/android/feature/files/…/files/save/SavePipeline.kt`: fingerprint
   check, backup, a write-ahead intent (`PendingSaves`), the write, a
   read-back verify. Only then is the document clean and its journal
   ended (ADR 0012).

The tests that hold this path together are `InterleavingTest` and
`RandomKillTest` (feature/files), `NeverLoseWorkTest` (app) and the
journal property tests in core/history; `docs/edge-cases.md` names the
rest.

## Where history is stored, and why

History and the recovery journal live in **SS Markdown's private app
storage**, not as extra files next to your documents. That was a
deliberate choice:

| | App storage (what we do) | A sidecar file or folder next to the document |
|---|---|---|
| **Robustness** | Atomic writes (write, sync, rename) and real `fsync` | Android's file-access system often can't rename, so a crash mid-write can corrupt the history file itself |
| **Speed** | Local and fast | Every save writes twice, sometimes through a slow cloud provider |
| **Synced folders** (Syncthing, Drive, Dropbox) | Unaffected | Two devices writing the same history file create sync conflicts and "conflicted copy" clutter |
| **Git repositories** | Unaffected | Pollutes your repo with files you'd have to ignore |
| **Privacy** | Deleted text stays on your phone | Deleted text travels with a shared folder |
| **Read-only files** (email attachments) | Still get history | Impossible: there's nowhere to write |
| **Clutter** | None | Extra files beside every document |
| **Survives uninstall / another device** | No | Yes |

**The trade-off we accept.** History is per device and is removed if
you uninstall the app. Your *files* are always plain Markdown you own.
History is a safety net, not the record of truth.

**Keeping storage in check:**
- The default limits are generous: 1 GB in total and 100 MB per file,
  adjustable or off. Normal use never gets near them, so nobody is
  surprised by a missing version. Limits only ever thin old
  intermediate versions, and every removal is logged on the Storage
  screen.
- Older versions thin out over time, like Time Machine.
- History for files you haven't opened in 90 days is pruned first.
- Pinned versions are kept forever.

**If you need history to travel:** *Export history* (in Version
history) saves a file's versions as a `.zip` that can be imported
later. A per-folder "keep history with this folder" option, a single
hidden `.ssmarkdown/` folder, is on the list for after 1.0, opt-in
only, for people who want it and understand the sync trade-offs.
