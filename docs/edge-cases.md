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
| Disk full during save (`ENOSPC`) | The write fails without touching the original thanks to the backup and verification. The doc stays dirty and we show "Storage full — free space or Save as". | Fault-injection provider | planned |
| Disk full for the journal or history | Check `getAllocatableBytes` before writing. If space is low, warn early ("Less than 50 MB free — recovery copies may stop"). History pruning runs first. The journal has 2 MB reserved and preallocated. | Unit test with a fake StorageManager | planned |
| File changed by another app between sessions, with no unsaved edits | Load the disk version and record "Changed outside" in history. Cursor and scroll are mapped through the diff; the old undo stack is discarded. | Outside-change scenarios | planned |
| File changed by another app while we hold unsaved edits (open now or in the journal) | Never auto-apply: the conflict sheet offers merge, compare, keep file, or use mine. Autosave pauses. | Outside-change scenarios | planned |
| Outside change with the same size in the same second, or a provider reporting `lastModified = 0` | Base fingerprint includes SHA-256; hash compared when metadata is ambiguous or unreliable, and always before save. | Outside-change scenarios | planned |
| File deleted or renamed by another app while open | The next save or focus check notices. Banner: "File no longer exists — Save as / Recreate". The text is kept. | Instrumented test | planned |
| SD card ejected or cloud provider uninstalled | Same as a lost permission: work stays in the journal; offer Save as. | Fault-injection provider | planned |
| Slow cloud provider (Drive, OneDrive) | All file I/O runs off the main thread with a progress indicator after 300 ms. A timeout offers Retry or Save locally. We never block the UI. | Fake provider with a delay | planned |
| Same file open twice (multi-window, or open again from the file manager) | One `DocumentStore` entry per URI; both windows share the same buffer. | Instrumented test | planned |
| Zero-byte, extension-less, or `text/plain` `.MD` (upper case) | Opens normally. MIME and extension matching ignore case. | Unit test | planned |
| Binary file opened by mistake | NUL bytes or invalid UTF-8 → read-only with "This doesn't look like text". It is never saved over. | Corpus test | planned |
| Mixed line endings in one file | Preserved byte-for-byte. New lines use the file's dominant ending. | Round-trip corpus | planned |
| A save interrupted by power loss | Backup + verify + journal; on next launch the file is old-or-new, never partial. | Kill test | planned |

## Text and input

| Case | Handling | Test | Status |
|---|---|---|---|
| Emoji, surrogate pairs, flags, ZWJ sequences, combining marks | All cursor, selection and anchor maths works in grapheme clusters (`BreakIterator`), never raw `char` indices. | Property tests with a grapheme corpus | planned |
| Right-to-left and mixed-direction text | The view uses `Bidi` per line; caret movement follows visual order. | Corpus + screenshot test | planned |
| One 5 MB line (minified JSON, base64 image) | Virtualised horizontally too. Colouring is capped at 10k characters per line. Wrap mode measures that line lazily. | Benchmark corpus | planned |
| Keyboard composing during rotation or toolbar tap | Commit composing text first (`finishComposingText`). | Rotation + keyboard matrix | planned |
| Hardware keyboard or Chromebook | Ctrl+S, Ctrl+F, Ctrl+Z / Ctrl+Shift+Z, Ctrl+B / Ctrl+I, Tab to indent; arrows and Shift-select work. | Instrumented key-event tests | planned |
| Paste of 50 MB | Chunked insert off the main thread with progress; undoable as one step; admission control applies. | Instrumented test | planned |
| Undo across a Preview edit | The Preview round-trip is applied as one piece-table operation and lands on the same undo stack. | Unit test | planned |

## Share-in and Navigator

| Case | Handling | Test | Status |
|---|---|---|---|
| Append to a file open in the app with unsaved edits | Insert into that buffer (marked unsaved), never write the file underneath it. | Instrumented test | planned |
| Append to a file changed outside the app since last seen | Fingerprint check; the append applies to the current disk version (not our stale copy) and is recorded in history. | Outside-change scenarios | planned |
| Append to a read-only, conflicted, missing or huge file | The sheet explains and offers New note; admission control applies to huge targets. | Instrumented test | planned |
| Shared payload above the Binder limit, or shared as a stream URI | Read from the URI as a stream; size is checked before buffering. | Instrumented test | planned |
| Outline of a file with 10k+ headings | Built from the block index incrementally; the list is virtualised. | Benchmark corpus | planned |
| Setext headings and headings inside code fences | The outline uses the same tokenizer state, so `#` inside a fence is never a heading. | Corpus test | planned |

## Rendering and preview

| Case | Handling | Test | Status |
|---|---|---|---|
| WebView renderer killed or crashed | `onRenderProcessGone` → recreate, re-anchor, "Preview restarted". The app never crashes. | Instrumented test (`chrome://crash`) | planned |
| WebView missing, disabled or mid-update | Preview unavailable notice; Code still works. Retry when the package returns. | Instrumented test with a mocked provider | planned |
| Mermaid diagram that never finishes or is enormous | Rendered in a worker with a timeout and a node-count cap → inline error box with the source. | Playwright corpus | planned |
| Malicious Markdown (script tags, `javascript:` links, iframes) | DOMPurify + strict CSP + no JS bridge exposed to content scripts; `file:` and `content:` links blocked; external links open in the browser after a tap. | Playwright security corpus | planned |
| Very large embedded image | Decoded with downsampling to display size, counted against the memory budget. | Corpus test | planned |
| Hundreds of diagrams in one file | Rendered only while their blocks are mounted; SVG cache byte budget. | Benchmark corpus | planned |
| Dark mode white flash when the WebView loads | The WebView background is set to the theme colour before first paint. The page CSS is inlined in the shell. | Screenshot test | planned |

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
