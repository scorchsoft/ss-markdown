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
- **Record framing** follows LevelDB's log format. Each record is
  length + CRC32C + sequence number + the operation. A torn record at
  the tail is legal and simply truncated; replay stops at the first bad
  CRC. The journal header names the base file's SHA-256, so a journal
  can never be replayed onto the wrong file.
- The journal is flushed with `fsync` (group commit, never per
  keystroke) after about 1 s of idle typing, on
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
- **App-private files** (journal index, history index, settings, themes)
  are written with `AtomicFile`: write `<name>.new`, sync, then rename.
  User files reached through Storage Access Framework URIs can't be
  renamed, so they rely on the backup and verify steps here.
- **Truncating write mode.** Writes use `openOutputStream(uri, "wt")` and
  never plain `"w"`: on Android 10+ some providers don't truncate with
  `"w"`, which leaves stale bytes at the end of a shorter file. If a provider
  rejects `"wt"`, we fall back to `"rwt"` plus `FileChannel.truncate`.
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

### 2a. Other apps edit our files: the disk is always the truth

We are never the only app touching a file: Syncthing, Drive, a desktop
editor and git clients all change files too. The invariant:

> **Nothing we hold — cached text, the journal, undo stacks, saved
> cursor positions, history — is ever applied over the file on disk
> unless the user chooses it.** Our copy is only "newer" when it
> carries unsaved edits, and those edits are merged or compared, never
> blindly applied.

**Fingerprints, not just timestamps.** `lastModified` alone is
unreliable: some providers report 0 or whole seconds, clocks skew, and a
same-length edit inside one second is invisible to it. Each document
therefore records a **base fingerprint**: size + `lastModified` +
SHA-256 of the bytes, taken when it was loaded or last saved. If the
metadata changes, we compare hashes. If the metadata is unchanged but
can't be trusted (it's 0, or a provider known for coarse times), we hash
anyway. For very large files we hash in the background, but the full
hash is always taken before a save.

**When we check**
- On open and reopen, including journal recovery after a restart.
- On returning to the app or the file (`onResume`, switching files).
- Every few seconds while the file is in the foreground (cheap metadata
  query, with a `ContentObserver` where the provider supports one).
- Immediately before every save, manual or auto. **A save requires the
  disk fingerprint to equal the base fingerprint.** If it doesn't, the
  save becomes the conflict flow. This is an invariant in code, not a
  UI check.

**What happens when the disk version is new**

| Our state | What we do |
|---|---|
| No unsaved edits | Silently load the file as it is now. Record a history entry "Changed outside SS Markdown". Map the cursor and scroll through the diff so you stay near the same place. **Discard the old undo stack**, because undoing into stale content would bring old text back. |
| Unsaved edits (open now, or recovered from the journal) | **Never auto-apply.** Show the file as it is now, plus the "changed since your unsaved edits" sheet with these choices: **Merge my edits** (a three-way merge), **Compare side by side**, **Keep the file as it is** (your edits stay in History), or **Use my version…** (behind a confirmation; the disk version is recorded in history first). Autosave is paused until you choose. |
| File deleted or moved | Your text is kept. Offer **Save as / Recreate**. |

**Three-way merge (the "Merge my edits" choice)**
- **Base** = the version our edits started from. It's always in history
  because we record "Opened" and "Saved" versions.
- **Ours** = the unsaved buffer. **Theirs** = the disk version now.
- Changes are merged line by line, as git does. Non-overlapping changes
  apply automatically. Overlaps are presented one by one (take theirs /
  take mine / edit). The result loads as an unsaved edit, and nothing is
  written until you save.
- It lives in pure JVM (`core/history`) and is property-tested.

**History is a graph, not a line.** Each version records its parent, so
an outside change becomes its own node, and your edits branch from the
base they actually started from. Restoring an old version always goes
through this same conflict check, so it can never silently overwrite a
newer outside change.

