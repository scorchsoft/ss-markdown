# SS Markdown

A calm, fast Markdown editor for Android by Scorchsoft. It opens straight
to an editor and offers one-tap Code / Split / Preview, always-visible
Copy and Save, an unmistakable unsaved state, Mermaid, themes, pinch zoom
and a quiet folder mode.

**Read `docs/product-journey.md` first.** It holds the vision, the
principles ("features may be deep, chrome may not be busy") and the
reasons behind every decision so far. Then:

- `docs/ux-spec.md` — what each control does and when it appears.
- `docs/robustness.md` — the never-lose-work and large-file contract.
  Required reading before touching files, saving or the editor.
- `docs/rubric.md` — the definition of good. Score it at the end of
  every iteration.

## Status

Iteration 0 (design) is in progress: the mockups are under review and
there is no app code yet. Do not start the Android app until the mockups
are signed off.

## Planned layout (`apps/android`)

```
app/            single Activity, Compose screens, view models
  editor/       MarkdownEditText + visible-range span highlighter, pinch → text size
  preview/      WebView host, JS bridge (anchor sync, long-press, copy), print → PDF
  files/        SAF open/save/tree, conflict check, drafts + recovery journal
  fonts/        Google Fonts download + cache, served via WebViewAssetLoader
  assets/preview/  markdown-it + plugins, mermaid, katex, highlight.js, dompurify (pinned, LICENSES)
core/markdown   PURE JVM: tokenizer, line/anchor maths, theme JSON → CSS, settings model
core/ui         design system (light + dark, Scorchsoft red = "unsaved")
```

## Conventions

- The toolchain mirrors `scorchsoft/measur3d`'s `apps/android`. There is
  no Gradle wrapper. Run `gradle -p apps/android <task>` from the repo
  root.
- The app is offline. `INTERNET` is used only to download a chosen
  Google Font. Never add analytics, ads or accounts.
- Screens are pure `(state, callbacks)` composables, so every screen
  renders under Robolectric.
- Build artefacts never go in git; hand-over APKs go to Drive.
