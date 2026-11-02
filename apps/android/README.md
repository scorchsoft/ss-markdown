# SS Markdown for Android

Kotlin and Jetpack Compose. No DI framework, no Play Services, no network
beyond an optional Google Font download.

```sh
# From the repository root. There is no Gradle wrapper; use Gradle 8.14.
export ANDROID_HOME=/path/to/android-sdk   # or apps/android/tools/bootstrap-android-sdk.sh
gradle -p apps/android check                # module graph, ktlint, detekt, lint, unit tests
gradle -p apps/android :app:assembleDebug
```

`CLAUDE.md` here has the module layout, every command and the known
traps. The design pack is in `../../docs/`; start with
`docs/product-journey.md` and `docs/engineering.md`.
