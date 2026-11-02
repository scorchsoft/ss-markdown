# ADR 0010: The module graph and its rules

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made at the scaffold)
- **Deciders:** Andrew (Scorchsoft)

## Context

The engine (text, Markdown, history, themes) has to be testable on the host in
seconds, and the code is a public example of how Scorchsoft structures an app
(`engineering.md`).

## Decision

```
app ──> feature/{editor, preview, files, fonts} ──> core/{text, markdown, history, theme, ui}
tools/editor-bench: a leaf that may depend on anything; nothing may depend on it
```

- `core:text`, `core:markdown`, `core:history`, `core:theme` are **pure JVM**
  (`ssmarkdown.jvm.library`, explicit API mode): no Android types, plain JUnit
  on the host.
- `core:ui` is the design system; `feature:*` are the engines (editor view,
  WebView host, storage and save pipeline, fonts).
- **Dependencies point inwards:** core never depends on a feature or `app`; a
  feature never depends on another feature or on `app`; nothing depends on
  `tools:*`, because the benchmark carries LGPL-2.1 Sora Editor.
- `checkModuleGraph` (the `ssmarkdown.module-graph` plugin, in `check`) reads
  every declared project dependency and fails the build, naming the edge, if a
  rule is broken. A pure-JVM core module cannot even resolve an Android
  feature, so that case fails earlier, at dependency resolution.

## Consequences

- The text engine, journal, history formats and theme CSS run in seconds on
  the host and carry the 90% coverage gate.
- Features cannot reach each other, so cross-feature behaviour is wired in
  `app`. In practice `app` has grown to hold every screen's state machine and
  view model (about a third of the code; code-craft review CC-7): the graph
  rule holds, but `app` is not thin. Moving pure reducers into a module is the
  next step when they are reworked.
- Package names do not yet map one-to-one onto modules (CC-10).
