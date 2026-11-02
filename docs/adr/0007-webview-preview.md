# ADR 0007: Preview renders in a WebView with markdown-it

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made in iteration 0)
- **Deciders:** Andrew (Scorchsoft)

## Context

Preview must render GitHub-flavoured Markdown with footnotes, alerts, front
matter, KaTeX maths, Mermaid diagrams and highlighted code, offline, and must
print to PDF with proper typography. It must also be editable on tap
(ADR 0005) and safe with untrusted HTML in the document.

## Options

1. **Native rendering** (a Kotlin Markdown parser such as commonmark-java or
   Markwon, drawn with spans or Compose). Fast to start, no WebView, but
   Mermaid and KaTeX need a browser engine anyway, and printing would be ours
   to build.
2. **One reused WebView running markdown-it** and its plugins, with Mermaid,
   KaTeX, highlight.js and DOMPurify all bundled.

## Decision

Option 2 (`product-journey.md`, "Preview engine"). One WebView per Activity,
created only when Preview first shows. The page is served from an https
origin by `WebViewAssetLoader` so its Content Security Policy and module
workers behave as on the web. Parsing runs in a worker; the host and page talk
over a `WebMessagePort` carrying JSON, never `@JavascriptInterface`. Rendered
HTML goes through DOMPurify.

## Consequences

- Full fidelity with the web ecosystem the documents are written for, and
  print-to-PDF from the platform.
- The app is offline: everything is in the APK (ADR 0004), about 3.4 MiB of
  it.
- WebView start-up costs time and memory, so it stays lazy, and renderer death
  is survived rather than fatal (`robustness.md`).
- Two languages in one feature: the TypeScript page has its own toolchain and
  tests (Vitest, Playwright), run in CI.
- The bridge is the security boundary; the e2e security corpus guards it.
