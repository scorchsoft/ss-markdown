# Architecture decision records

One short record per significant decision: context, options, decision,
consequences. `docs/product-journey.md` is the story; these are the precise
record. Add the next number for a new decision, and supersede an old record
rather than rewriting it.

| ADR | Decision |
|---|---|
| [0001](0001-editor-engine.md) | The editor engine: our own virtualised `MarkdownCodeView` (provisional until device numbers) |
| [0002](0002-no-gradle-wrapper.md) | No Gradle wrapper |
| [0003](0003-no-di-framework.md) | Constructor injection and one `AppGraph`, no DI framework |
| [0004](0004-committed-preview-bundle.md) | The Preview's web bundle is committed |
| [0005](0005-edit-sync-protocol.md) | Preview editing syncs with operational transform |
| [0006](0006-history-in-app-storage.md) | Version history lives in private app storage |
| [0007](0007-webview-preview.md) | Preview renders in a WebView with markdown-it |
| [0008](0008-piece-tree.md) | The text model is a piece tree ported from VS Code |
| [0009](0009-font-bundling.md) | Which fonts ship in the APK |
| [0010](0010-module-graph.md) | The module graph and its rules |
| [0011](0011-recovery-journal.md) | The recovery journal records every op and is written by one thread |
| [0012](0012-safe-save-pipeline.md) | Saves back up, record their intent, write, then verify; interrupted saves are repaired at launch |
| [0013](0013-emulator-evidence.md) | Performance evidence comes from CI's emulator, gated only where it is stable |
| [0014](0014-leakcanary-test-apk-only.md) | LeakCanary runs over the instrumented tests and never ships |
| [0016](0016-undo-survives-process-death.md) | Undo survives process death, through the recovery journal (format 2) |
| [0017](0017-direct-share-targets.md) | Recent files are Direct Share targets that preselect, never append |
