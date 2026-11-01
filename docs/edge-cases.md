# Edge-case register

Every hostile situation we know of, with how we handle it and the test
that proves it. We can't promise nothing unexpected will ever happen. We
can promise that **every known case has a named defence and a test**,
and that every bug found in the wild adds a row here and a regression
test before it's fixed.

Status values: `planned` → `handled` (code merged) → `tested` (the test
is green in CI).

## Files and storage

| Case | Handling | Test | Status |
|---|---|---|---|
| Disk full during save (`ENOSPC`) | The write fails without touching the original thanks to the backup and verification. The doc stays dirty and we show "Storage full — free space or Save as". | Fault-injection provider (`SavePipelineTest`: a full disk part way is StorageFull and the old file is put back) | tested |
| Disk full for the journal or history | Check `getAllocatableBytes` before writing. If space is low, warn early ("Less than 50 MB free — recovery copies may stop"). History pruning runs first. The journal has 2 MB reserved and preallocated. | Unit test with a fake StorageManager | planned |
| File changed by another app between sessions, with no unsaved edits | Load the disk version and record "Changed outside" in history. Cursor and scroll are mapped through the diff; the old undo stack is discarded. | Outside-change scenarios (`DocumentStoreTest`: a clean buffer reloads silently, with a mapped cursor and no old undo) | tested |
| File changed by another app while we hold unsaved edits (open now or in the journal) | Never auto-apply: the conflict sheet offers merge, compare, keep file, or use mine. Autosave pauses. | Outside-change scenarios (`DocumentStoreTest`, `SchedulingTest`: autosave pauses on a conflict) | tested |
| Outside change with the same size in the same second, or a provider reporting `lastModified = 0` | Base fingerprint includes SHA-256; hash compared when metadata is ambiguous or unreliable, and always before save. | Outside-change scenarios (`SavePipelineTest`, `DocumentStoreTest`) | tested |
| File deleted or renamed by another app while open | The next save or focus check notices. Banner: "File no longer exists — Save as / Recreate". The text is kept. | `DocumentStoreTest` (a deleted file keeps its text); `FolderActionsTest` (rename and delete through a real resolver). Instrumented test still planned | tested (unit) |
| SD card ejected or cloud provider uninstalled | Same as a lost permission: work stays in the journal; offer Save as. | Fault-injection provider (`SavePipelineTest` and `DocumentStoreTest`: a revoked grant keeps the work dirty and journalled) | tested |
| Slow cloud provider (Drive, OneDrive) | All file I/O runs off the main thread with a progress indicator after 300 ms. A timeout offers Retry or Save locally. We never block the UI. | `SaveTimeoutTest`: a timed-out save is `SaveOutcome.TimedOut` with the file untouched or restored, and can be retried. The Retry / Save locally UI is the app's | tested (library) |
| Same file open twice (multi-window, or open again from the file manager) | One `DocumentStore` entry per URI; both windows share the same buffer. | `DocumentStoreTest` (one entry per uri, shared by every window). Instrumented test still planned | tested (unit) |
| Zero-byte, extension-less, or `text/plain` `.MD` (upper case) | Opens normally. MIME and extension matching ignore case. | `SafHelpersTest` (extensions match without regard to case) | tested |
| A provider's display name with `/` or `..` used as a file name (SEC-1, history export) | `SafeFileName` (feature/files) strips separators, control characters and leading dots, caps the length, and `inside()` checks the result stays in its directory. | `SafeFileNameTest` | tested |
| A crafted history zip: deep JSON nesting, huge declared lengths (SEC-4) | Parser depth limit 64; manifest ≤ 16 MiB and 100,000 versions; a version ≤ 64 Mi chars; StackOverflowError and OutOfMemoryError become `Invalid`. | `HostileImportTest` | tested |
| Binary file opened by mistake | NUL bytes or invalid UTF-8 → read-only with "This doesn't look like text". It is never saved over. | `DocumentStoreTest` (invalid UTF-8 and NUL bytes open read-only) | tested |
| Mixed line endings in one file | Preserved byte-for-byte. New lines use the file's dominant ending. | Round-trip corpus | tested |
| Mixed-EOL file: a line break deleted then restored by undo | Comes back in the file's dominant style (documented in `TextDocument`); every other line stays byte-exact. Acceptable for now; revisit if seen in the wild | `LineEndingsTest` | tested (known limitation) |
| A save interrupted by power loss | Backup + verify + journal; on next launch the file is old-or-new, never partial. | Restore path: `SavePipelineTest` (random one-shot faults leave the old version or the new). Kill test still planned (journal half proven: torn-tail tests in core/history) | handled |