**Folder mode.** Files changed outside the app show a small "changed"
marker in the drawer. Clean buffers refresh silently (with a history
entry). Dirty buffers get the conflict sheet when you open them. Save
all skips conflicted files and lists them for you.

### 3. Version history (keyframes + diffs, like git or video)

This is a local, per-file history that costs almost nothing in storage.
It lives in a pure-JVM `core/history` module and is fully unit-tested.

**When a version is recorded**
- On open ("Opened · as found on disk").
- On every save, manual or auto ("Saved").
- At **checkpoints** of unsaved work: after 5 minutes of active editing,
  when the app goes to the background, and before risky actions (Reload
  from disk, "take theirs" in a conflict, Restore, a large paste, Replace
  all).

So even in manual-save mode, the unsaved work you never saved is in the
history.

**Storage format**
- **Reverse deltas.** The newest version is stored in full, and older
  versions are backward deltas from it, as in RCS/Fossil style. Opening
  the latest version is instant, and retention thinning simply drops
  entries from the old end without re-composing chains.
- Deltas are line diffs: common prefix and suffix are stripped first,
  lines are interned to ints, then linear-space Myers runs with a time
  and edit cap. Hitting the cap stores a keyframe instead.
- **Keyframe rule (from Mercurial's `revlogutils/deltas.py`).** Store a
  full deflated snapshot instead of a delta when any of these hold:
  - the delta is bigger than the text;
  - the deltas needed to rebuild the version would exceed 2× the text
    length (`LIMIT_DELTA2TEXT = 2`);
  - the chain reaches 50 (git's default pack depth). That keeps
  restore fast and limits how much one bad record can damage.
- Every version stores a SHA-256 of its full text. Rebuilding a version
  is verified against it. If a delta is corrupt, we fall back to the
  nearest keyframe, and only the versions between that keyframe and the
  bad delta are lost.
- Writes are atomic: append to a pack file, then rename the index, both
  in app-private storage where rename is atomic.
- Rough cost: a 200 KB document with typical edits produces deltas of
  about 0.1–2 KB, so 1,000 versions come to about 1–3 MB.

**Retention (thinning, like Time Machine)**
- Keep everything from the last 24 hours, then hourly for 7 days, then
  daily for 90 days, then weekly.
- Each file is capped at 20 MB and the whole history at 100 MB (both
  adjustable), with the oldest pruned first.
- Pruning only ever removes from the old end, or collapses a run into
  its newest member re-stored as a keyframe, so chains never break.
- **Pinned** versions (long-press → "Keep forever", optionally named) are
  never pruned.

**Restoring is never destructive**
- Before a restore, the current text is recorded as a version ("Before
  restore").
- The restored text loads as a dirty edit. In manual mode, the file on
  disk doesn't change until you save, and the restore can be undone.

**Identity**
- History is keyed by document URI plus a content fingerprint. "Save
  as…" copies the history to the new file.
- History is local only. It is cleared per file or all at once (folder
  ⋮ or Settings), and deleted on uninstall. That's why export, Save as
  and pinning exist.

### 3a. Save modes

| Mode | Writes to the file | Default |
|---|---|---|
| **When I tap Save** | Only when you tap Save or Save all, or answer "Save" at a prompt | ✔ |
| **Autosave** | After a 2 s pause in typing, and when leaving the file or app | |

- Both modes share the same recovery journal and version history, so
  either way every change is recoverable.
- In Autosave the header reads "Autosaved · just now". The Save button
  stays visible and forces an immediate save. The unsaved dot shows only
  while a save is pending or has failed.
- Autosave **pauses** (with a banner) when the file has changed on disk,
  when write permission has been lost, or when a save fails. It never
  overwrites someone else's change.
- A file can override the global mode through ⋮ → "Autosave this file".

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

### 5a. Rotation, folding, resizing and other configuration changes

Rotating moves the editor from a top/bottom split into a side-by-side
one. Unfolding, split-screen resizing, dark-mode or font-scale changes
and keyboard attachment do similar things. Any of them can rebuild
views, which is the classic way an Android editor loses text. Our rules:

1. **Views never own text.** The document lives in a `DocumentStore`
   that outlives Activities and lives as long as the process, and that
   store is backed by the journal. The editor and preview are bound to
   it; they hold no state of their own.
2. **The same view instance moves; it is never rebuilt.** The editor and
   the WebView are hosted with `movableContentOf`, so moving between
   split layouts keeps the same `EditText` and `WebView`, along with
   their cursor, selection, scroll, undo and keyboard connection.
3. **Avoid recreation where it's safe.** The manifest handles
   `orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation|density|uiMode|fontScale`
   itself, so most changes re-layout instead of recreating the Activity.
   Every remaining path (locale, process death, "Don't keep activities")
   still has to pass rules 1 and 2.
4. **Flush pending input first.** Before any layout change, the
   keyboard's in-progress word is committed (`finishComposingText`), and
   any half-done Preview block edit (an uncommitted `contenteditable`
   change or a table cell) is turned back into Markdown immediately.
   Preview edits also flush on every change, never only when the cursor
   leaves the block.
5. **Dialogs and sheets** (find & replace text, the theme editor,
   unsaved prompts) keep their inputs in saved state, so a rotation
   never wipes a half-typed search or theme.

### 6. Leaving and switching

- Closing a dirty document, opening another, or closing a folder with
  dirty files always prompts. Back gesture included.
- Dirty buffers in folder mode are journaled exactly like single files.

## Part 2 — Large files: virtualised everywhere

**Requirement:** a file's size decides how much *memory* we need, never
how much *work* each frame or keystroke costs. Both panes draw only
what's on screen, plus a small margin, the way a well-built list scrolls
a million rows.

### Text model (pure JVM, in `core/text`)

- The text is a **piece tree**, ported from VS Code's MIT-licensed
  design. It is one balanced tree of pieces in which each node also
  stores the length and line-feed count of its left subtree. Offset↔line
  lookups are O(log n) through that **one** structure. There is no
  separate line tree to drift out of sync.
- **Buffers are immutable chunks** of at most 64K chars, each with a
  precomputed `lineStarts` array. Files are decoded straight into chunks
  by a streaming `CharsetDecoder`, never into a whole-document `String`.
  A chunk never ends on `\r` or a high surrogate.
- Line endings are normalised to `\n` in memory. The original style
  (LF, CRLF, or mixed with a per-line map) is remembered and re-emitted
  on save, which removes the CRLF bug class.
- Typing appends to the last add-buffer chunk in place (VS Code's fast
  path). A last-line cache serves repeated reads.
- **Snapshots:** readers on background threads (tokenizer, save, history
  diff, preview sync) take an O(pieces) immutable snapshot. That's safe
  because buffers never change.
- **Compaction:** when the piece count grows past about
  2 × (chars / 64K) + 1,000 after heavy editing, the tree is rebuilt into
  fresh chunks during idle time. VS Code's own write-up names piece
  growth as the design's weak point. If snapshot copying shows up in
  profiles, the planned upgrade is a persistent B-tree with summaries
  (Zed's `sum_tree` design, Apache-2.0).
- An edit costs O(log n) regardless of file size. Finding the offset of
  line 80,000 costs O(log n). Opening a file never copies its text: the
  original buffer *is* the decoded file.
- Undo/redo and the journal record piece-table operations, not snapshots.
- This module is the heart of the app. It's Android-free, so it is
  property-tested on the host against a naïve `StringBuilder` reference
  with millions of random edits.

### Editor view (Code pane): virtualised

- A custom `MarkdownCodeView` draws only the visible lines plus about
  one screen above and below. Each line is laid out on its own and cached
  by **stable line ID + edit version** (not by content hash, which costs
  O(line length) per lookup and can collide). On API 29+, each visible
  line is also recorded into a pooled `RenderNode`, so scrolling redraws
  without re-laying out. The render margin leans towards the scroll
  direction (about 1,000 px ahead).
- **Long lines are chunked.** No layout is ever built for a whole huge
  line, such as a 1 MB base64 image or minified JSON. Only about 2,000
  chars around the viewport are laid out (10,000 when wrapped), and the
  rest is estimated (CodeMirror's rule). Unwrapped lines use
  `getRunAdvance` + `drawTextRun`; wrapped lines use `MeasuredText` +
  `LineBreaker` off the main thread. Scroll height comes from the
  line index: fixed line height in No wrap mode; in Wrap mode, measured
  wrap counts for lines already seen and estimates for the rest, refined
  as you scroll, with the scroll position anchored so nothing jumps.
- Syntax highlighting is a per-line tokenizer whose small end-of-line
  state (fence, front matter, `$$` math, HTML block, container stack)
  carries from one line to the next. After an edit, re-tokenising stops
  as soon as a line's new end state equals its cached one.
- **Lazy, with a time budget.** On the main thread we only tokenise up
  to the viewport, capped at about 8 ms. The rest runs on a background
  snapshot in idle slices, never more than about 100k chars past the
  viewport. Visible lines that haven't been reached yet are tokenised
  with a guessed start state and corrected when the accurate pass
  arrives (the VS Code and CodeMirror approach). So opening a fence on
  line 1 of a 200k-line file never freezes typing.
- Markdown's non-local rules are handled explicitly. A setext underline
  (`===` or `---`) re-styles the line above it. Reference definitions
  live in a separate document-wide index. Lazy continuation in lists
  and quotes is part of the container state.
- The keyboard connection is a custom `InputConnection` with full
  composing support. It never hands the keyboard more than a **32K-char
  window** centred on the selection (`getTextBefore/AfterCursor`,
  `getSelectedText`, `getExtractedText`); without that limit, select-all
  causes ANRs. Batch edits don't nest, and `updateSelection` fires only
  when the outermost batch ends. `restartInput` is called only when the
  text is replaced from outside. On API 30+ we set
  `setInitialSurroundingSubText`. Accessibility node text is capped at
  100K chars (the parcel-safe limit). This is
  the highest-risk component, so it carries the keyboard matrix (Gboard,
  Samsung, HeliBoard, plus a hardware keyboard) and its own tests.
- Native selection handles, the magnifier, the text-action toolbar,
  accessibility (a virtual node tree for visible lines) and drag-scroll
  are all implemented against the platform APIs.

**Gate — the editor-engine spike (first task of iteration 1).** Build a
minimal `MarkdownCodeView` on `core/text` and benchmark it against the
platform `EditText` and Sora Editor on a 1 MB, 20 MB and 100 MB corpus.
The corpus includes a single 5 MB line and 1M short lines. The
benchmarks run on a Pixel 6a-class phone and a 3 GB low-end phone, and
measure:
- open time,
- keystroke latency,
- 60 fps scrolling,
- peak memory,
- the keyboard matrix.

The decision and its numbers are recorded in an ADR
(`docs/adr/0001-editor-engine.md`).

Default expectation: our own view wins, because `EditText` lays out the
whole document. Note that **Sora Editor is LGPL-2.1**, not Apache. Using
it in an Apache-2.0 app is possible but brings relinking obligations. It
is a benchmark baseline, not the plan.

### Preview (WebView): virtualised too

- The page keeps a **block index** from markdown-it, with each block's
  source line range and a measured or estimated height.
- markdown-it isn't incremental, so the source is split into top-level
  **sections** at blank lines outside fences, lists and HTML blocks.
  Each section is keyed by hash. Reference definitions are collected
  across the whole document once and passed in through `env`. Only dirty
  sections, plus one neighbour, are re-parsed. Every block carries
  `data-line` / `data-line-end` from `token.map` for scroll sync (as the
  VS Code preview does).
- Sections near the viewport use `content-visibility: auto` with
  `contain-intrinsic-size: auto <estimate>px`, so the browser skips their
  layout and remembers real sizes. Beyond about 3 screens, sections are
  unmounted and replaced by spacers.
- Very tall documents get a **height scaler**: spacer heights are
  compressed above about 7M px, as CodeMirror does, because browser
  layout units overflow on huge pages. Scrolling mounts and unmounts
  blocks, and the scroll position is anchored to a block so nothing
  jumps.
- Parsing runs in a Web Worker on changed line ranges only. Kotlin sends
  edits over a `WebMessagePort`, never the whole document.
- **KaTeX renders in the worker** (`renderToString` needs no DOM), with
  `trust: false` and `maxExpand`/`maxSize` limits. **Mermaid needs the
  DOM**, so it runs on the main thread, one diagram per idle slice, with
  text-size and edge-count caps.
- Both render only in mounted blocks. The SVGs are cached
  by source hash with a byte budget (oldest evicted first). Each diagram
  has a render timeout: a failing or timed-out diagram shows an inline
  error box with its source and never blanks the page.
- Print/PDF is the only path that renders every block, and it shows a
  progress indicator.

### What still varies with size

With virtualisation in place, editing stays smooth at any size. What
changes with size is the **memory budget**, managed below. Optional
extras are dropped before anything essential:

1. Line-level diffs in the history view (a whole-file comparison is
   still offered).
2. Live preview while typing (it updates on pause instead).
3. Spell-check suggestions.
4. The diagram cache.
5. Syntax colouring of very long lines: colouring is limited to the
   first 10k characters of a line, and the rest draws plain.

A file is never refused for being large. It is refused only if it won't
fit in memory, and we say so before trying.

## Part 2a — The memory governor

Android tells us how much memory we have, so we use that information
instead of hoping. A small `MemoryGovernor` service owns it.

**Ceilings we read**
- `Runtime.maxMemory()` — this app's Java heap limit (often 256–512 MB).
  This is the real ceiling for our text.
- `ActivityManager.getMemoryInfo()` — available system memory, the
  `lowMemory` flag and its threshold.
- `ActivityManager.isLowRamDevice()` and `memoryClass` — on low-RAM
  devices we start in the conservative budget.
- `ComponentCallbacks2.onTrimMemory(level)`. Note that **since API 34
  only `UI_HIDDEN` and `BACKGROUND` are delivered**; the RUNNING_* levels
  never arrive. So during loads, big pastes and exports, the governor
  also **polls** `getMemoryInfo().lowMemory` and the heap headroom from
  `Runtime`, rather than waiting for a callback. We never set
  `largeHeap`; admission control uses `getMemoryClass()`.
- `StorageManager.getAllocatableBytes()` — free disk space, which is
  needed for the journal, history and saves.

**Admission control before loading**
- Estimate the cost of opening a file: decoded size × 2 (UTF-16) plus
  the line index, caches and a safety margin.
- If that fits within 40% of the free heap, open normally.
- If it fits within 70%, open in the lean budget (caches capped, live
  preview off), with a quiet notice.
- If it doesn't fit, explain: "This 180 MB file needs about 400 MB and
  this phone allows 256 MB." Offer a read-only streamed view (paged from
  disk) and nothing worse.
- Opening a second large file in folder mode counts against the same
  budget. Dirty buffers are never evicted; clean ones are, and they
  reload from disk on demand.

**Under pressure**
- When `onTrimMemory` fires or free heap drops below 15%, we shed in
  order: the diagram cache, line-layout caches, the tokenizer cache,
  inactive clean buffers, and the preview's mounted window. A quiet
  "Low memory — preview paused" notice may appear.
- Unsaved work isn't touched. It's already journalled, so even if the
  system kills the process, the recovery path above restores it.

**The WebView runs in a separate process. Its crash must not be ours.**
- The preview's renderer runs at `RENDERER_PRIORITY_IMPORTANT`
  (`setRendererPriorityPolicy(…, waivedWhenNotVisible = true)`).
- We implement `onRenderProcessGone`, which returns `true`, then removes
  and `destroy()`s the dead WebView. `detail.didCrash()` tells a renderer
  bug from an out-of-memory kill; after an OOM kill we reload with a
  lower preview budget. If we don't, the WebView renderer
  being killed for memory, or by a WebView update, **crashes the app**.
  Instead we drop that WebView, create a fresh one, re-render around
  the current anchor, and show "Preview restarted".
- A renderer that has crashed twice in a minute keeps the preview off
  until you tap "Retry". The editor keeps working throughout.

**Last resort**
- `OutOfMemoryError` is caught only at a few known allocation points:
  file load, export, image decode and ZIP. There we can unwind safely,
  release the partial allocation and report. It is never swallowed
  anywhere else.

## Part 3 — How we prove it

| Test | What it proves |
|---|---|
| **Kill test** (instrumented, ×1000): kill the process with `kill -9` at random moments while typing, saving, switching views and doing Save all | After relaunch, recovered text equals the expected text to within the last ~1 s. The file on disk is always either the old version or the new one, never partial. |
| **Save-fault injection**: a fake provider that fails mid-write, ignores truncation, or revokes permission | The document stays dirty, the backup survives, and the user sees the error and a way out. |
| **Journal property tests** (JVM, randomised ops) | Replay equals the live buffer; compaction keeps that true; a torn final record is ignored safely. |
| **Round-trip corpus** | Opening and saving without edits is byte-identical, including CRLF, BOM, no final newline and odd encodings. A Preview edit changes only its own block. |
| **Large-file benchmarks** (macrobenchmark, Pixel 6a-class and a 3 GB low-end phone) | Open, type and scroll targets at 1, 20 and 100 MB. Frame cost doesn't grow with file size. |
| **Memory-pressure tests**: a 128 MB heap emulator, `am send-trim-memory`, killing the WebView renderer (`chrome://crash` / `am kill`), and opening several large files | No out-of-memory crash; admission control refuses cleanly; the preview restarts after a renderer death; no unsaved work lost. |
| **Clipboard/share at 5 MB** | No `TransactionTooLargeException`; the fallback works. |
| **Outside-change scenarios** (instrumented + JVM): change the file externally (a) while open and clean, (b) while open and dirty, (c) between sessions with no journal, (d) between sessions with a journal, (e) same size and same second, (f) on a provider that reports `lastModified = 0`; plus delete, rename and replace with a different file of the same name | Stale text is never written or shown as current. The old undo stack is never used against new content. Every case ends in the matrix outcome above. |
| **Three-way merge property tests** (JVM) | Non-overlapping edits merge exactly as git's `merge-file` would; overlaps are always reported, never silently resolved. |
| **Rotation matrix** (instrumented): every view mode × {typing a half-finished word, mid-selection, a Preview paragraph edit, mid table-cell edit, find open, a sheet open, unsaved prompt open} × {rotate, fold/unfold, split-screen resize, dark-mode toggle, font-scale change, "Don't keep activities", `am kill`} | The text, cursor, selection, scroll, undo stack and dirty state are identical before and after. Zero characters lost. Runs on every PR. |
| **Keyboard matrix** (manual, each release): Gboard, Samsung Keyboard, HeliBoard | Toolbar actions and Preview edits respect the in-progress word; autocorrect never lands text in the wrong place. |
| **History property tests** (JVM): random edit sequences, then record, prune and restore | Every kept version rebuilds byte-identically; pruning never breaks a chain; a corrupt delta loses only the versions after it up to the next keyframe. |
| **Monkey + StrictMode** runs in CI | No ANRs and no main-thread disk access. |
