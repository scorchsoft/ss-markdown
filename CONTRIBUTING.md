# Contributing to SS Markdown

Thanks for helping. SS Markdown is Apache-2.0 and is meant to be a showcase
of careful Android engineering, so the bar is high but the rules are short.
Please read [docs/product-journey.md](docs/product-journey.md) (what the app
is for and why) and [docs/engineering.md](docs/engineering.md) (how the code is
written) first. Everyone taking part follows the
[Code of Conduct](CODE_OF_CONDUCT.md). Security issues go through
[SECURITY.md](SECURITY.md), never a public issue.

## Toolchain

| Tool | Version |
|---|---|
| JDK | 21 (the code targets Java 17) |
| Android SDK | platform 36 (`apps/android/tools/bootstrap-android-sdk.sh` installs it) |
| Gradle | 8.14.x, installed yourself. There is no wrapper ([ADR 0002](docs/adr/0002-no-gradle-wrapper.md)); AGP 8.13 does not run on Gradle 9 |
| Node | 22 (`apps/android/feature/preview/web/.nvmrc`), only to change the Preview page |

Always run Gradle **from the repository root** with `-p apps/android`:

```sh
export ANDROID_HOME=/path/to/android-sdk
gradle -p apps/android check            # everything CI gates on
gradle -p apps/android :core:text:test  # one pure module, seconds
gradle -p apps/android ktlintFormat     # fix formatting before you commit
```

`apps/android/CLAUDE.md` is the working map: every module, every command and
the known traps.

## Making a change

- **Every bug fix starts with a failing test** that reproduces it, then the
  fix. Add or update a row in [docs/edge-cases.md](docs/edge-cases.md) for the
  bug class.
- **Touching files, saving or the editor?** Read
  [docs/robustness.md](docs/robustness.md) first. It is the never-lose-work
  contract, and a change that weakens it will not be merged.
- **Zero warnings.** ktlint (`ktlint_official`), detekt and Android lint all
  fail the build on any finding. Run `ktlintFormat` rather than your IDE's
  formatter. Suppress a detekt rule only with a comment saying why.
- **Coverage.** Each core module must stay at or above 90% line coverage
  (Kover, in `check`).
- **Dependencies point inwards** (core ← feature ← app), and features never
  depend on each other; `checkModuleGraph` enforces it
  ([ADR 0010](docs/adr/0010-module-graph.md)).
- **Screens are pure `(state, callbacks)` composables**, so they render under
  Robolectric. Render the screenshots for UI changes:
  `gradle -p apps/android -Pssmarkdown.screenshots=true :app:testDebugUnitTest --tests '*ScreenshotTest*'`.
- **Significant decisions get an ADR** in [docs/adr/](docs/adr/).
- **No analytics, ads, accounts or new permissions.** The app is offline apart
  from Google Fonts downloads the user asks for.

## The Preview page

The Preview is a TypeScript page in `apps/android/feature/preview/web`. Its
built output in `feature/preview/src/main/assets/preview/` is committed so
Gradle never needs Node ([ADR 0004](docs/adr/0004-committed-preview-bundle.md)).

```sh
cd apps/android/feature/preview/web
npm ci
npm run build    # rebuild the committed assets
npm test         # tsc, ESLint + Prettier, Vitest, Playwright
```

**Rebuild and commit the assets in the same commit as the `src/` change.** CI
rebuilds the bundle and fails if the committed assets differ, and fails if the
bundle grows past its 3.5 MiB budget. The edit protocol has a Kotlin half and
a TypeScript half that must change together
([ADR 0005](docs/adr/0005-edit-sync-protocol.md)).

## Commits and pull requests

- Small, focused commits. The subject line says what changed; the body says
  why, and cites the issue or finding it fixes.
- Fill in the pull request checklist. CI must be green: `check`, the
  screenshots, the minified build and its checks, the Preview bundle job and
  the instrumented tests.
- Add a line to [CHANGELOG.md](CHANGELOG.md) under *Unreleased* for anything a
  user would notice.
- By contributing you agree your work is licensed under the
  [Apache License 2.0](LICENSE).

## A note on internal tooling

Some of this repository is shaped by how Scorchsoft builds it: CI also runs on
`ccr-**` branches (cloud working branches), and the root `CLAUDE.md` mentions
hand-over builds archived to a shared drive. Neither affects contributors; you
can ignore both.