## Never lose work: keystrokes against disk work

Every row here was found by the data-safety review: a keystroke that lands
on the main thread while the store is suspended on disk. The harness is
`InterleavingDispatcher` (feature/files and app tests), which types after
any chosen I/O hop before the store resumes.

| Case | Handling | Test | Status |
|---|---|---|---|
| Typing while a save ends the journal, or right after a quiet save (DS-1) | Every op is journalled by `OpenDocument`'s `EditObserver` as the buffer applies it; there is no "journal already running" cache to go stale. The save's bookkeeping runs in one step on the main thread after the write. | `InterleavingTest` (DS-1 rows), `NeverLoseWorkTest` (Code-view keystrokes during and after a save) | tested |
| Typing during the write of a save, then the journal restarts (DS-2) | One atomic `JournalStore.resume(…, replacing = oldBase)` from a snapshot taken in the same main-thread step; the file work is queued in order on the journal thread. | `InterleavingTest`, `JournalOwnershipTest` | tested |
| Recovery could not apply an earlier journal (`Recovery.Kept`) and the document is closed (DS-3) | The journal is *held*: no close, save or discard deletes it, `markClean` quarantines a journal on another base, and the first new edit moves it into quarantine. A banner says what happened. | `InterleavingTest` (DS-3), `JournalOwnershipTest` | tested |
| Typing while a clean buffer reloads from an outside change (DS-4) | The dirty check and the swap happen after the last suspension; a keystroke during the read becomes a conflict. | `InterleavingTest` (DS-4, every hop) | tested |
| Typing during an autosave (DS-5) | An autosave in flight is never cancelled; another is booked after it. A save holding the lock runs its bookkeeping `NonCancellable`. | `InterleavingTest` (DS-5) | tested |
| Typing during Save as (DS-6) | The target's journal starts from a snapshot before the old one is retired. | `InterleavingTest` (DS-6, every hop) | tested |
| Typing after "Use my version" or "Merge" while the disk is re-read (DS-7) | Use mine and clean merges take the buffer when the store applies them; a hand-settled merge made from an older buffer is refused (`BufferChanged`) and the sheet comes back. | `InterleavingTest` (DS-7) | tested |
| A merge or Use mine drops lines of "mine" (DS-8) | The buffer is checkpointed (`BeforeMerge`) and awaited, again if typing moved it, before it is replaced. | `InterleavingTest` (DS-8) | tested |
| History off, then Don't save, Keep the file's version or Discard edits (DS-9) | The prompts and the message say plainly that nothing is kept; with History on, checkpoints are awaited before the journal goes. | `NeverLoseWorkTest` (DS-9) | tested |
| Don't save, Exit, then the app is reopened in the same process (DS-10) | A buffer that left the store is never shown; the file is opened again from disk. | `NeverLoseWorkTest` (DS-10) | tested |
| Typing on a 5 MB line (DS-11, PERF-5) | The journal records the exact chars of each op, not the line; the Preview gets the same char edits. | `InterleavingTest`, `NeverLoseWorkTest` (bytes per keystroke on a 200k-char line) | tested |
| History records racing each other (DS-12) | One ordered queue; checkpoints return whether they are durable. | `InterleavingTest` (DS-8 checkpoint), `NeverLoseWorkTest` | handled |
| The journal cannot be written (disk refused, record too large) | The document turns unhealthy, a "Recovery copies have stopped" banner appears, and the next edit retries from a full snapshot. | `InterleavingTest` (a journal that cannot start) | tested |
| `fsync` on the main thread (PERF-6, PERF-7) | One dedicated journal thread owns every writer and the group-commit timer; `onPause` waits for it for at most 300 ms. | `InterleavingTest` (ordering on the queued executor); device StrictMode run still planned | handled |
| Another app writes while our save is hashing the file (DS-17) | A metadata check right before opening the file re-hashes if size or time moved. | `SavePipelineTest` (DS-17) | tested |
| Deleting a draft from the Navigator (DS-16, UX-1) | Moved to a trash; "Draft deleted · Undo" restores it; a draft open in memory is saved and closed first. | `DraftStoreTest`, `NeverLoseWorkTest` (DS-16) | tested |
| A Preview edit in flight across a rebind, or in the last 150 ms before pause (DS-13, DS-14) | Not yet handled: needs a session epoch in the page protocol and a page flush on pause (feature/preview). | — | planned |

## Text and input

