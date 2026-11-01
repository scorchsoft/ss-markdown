# ADR 0004: The Preview's web bundle is committed

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made with the preview module)
- **Deciders:** Andrew (Scorchsoft)

## Context

Preview runs a TypeScript page (markdown-it in a worker, KaTeX, highlight.js,
Mermaid, DOMPurify) in a WebView (ADR 0007). Its source is
`apps/android/feature/preview/web/src`; esbuild turns it into
`feature/preview/src/main/assets/preview/` (about 3.4 MiB, 110 files, with
`LICENSES.txt` generated from the bundled packages).

## Options

1. **Build in Gradle:** a Node toolchain (or the Gradle Node plugin) becomes
   part of every Android build, in CI, for F-Droid and on every contributor's
   machine.
2. **Commit the built assets:** Gradle consumes plain files; only someone
   changing the page needs Node.

## Decision

Commit the built assets. `npm run build` writes them; they are committed in the
same commit as the `src/` change that produced them.

## Consequences

- An Android build needs no Node, network or npm registry, which keeps it
  simple and closer to reproducible for F-Droid.
- Reviewers see the exact JS that ships, including size changes.
- The assets can go stale: edit `src/`, forget to rebuild. CI's `preview-web`
  job rebuilds and fails if the committed assets differ (the build is
  deterministic), and enforces the 3.5 MiB budget. The e2e tests drive the
  built bundle, so they also fail on a stale one.
- Diffs of minified chunks are noise; review the `src/` diff and the size line
  that `build.mjs` prints.
