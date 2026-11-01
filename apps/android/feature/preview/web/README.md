# Preview bundle

The page that renders Markdown inside SS Markdown's Preview: TypeScript built
with esbuild into `../src/main/assets/preview/`. The built files are committed,
so Gradle never needs Node. Rebuild and commit them whenever `src/` changes.

```sh
cd apps/android/feature/preview/web
npm ci              # exact versions from package-lock.json
npm run build       # writes ../src/main/assets/preview and prints its size
npm test            # tsc, ESLint + Prettier, Vitest unit tests, Playwright e2e
```

The e2e tests drive the **built** bundle in Chromium through the same
MessagePort the app uses, so build before testing. Playwright's Chromium must
be installed (`npx playwright install chromium` on a new machine).

## How it fits together

```
Kotlin (PreviewView) ──WebMessagePort, JSON──▶ page main thread ──▶ render worker
                                                │  sections, DOM,      markdown-it + plugins,
                                                │  DOMPurify, Mermaid, highlight.js, KaTeX
                                                ▼  editing, gestures
```

| Path | What it does |
|---|---|
| `src/protocol.ts` | Every message, typed and versioned. Mirrored in Kotlin's `bridge/`. |
| `src/core/` | Pure logic, unit tested: sectioning, definitions, source map, minimal edits, tables, display width, Enter rules. |
| `src/render/` | markdown-it set-up, the inline source map plugin and renderer rules. Runs in the worker. |
| `src/worker/` | The render worker entry. |
| `src/page/` | The page: controller, virtualiser, scroll sync, outline, Mermaid, theme, gestures, bridge. |
| `src/page/editing/` | Tap-to-edit: sessions, run mapping, table editing and its toolbar. |
| `src/styles/` | Theme-variable CSS, highlight.js palette, print stylesheet and A4 page mode. |
| `src/shell/index.html` | The page shell and its Content Security Policy. |
| `test/corpus/` | Markdown that covers every syntax, plus the hostile security corpus. |
| `test/vectors/` | Golden vectors both halves run: every bridge message on the wire, and OT cases with their answers (Kotlin's `SharedVectorsTest`). |

### Rendering

- The source is split into **sections** at top-level blank lines outside
  fences, maths, front matter, lists and open HTML blocks (`core/sections.ts`).
  After an edit, re-splitting starts one section before the change and stops at
  the first old boundary it meets again; a property test checks it always
  equals a full split.
- Sections are keyed by content hash plus a hash of the document-wide
  definitions (link references, footnote numbering, abbreviations), which every
  render gets through markdown-it's `env`. Only sections whose key changed are
  sent to the worker, near-viewport ones first.
- Each block carries `data-line` / `data-line-end` from `token.map`. The worker
  emits them relative to the section, so a section that only moved is
  relabelled, not re-rendered.
- Sections more than about three screens away are unmounted to spacers;
  mounted ones use `content-visibility: auto`. Above 7M px the spacers are
  scaled down, so layout never overflows.

### Editing in Preview

Every rendered text run is `<span data-s data-e>`, its offsets in the source.
A run is emitted only when its text is a byte-for-byte copy of the source and
the source skipped before it is pure markup (`core/sourceMap.ts`), so an edit
can never land in a URL or an escape. Typing inside a run is written back after
150 ms as the minimal splice (`core/minimalEdit.ts`). Anything that crosses a
run edge, adds a line or pastes is intercepted and turned into a source edit,
and the caret comes back by source offset after the re-render. Raw HTML,
footnote and reference definitions, front matter, maths and diagrams are
read-only with an **Edit in Code** chip.

Nothing typed is ever left on screen without reaching the source:

- **Read-only inline runs.** Text inside raw inline HTML, autolinks and bare
  URLs, emoji, and any run whose edge touches an escape or a character
  reference (`\*x`, `&amp;`) is `contenteditable="false"`. A unit property
  test checks every visible character in the corpus, and fuzzed inline
  Markdown, is in an exact run or read-only.
- **Stray guard** (`page/editing/strayGuard.ts`). An IME composition cannot
  be cancelled in `beforeinput`, so a MutationObserver watches the block.
  Text added at the edge of unmapped content goes to the neighbouring run's
  edge; anything else (deleting an emoji, a run removed) is undone by
  re-rendering from source and reported as `editRefused`. The Playwright test
  `e2e/editSafety.test.ts` types, deletes and injects IME changes all over the
  corpus and checks the screen always equals a fresh render of Kotlin's text.

### Loading large documents

A `load` above 256 Ki characters arrives as a JSON header with `chunks: n`
followed by n raw text frames, cut off the main thread in Kotlin
(`FrameQueue`). Neither side builds or parses a JSON string the size of the
document, and nothing sent after a load can overtake it.

### Edits from both sides

Kotlin numbers every change; each reaches the page once, as an `edit` or an
`ack`. Preview edits are sent at once with `baseRev`, the last revision the
page saw. If Kotlin changed the text meanwhile (Code pane, undo, a format),
both sides rebase with the same operational transform (`core/ot.ts`, twin of
Kotlin's `editing/TextTransform.kt`; host inserts win ties), so neither
side's keystrokes are lost and nothing reloads. Only a revision gap makes the
page send `resync`, and its edits have already reached Kotlin by then.

### Formatting

Strip keys arrive as `format`; the page flushes typing and answers
`formatRequest` with the selection's source range. Kotlin's `PreviewEditor`
runs core:markdown's `EditCommand` in the document's style, sends the minimal
edits as an `edit`, then `select` to put the selection back.

Tables are written back through `core/table.ts` in their own style: compact,
spaced or padded. A padded table is re-padded, by display width, when the caret
leaves the cell, and only the lines whose bytes change are sent.

### Security

Rendering output passes DOMPurify before it reaches the DOM, under a CSP that
allows only this bundle's scripts and no network. Link schemes other than
http(s), mailto and tel are dropped. Attributes the page relies on (`data-s`,
`data-line`, …) are renamed when they appear in a document's own HTML. The
only channel to the app is the one MessagePort; nothing is exposed to content.

## Size budget

The bundle is about **3.4 MB** uncompressed, of which Mermaid is about 2.7 MB,
loaded lazily only when a diagram is on screen. To stay inside the budget the
build replaces `cytoscape` and its layouts (about 630 KB, used only by Mermaid's
mindmap and architecture diagrams) with a stub; those two diagram types are
recognised before Mermaid runs and show an inline box naming them, with a
**Show source** button. KaTeX ships woff2 fonts only.

## Dependencies

All versions are pinned exactly. Third-party licences are collected into
`assets/preview/LICENSES.txt` at build time, for the in-app licences screen,
and summarised in the repository's `NOTICE`. To update a library: bump it in
`package.json`, `npm install`, run `npm audit --omit=dev` (it must be clean),
build, test, and commit the lock file and the rebuilt assets together.
