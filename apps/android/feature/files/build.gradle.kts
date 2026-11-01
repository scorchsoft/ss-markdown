// Files: Storage Access Framework, DocumentStore, the save pipeline, the
// three-way merge and the MemoryGovernor (docs/robustness.md). Android
// types stay at the edges (provider/, saf/, memory/AndroidMemorySource);
// everything else runs on the host JVM against injected fakes.
plugins {
    id("ssmarkdown.android.library")
    id("ssmarkdown.coverage")
}

// The never-lose-work pipeline is gated too (code-craft review CC-B), at what it passes:
// 88.5% when the gate went in (2026-11-01). Raise it as tests land; never lower it.
ssmarkdownCoverage {
    minLineCoverage = 88
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.files"
    testOptions {
        unitTests.all { test ->
            // Same cap as :app: each Robolectric fork carries a sandbox.
            test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            // RandomKillTest: `check` runs 100 seeds; -Pssmarkdown.killRuns=1000 is the rubric's thousand.
            providers.gradleProperty("ssmarkdown.killRuns").orNull?.let { test.systemProperty("ssm.kill.runs", it) }
            providers.gradleProperty("ssmarkdown.killSeed").orNull?.let { test.systemProperty("ssm.kill.seed", it) }
        }
    }
}

// core:text, core:history and coroutines types appear in this module's public API
// (TextDocument, BaseFingerprint, suspend functions), so they are `api`.
// No core:ui yet: this module has no screens; the app draws its states.
dependencies {
    api(project(":core:text"))
    api(project(":core:history"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
