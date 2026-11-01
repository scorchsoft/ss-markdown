# Robustness: never lose work, never choke on big files

Losing work is the one failure that makes an editor permanently
untrustworthy. It is also the most common complaint about Markdown
editors. This file is the contract. Every rule here has a test (see the
end), and rubric criteria 1 and 4 are scored against it.

## Part 1 — Never lose work: layered defences

Each layer assumes every layer above it has failed.

### 1. Recovery journal (survives crashes, process death and battery pulls)

- Each open document with unsaved changes has an **append-only journal**
  in app-private storage. The journal holds the base file's identity
  (URI, size, hash) and then a list of edit operations.
- The journal is flushed with `fsync` after about 1 s of idle typing, on
  `onPause`/`onStop`, and before any risky step (save, view switch,
  opening another file). Worst case, you lose the last second of typing.
- Every so often the journal is compacted into a full snapshot plus a
  fresh operation list, so replay stays fast.
- On launch, a journal that wasn't cleanly closed brings up "Recovered
  unsaved changes from 14:02", with Compare / Restore / Discard. Nothing
  is ever discarded silently.
- **Document text never goes into `onSaveInstanceState`.** The Bundle
  limit is about 1 MB, and going over it crashes the app with
  `TransactionTooLargeException`. Process death is handled by the
  journal; the Bundle holds only IDs and cursor positions.

### 2. Safe saves (a file is never left half-written)

- **Back up before overwriting.** The last saved version goes to
  app-private storage before every write.
- **Truncating write mode.** Writes use `openOutputStream(uri, "wt")` and
  never plain `"w"`: on Android 10+ some providers don't truncate with
  `"w"`, which leaves stale bytes at the end of a shorter file.
- **Verify after writing.** After the write we read the file back and
  compare its length and hash. The document is marked clean only after
  that check passes. If it fails, Save shows an error state, the backup
  is still there, and we offer Retry / Save as…
- **Conflict check.** Before writing, the file's `lastModified`, size and
  hash are compared with what we loaded. A mismatch shows the conflict
  banner (Compare · Reload · Keep mine) instead of overwriting.
- **Lost permission.** If file permission has gone (a temporary grant
  from another app, or a revoked folder), the work stays in the journal
  and we offer "Save as…". We never fail silently.

### 3. Local version history (cheap insurance, hidden UI)

- The last 20 saved versions of each file are kept in app storage,
  capped at 50 MB overall with the oldest pruned first.
- They are reached through ⋮ → Version history, with a diff against the
  current text and a Restore button. There is no other UI for this.

### 4. Byte-faithful files (no silent corruption)

- On open we detect the encoding (UTF-8 with or without BOM, or
  UTF-16), the line endings (LF or CRLF), and whether there's a final
  newline. On save we write back exactly the same.
- **Invalid UTF-8 opens read-only** with an explanation. Decoding it as
  replacement characters (`�`) and saving would quietly destroy the
  file.
- We never strip trailing whitespace or reflow text, and never
  normalise anything the user didn't edit. Edits in Preview are scoped
  to one block, and every other source line stays byte-identical.

### 5. Clipboard and share limits (avoiding a known crash)

- Android's clipboard and intents travel through Binder, which has a
  limit of roughly 1 MB. **Copy all** on a large document therefore
  sends the text as a content URI (FileProvider), so we don't put a huge
  string on the clipboard and crash. If the receiving app can't take a
  URI, we say "Too large to copy (5.2 MB) — Share as file instead".
- Share and Export do the same: files over a threshold go as a file,
  never as `EXTRA_TEXT`.
- Pasting very large text is chunked so it doesn't freeze the UI, and it
  can be undone in one step.

### 6. Leaving and switching

- Closing a dirty document, opening another, or closing a folder with
  dirty files always prompts. Back gesture included.
- Dirty buffers in folder mode are journaled exactly like single files.

## Part 2 — Large files: what's guaranteed at each size

