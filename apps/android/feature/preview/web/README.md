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
mindmap and architecture diagrams) with a stub; those two diagram types show an
inline "couldn't draw" box with their source. KaTeX ships woff2 fonts only.

## Dependencies

All versions are pinned exactly. Third-party licences are collected into
`assets/preview/LICENSES.txt` at build time, for the in-app licences screen,
and summarised in the repository's `NOTICE`. To update a library: bump it in
`package.json`, `npm install`, run `npm audit --omit=dev` (it must be clean),
build, test, and commit the lock file and the rebuilt assets together.
