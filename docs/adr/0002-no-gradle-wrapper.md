# ADR 0002: No Gradle wrapper

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made at the scaffold)
- **Deciders:** Andrew (Scorchsoft)

## Context

The toolchain mirrors Scorchsoft's Measur3D Android app (`product-journey.md`,
"Toolchain"): AGP 8.13, Kotlin 2.3, Gradle 8.14. The cloud containers the app
is built in, and GitHub Actions (`gradle/actions/setup-gradle` with
`gradle-version: "8.14.3"`), both supply Gradle themselves. AGP 8.13 is the last
line that runs on Gradle 8.14; AGP 9 needs Gradle 9 (`libs.versions.toml`).

## Options

1. **Commit the wrapper** (`gradlew`, `gradle-wrapper.jar`,
   `gradle-wrapper.properties`): the Android norm, one command for strangers,
   and a checksum-pinned distribution.
2. **No wrapper:** run the installed Gradle as `gradle -p apps/android <task>`
   from the repository root.

## Decision

No wrapper. Every command in the docs is `gradle -p apps/android …` from the
repo root, and the required version is stated in README, CONTRIBUTING and the
CI workflow.

## Consequences

- The build is identical across Scorchsoft's Android projects and the
  containers they are built in, with no binary jar in the repository.
- A contributor must install Gradle 8.14.x themselves; Gradle 9 fails because
  of AGP 8.13. This works against "clone, build, test in 30 minutes" (rubric),
  and CI's pin is a second source of truth for the version (code-craft review
  CC-21).
- Running Gradle from inside `apps/android` with `-p apps/android` fails in a
  second (`apps/android/apps/android`); this is the top trap in
  `apps/android/CLAUDE.md`.
- Revisit when moving to AGP 9: that move changes the Gradle major anyway, and
  committing the wrapper then costs nothing extra.