The document lives in one model. The editor, preview and journal read
from it; they don't each keep their own full copy. We avoid holding
several full `String` copies, because each copy of a 20 MB file costs
about 40 MB of memory as UTF-16.

| Size | Mode | What changes |
|---|---|---|
| < 1 MB (almost every file) | Normal | Everything on. |
| 1–5 MB | Large | Syntax colours only for the visible region (always true), with fewer kinds. Spell-check suggestions off. Preview updates on pause rather than every keystroke. A quiet "Large file" chip in the status line. |
| 5–20 MB | Very large | Live preview paused (tap to render). Diagrams render only when scrolled into view. Line-number gutter is drawn lazily. |
| > 20 MB | Read-mostly | Opens in a view-and-search mode with a clear message. Editing is still offered, but the user is warned it may be slow. We check free memory **before** loading and never risk an out-of-memory crash. |

The thresholds are starting points. The benchmark spike below sets the
real numbers.

### Editor (Code pane)

- Highlighting is incremental: lines are tokenised individually, results
  are cached by line content, and colour spans are applied only to the
  visible region ±1 screen. The span count stays capped however long
  the file is.
- The line-number gutter and no-wrap mode are computed from the layout
  only for visible lines.
- Undo stores diffs, not snapshots, so memory stays bounded. Undo history
  survives a switch to Preview and back.
- All file input/output, hashing and journal writes run off the main
  thread. StrictMode is on in debug builds, so any slip shows up.
- The keyboard is told not to request the full text
  (`flagNoExtractUi`, no extracted text), because some keyboards copy
  the whole document on every keystroke.

**Gate — the editor-engine spike (first task of iteration 1).**
Benchmark the platform `EditText` against a 1 MB, 5 MB and 20 MB corpus
on a mid-range phone (Pixel 6a class). The corpus includes a single 1 MB
line and 100k short lines. Measure:
- open time,
- keystroke-to-screen latency,
- scrolling at 60 fps,
- memory use.

If `EditText` misses the targets in rubric criterion 4, the Code pane
switches to **Sora Editor** (`io.github.Rosemoe.sora-editor`,
Apache-2.0). It is a virtualised Android code editor built for large
files. The editor sits behind an `EditorEngine` interface from day one,
so the swap is local and the rest of the app doesn't change.

### Preview (WebView)

- **No whole-document handover on every keystroke.** Kotlin sends the
  changed line range over a `WebMessagePort`; it never pushes a 5 MB
  string through `evaluateJavascript`.
- **Incremental render.** Blocks are hashed, and only changed blocks are
  re-parsed and patched in the page.
- Parsing runs in a Web Worker, so the preview page never freezes.
- Off-screen blocks use `content-visibility: auto`.
- Mermaid and KaTeX render only when near the viewport, and results are
  cached by source hash so they never re-render unchanged.
- Each diagram has a render timeout. A diagram that fails or times out
  shows an inline error box with its source; it never blanks the page.

## Part 3 — How we prove it

| Test | What it proves |
|---|---|
| **Kill test** (instrumented, ×1000): kill the process with `kill -9` at random moments while typing, saving, switching views and doing Save all | After relaunch, recovered text equals the expected text to within the last ~1 s. The file on disk is always either the old version or the new one, never partial. |
| **Save-fault injection**: a fake provider that fails mid-write, ignores truncation, or revokes permission | The document stays dirty, the backup survives, and the user sees the error and a way out. |
| **Journal property tests** (JVM, randomised ops) | Replay equals the live buffer; compaction keeps that true; a torn final record is ignored safely. |
| **Round-trip corpus** | Opening and saving without edits is byte-identical, including CRLF, BOM, no final newline and odd encodings. A Preview edit changes only its own block. |
| **Large-file benchmarks** (macrobenchmark) | The size targets in the table above, plus no out-of-memory crash on 20 MB with a 256 MB heap. |
| **Clipboard/share at 5 MB** | No `TransactionTooLargeException`; the fallback works. |
| **Monkey + StrictMode** runs in CI | No ANRs and no main-thread disk access. |