| Case | Handling | Test | Status |
|---|---|---|---|
| Emoji, surrogate pairs, flags, ZWJ sequences, combining marks | All cursor, selection and anchor maths works in grapheme clusters (`BreakIterator`), never raw `char` indices. | Property tests with a grapheme corpus | handled |
| Right-to-left and mixed-direction text | The view uses `Bidi` per line; caret movement follows visual order. | `BidiCaretTest`. Visual caret order per line (`BidiCaret`) is behind `MarkdownCodeView.bidiCaretMovement` until the renderer draws bidi runs reordered; runs are drawn in logical order today | handled (caret; drawing planned) |
| One 5 MB line (minified JSON, base64 image) | Virtualised horizontally too. Colouring is capped at 10k characters per line. Wrap mode measures that line lazily. | Benchmark corpus | planned |
| Keyboard composing during rotation or toolbar tap | Commit composing text first (`finishComposingText`). | `EditingSession`/`MarkdownCodeView` commit composing text on configuration change, focus loss and every outside edit; rotation + keyboard matrix still planned | handled |
| Hardware keyboard or Chromebook | Ctrl+S, Ctrl+F, Ctrl+Z / Ctrl+Shift+Z, Ctrl+B / Ctrl+I, Tab to indent; arrows and Shift-select work. | `KeyCommandsTest`, `EditorViewBehaviourTest` (key routing); instrumented key-event tests still planned | handled |
| Paste of 50 MB | Chunked insert off the main thread with progress; undoable as one step; admission control applies. | `ChunkedPasteTest`: prepared off the main thread, inserted a slice per frame with progress, one undo step, typing held until it ends. Admission control is the host's (it owns the `MemoryGovernor`) | tested (editor) |
| Undo across a Preview edit | The Preview round-trip is applied as one piece-table operation and lands on the same undo stack. | Unit test | planned |
| Typing that changes block state (e.g. typing ``` or a table pipe) far up a huge file | Main thread re-tokenises only to the viewport (≤ 8 ms); the rest converges in idle slices. Follow-up: measured ~5,000 lines re-tokenised per state-changing edit on a 1.46M-line file; profile convergence once the editor renders colours | `IncrementalTokenizer` property and benchmark tests | handled (tune in iteration 2) |
| Known tokenizer simplifications: setext restyles one line; links close on the same line; ≤ 8 nested containers; footnote defs don't continue | Documented; revisit if seen in real files | `LineTokenizerTest` | handled (known limitation) |
| Heading or list toggle with the caret inside a fenced code block | Line commands read the tokenizer's fence state (`FenceStates`); code lines are never toggled, Enter inside a fence never continues a "list" | `FenceAwareEditTest` | tested |
| Code-block toggle between two fenced blocks | Unwrap uses the fence state, not "a fence-looking line is adjacent", so the text between two blocks is wrapped, never mistaken for a block | `FenceAwareEditTest` | tested |
| Indent, outdent or delete an item in a numbered list | Every level is renumbered: the level an item leaves closes its gap, the level it joins continues, a new sublist starts at 1; `1. 1. 1.` lists and custom starts are kept; numbers in fences are never touched | `IndentEditTest`, `ListNumberingPropertyTest` | tested |
| Many find matches in a huge file | Matches are sorted ranges; a frame finds the ones on screen by binary search, and edits move them | `FindHighlightsTest` | tested |

## Storage and clean-up

| Case | Handling | Test | Status |
|---|---|---|---|
| User runs Android "Clear cache" | Only the diagram/preview cache lives in `cacheDir`; history, drafts and journals are in `filesDir`, so nothing of value is lost | `StorageAccountantTest` (only the preview cache lives in cacheDir). Instrumented test still planned | tested (unit) |
| A clean-up runs while a file is open with unsaved edits | Bulk clears skip files with open dirty buffers and never touch journals or drafts | `ClearAndExportTest` (every clear previews exactly what it removes, keeping skipped files) | tested |
| A clean-up is interrupted (app killed mid-clear) | History deletions are per-file atomic (index rewritten with AtomicFile), so no file's history is left half-deleted | `CorruptionAndCrashTest` (index rewritten atomically; torn `index.ssv.new` deleted on load). Kill test still planned | handled |
| Import a history zip for a file that has changed since it was exported | Versions attach as a separate branch; the current file is never modified by an import | `ClearAndExportTest` (export then import attaches a separate branch); `ExportContentsTest` (the import picker lists an export's documents without writing) | tested |
| Journal disk-space preallocation, and quarantine retention | Reserve journal space up front; quarantine is pruned after 30 days and capped at 20 MB | Unit test | planned |
| A version-history delta is damaged on disk | CRC and SHA-256 checks reject it; reading reports `Corrupt` with the nearest intact keyframe; only versions between it and the keyframe below are lost, and a rewrite drops and logs them | `CorruptionAndCrashTest` (core/history) | done |
| Crash between appending to the history pack and renaming its index | The old index ignores the uncommitted pack tail, which is cut off before the next append; a torn `index.ssv.new` and orphan packs are deleted on load | `CorruptionAndCrashTest` (core/history) | done |
| A storage limit that cannot be met without touching protected versions | Retention stops and returns a `LimitShortfall`; latest, last-7-days and pinned versions are never removed | `RetentionTest` (core/history) | done |

## Share-in and Navigator

| Case | Handling | Test | Status |
|---|---|---|---|
| Append to a file open in the app with unsaved edits | Insert into that buffer (marked unsaved), never write the file underneath it. | Instrumented test | planned |
| Append to a file changed outside the app since last seen | Fingerprint check; the append applies to the current disk version (not our stale copy) and is recorded in history. | Outside-change scenarios | planned |
| Append to a read-only, conflicted, missing or huge file | The sheet explains and offers New note; admission control applies to huge targets. | Instrumented test | planned |
| Shared payload above the Binder limit, or shared as a stream URI | Read from the URI as a stream; size is checked before buffering. | Instrumented test | planned |
| Another app starts the editor with an "open this URI" extra (SEC-2) | `MainActivity` takes no URI from an extra. The share sheet's Open passes a one-time `OpenTokens` ticket (128 random bits, single use, one minute); anything else opens nothing. VIEW/EDIT filters are not BROWSABLE. | `LaunchIntentsTest` | tested |
| `file://` VIEW, EDIT or SEND pointing at the app's private files (SEC-3) | Only `content:` is accepted, in the manifest and in `IncomingRequests`, so nothing is read with the app's own identity. | `LaunchIntentsTest` | tested |
| Share as file with a provider display name like `../../files/settings.xml` (SEC-1) | `SafeFileName.inside(cache/shared, name)`; a failure to write or share does nothing. | `PlatformActionsTest` | tested |
| A picked theme, import zip or picture of hostile size (SEC-8) | `BoundedRead`: theme ≤ `ThemeCodec.MAX_BYTES`, zip ≤ 256 MiB, picture ≤ 32 MiB; older `import-*.zip` copies deleted. | `PlatformActionsTest`, `DocumentPicturesTest` | tested |
| Outline of a file with 10k+ headings | Built from the block index incrementally; the list is virtualised. | `OutlineTest` (ten thousand headings stay cheap to query and edit) | tested |
| Setext headings and headings inside code fences | The outline uses the same tokenizer state, so `#` inside a fence is never a heading. | Corpus test | planned |

## Rendering and preview

| Case | Handling | Test | Status |
|---|---|---|---|
| WebView renderer killed or crashed | `onRenderProcessGone` → recreate, re-anchor, "Preview restarted". The app never crashes. | Instrumented test (`chrome://crash`) | planned |
| WebView missing, disabled or mid-update | Preview unavailable notice; Code still works. Retry when the package returns. | Instrumented test with a mocked provider | planned |
| Mermaid diagram that never finishes or is enormous | Rendered in a worker with a timeout and a node-count cap → inline error box with the source. | Playwright corpus | planned |
| Malicious Markdown (script tags, `javascript:` links, iframes) | DOMPurify + strict CSP + no JS bridge exposed to content scripts; `file:` and `content:` links blocked; external links open in the browser after a tap. | Playwright security corpus | planned |
| Raw HTML forging the page's attributes without spaces (`title="x"data-s="0"`), with `/` separators, or with `contenteditable`; a `position:fixed` overlay (SEC-6) | The worker renames reserved attributes the way the HTML tokenizer reads them (`core/userHtml.ts`); DOMPurify keeps only the worker's data attributes and `contenteditable="false"`, and strips fixed, sticky and absolute positions and `z-index`. | `e2e/hostile.test.ts`, `unit/userHtml.test.ts` (Playwright and Vitest) | handled |
| Theme CSS that loads through escapes or `image-set` (`@\69mport`, `\75rl(…)`, `url("\68ttps:…")`) (SEC-5) | Comments removed and escapes decoded before checking, in Kotlin's `CssPolicy` and the page alike; such a theme is refused whole. The CSP stays the wall. | `ThemeCodecTest`; `e2e/hostile.test.ts` | tested (Kotlin) |
| Theme font faces that point anywhere but the page's own origin | The page applies `@font-face` rules only when that is all the CSS holds and every URL is on its origin; fonts are served from assets and `/fonts/` (font files only, inside the store). | `e2e/bridge.test.ts`, `FontPathHandlerTest` | tested (Kotlin) |
| A document of 15k+ sections rendered in the background (PERF-10) | The background walk keeps its place: 40 sections per tick, linear over the document. | `e2e/virtual.test.ts` | handled |
| A 200k-line paste reaching Split or Preview (PERF-11) | Line starts in a Fenwick tree; splices over 16k lines are rebuilt, not spread; an edit splices only the sections it touched. | `unit/sourceDoc.test.ts`, `e2e/virtual.test.ts` | handled |
| Opening or resyncing a 20 MB document in Preview (PERF-12) | The load is a header plus raw 256 Ki-char frames, cut from the immutable snapshot off the main thread; later messages wait behind it. | `FrameQueueTest`, `SharedVectorsTest`, `e2e/bridge.test.ts` | tested (Kotlin) |
| Relative pictures on a provider with opaque ids (Drive), or `..` past the granted folder (SEC-9) | Resolved through SAF (`findDocumentPath`, then children by name), climbing no higher than the tree root; the id fallback compares whole path segments. | `DocumentPicturesTest` | tested |
| A link with `mailto:?attach=…` or bidi control characters (SEC-7) | `LinkPolicy` keeps only subject, body, cc and bcc and drops bidi controls; links open with `CATEGORY_BROWSABLE`. | `LinkPolicyTest` | tested |
| A font download redirected away from Google (SEC-10) | Redirects followed by hand: at most three, HTTPS only, `fonts.googleapis.com` or `fonts.gstatic.com`. | `RedirectGuardTest` | tested |
| Very large embedded image | Decoded with downsampling to display size, counted against the memory budget. | Corpus test | planned |
| Hundreds of diagrams in one file | Rendered only while their blocks are mounted; SVG cache byte budget. | Benchmark corpus | planned |
| Dark mode white flash when the WebView loads | The WebView background is set to the theme colour before first paint. The page CSS is inlined in the shell. | Screenshot test | planned |
| Print/PDF sizes drifting with screen zoom or theme base size | The print stylesheet uses fixed pt/mm units (11 pt body, 20 mm margins) and ignores screen zoom; dark themes print light | Playwright print-to-PDF size test | planned |

## Device and system

| Case | Handling | Test | Status |
|---|---|---|---|
| Low memory / `onTrimMemory` | `MemoryGovernor` sheds caches in order; unsaved work untouched. | `am send-trim-memory` test | planned |
| Low-RAM device (≤ 3 GB, `isLowRamDevice`) | Starts in the lean budget. | Emulator profile | planned |
| Process death in the background | Journal restore; the UI comes back at the same file, view and position. | `am kill` test | planned |
| Rotation, fold, resize, theme or font-scale change mid-anything | See `robustness.md` §5a. | Rotation matrix | planned |
| System font scale 200% | Layouts reflow; chrome never truncates Save or Copy. | Screenshot tests at 1.0, 1.3 and 2.0 | planned |
| Tiny split-screen window (e.g. 300 dp high) | Split falls back to a single pane; bars stay. | Screenshot test | planned |
| Clock or timezone change | History ordering uses a monotonic sequence number; times are display-only. | Unit test | planned |
| App updated with an older journal or history format on disk | Versioned formats with migrations and tests. Unknown future versions are read-only, never deleted. | Migration tests | planned |
| Large clipboard or share (> about 1 MB) | Sent as a content URI (see `robustness.md` §5). | Instrumented test | planned |

## Build and release

| Case | Handling | Test | Status |
|---|---|---|---|
| Hand-over APK built debuggable, so R8 skips optimisation and the baseline profile (PERF-1) | `isDebuggable = !minifyDebug`; locale filter; ui-tooling's exported `PreviewActivity` excluded. `mapping.txt` archived with each APK. | CI "Verify the hand-over APK" step (debuggable, PreviewActivity, baseline.prof, licence texts) | done |
| A licensed component ships without its licence text (the VS Code MIT notice, CC-2) | Every `licences.txt` row names a packaged text; LICENSE and `core/text/NOTICE` are copied into assets at build time; the licences screen shows each in full. | `LicenceTextsTest` | done |
| Committed Preview bundle stale against `web/src`, or over budget (CC-3) | CI rebuilds (deterministic) and fails on any difference; 3.5 MiB budget on `assets/preview`. | CI `preview-web` job | done |
| Screenshot of a modal sheet shows only the screen behind it; a deleted test's PNG lingers (UX-24) | The harness draws every open window; sheet shots fail with fewer than two windows; the screenshot folder is emptied per run. | `ScreenshotTest.sheet` | done |
