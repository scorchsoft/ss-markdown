# ADR 0003: Constructor injection and one AppGraph, no DI framework

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made at the scaffold)
- **Deciders:** Andrew (Scorchsoft)

## Context

The app is an Apache-2.0 showcase (`engineering.md`): a newcomer should be
able to follow how anything is built. It has one Activity, one process-wide
editor, a handful of stores and no server. Everything under test must be
swappable: clock, file system, provider, dispatchers, memory info.

## Options

1. **Hilt or Dagger:** compile-time graph, scoping annotations, the Android
   default. Adds kapt/KSP, generated code and a framework to learn.
2. **Koin:** runtime service locator; errors surface at run time.
3. **Constructor injection by hand:** one `AppGraph` object builds
   everything, lazily, and passes dependencies down through constructors.

## Decision

Option 3. `app/src/main/kotlin/.../AppGraph.kt` owns the clock, the I/O
dispatcher, the process-wide scopes, the stores and the view models, and is the
only singleton (`engineering.md`, "No hidden global state"). Every class takes
what it needs as constructor parameters; tests build the same classes with
fakes.

## Consequences

- No annotation processing, no generated code, no reflection: faster builds,
  and nothing for R8 to keep (`app/proguard-rules.pro`).
- The graph is plain Kotlin you can read top to bottom; "go to definition"
  always works.
- Wiring is manual, so cycles are possible and must be broken by hand (for
  example a `lazy` between the editor and the picture exporter; code-craft
  review CC-18). Scoping (per-Activity vs process) is a code decision, not an
  annotation.
- If the graph outgrows one file, split it into feature-sized graphs before
  reaching for a framework.
