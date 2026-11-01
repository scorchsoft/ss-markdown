# SS Markdown Android

Kotlin + Compose. Pure-JVM core, thin app, no DI framework. Iteration 1
is the scaffold: every module exists and builds, but only `core:ui` and
the app shell hold real code yet.

## Layout

```
app/              MainActivity (edge to edge, handles its own config changes) and shell/
core/text         PURE JVM. Piece table, line index, graphemes, undo.        (piece tree, done: see core/text/NOTICE)
core/markdown     PURE JVM. Tokenizer, block index, anchor maths.             (tokenizer + outline done; see known gaps in commit)
core/history      PURE JVM. Journal, keyframe+diff history, retention.        (recovery journal, done: see core/history/FORMAT.md)
core/theme        PURE JVM. Theme schema, CSS generation, contrast checks.    (placeholder)
core/ui           Design system: SS.colors, SSType, SSDimens, SS* components
feature/editor    empty: MarkdownCodeView, InputConnection, gestures
feature/preview   empty: WebView host, bridge, print/export
feature/files     empty: SAF, DocumentStore, save pipeline
build-logic/      ssmarkdown.* convention plugins (below)
config/detekt/    detekt overrides; ktlint reads ../.editorconfig
tools/            bootstrap-android-sdk.sh
```

Convention plugins (`build-logic/convention`):

- `ssmarkdown.jvm.library` — Kotlin JVM, Java 17, **explicit API mode**.
  Every `core:*` module except `core:ui`.
- `ssmarkdown.android.library` — compileSdk 36, minSdk 29, Java 17.
  `core:ui` and `feature:*`.
- `ssmarkdown.static-analysis` — ktlint, detekt and Android lint
  (`warningsAsErrors`), all in `check`. Applied by both of the above and
  by `:app` directly.
- `ssmarkdown.module-graph` — root only. `checkModuleGraph` (in `check`)
  fails if `core:*` depends on `feature:*`/`:app`, or a feature on
  another feature or `:app`.

## Commands

No wrapper. Run from the REPO ROOT.

```sh
export ANDROID_HOME=/root/android-sdk          # bootstrap: apps/android/tools/bootstrap-android-sdk.sh
gradle -p apps/android check                   # graph, ktlint, detekt, lint, all unit tests
gradle -p apps/android :core:text:test         # one pure module, host JVM, seconds
gradle -p apps/android ktlintFormat            # fix formatting before committing
gradle -p apps/android :app:assembleDebug
gradle -p apps/android -Pssmarkdown.minifyDebug=true :app:assembleDebug   # hand-over build, through R8
gradle -p apps/android -Pssmarkdown.screenshots=true :app:testDebugUnitTest --tests '*ScreenshotTest*'
gradle -p apps/android :app:connectedDebugAndroidTest   # needs a device or emulator
```

Screenshots land in `app/build/screenshots/{light,dark}/` (never
committed). CI uploads them as the `screenshots` artifact and runs the
instrumented tests on an API 34 emulator in a separate job.

Tests run on debug only (root `build.gradle.kts` removes the release
unit-test component). Robolectric is pinned to `sdk=34` in
`app/src/test/resources/robolectric.properties`; a different `@Config`
sdk buys a second sandbox and doubles the run time.

## Traps

- **Gradle from the wrong directory** fails in one second with a path
  like `apps/android/apps/android`. Run from the repo root with `-p`.
- **Formatting is ktlint_official**, not IntelliJ's default. Run
  `ktlintFormat` rather than reformatting in the IDE, or `check` fails.
- **detekt runs on Kotlin 2.0.21**, not the project's Kotlin. The
  static-analysis plugin pins detekt's classpath; do not remove that
  block when bumping Kotlin or detekt refuses to start.
- **A pure-JVM core module cannot even resolve an Android feature**, so
  a `core:text -> feature:*` edge fails at dependency resolution before
  `checkModuleGraph` runs. Android-to-Android violations
  (`feature:editor -> feature:preview`) reach the graph check and get its
  message. `gradle -p apps/android checkModuleGraph` alone always gives
  the graph message.
- **Screenshot capture** draws the decor view into a software bitmap.
  compose-test's `captureToImage()` deadlocks under Robolectric's paused
  looper. `ComponentActivity` is registered in `app/src/debug/AndroidManifest.xml`
  because the ui-test-manifest AAR is not merged into unit-test manifests.
- **configChanges** in the manifest is the robustness.md §5a list. Do not
  trim it: losing `uiMode` or `fontScale` recreates the Activity on a
  theme or text-size change.
- **Maven Central rate-limits** cloud containers (HTTP 429). If a build
  fails that way, retry, or point a local init script at Google's Central
  mirror (`https://maven-central.storage-download.googleapis.com/maven2/`)
  and set `robolectric.dependency.repo.url` to the same for Robolectric's
  own downloads. Never commit that workaround.
- **Fonts.** `SSType` uses the system sans and monospace until Inter and
  JetBrains Mono are bundled in iteration 2.
