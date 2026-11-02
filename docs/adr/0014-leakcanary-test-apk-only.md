# ADR 0014: LeakCanary runs over the instrumented tests and never ships

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** Scorchsoft

## Context

The editor holds large buffers, a WebView and long-lived listeners; a
leaked Activity after a rotation or a theme change can hold a 20 MB
document. We want leaks found automatically. LeakCanary is the standard
tool (Apache-2.0), but the usual `debugImplementation` puts it, with its
notification and launcher icon, into every debug APK, and the hand-over
build is a debug-signed build.

## Options

1. **`debugImplementation`**: leaks surface while developing, by hand.
2. **Nothing**: rely on reviews and heap dumps.
3. **`androidTestImplementation` only**: LeakCanary's instrumentation
   artifact checks the heap after every instrumented test.

## Decision

Option 3. `LeakCheckRunner` (an `AndroidJUnitRunner`) installs the
watcher in the app's process during instrumented tests only, and every
instrumented test class declares
`@get:Rule(order = 0) val leaks = DetectLeaksAfterTestSuccess()`, outermost
so it runs after the Activity closes. CI's `checks` job fails the
hand-over APK if it ever contains LeakCanary.

## Consequences

- Every instrumented test on CI's emulator (the rotation matrix, the kill
  loop, the device edge cases) is also a leak test; a leak fails it.
- No APK a person installs carries LeakCanary, its icon or its overhead.
- Leaks reached only through manual use are not caught automatically; a
  new instrumented test class must add the rule (`apps/android/CLAUDE.md`).
