// Emulator evidence for rubric #4 (docs/performance-techniques.md,
// "Emulator measurements"): Macrobenchmarks of cold start, opening 1 MB and
// 20 MB files, keystroke-to-commit, fling jank and Preview refresh, plus the
// generator of the app's Baseline Profile. Runs against :app's benchmark
// build types (non-debuggable, profileable), never against debug.
//
//   gradle -p apps/android :benchmark:connectedBenchmarkReleaseAndroidTest   # the measurements
//   gradle -p apps/android :app:generateBaselineProfile                      # the profile
//
// Both need a device or emulator; CI's `perf` job runs them on API 34.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
    id("ssmarkdown.static-analysis")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.benchmark"
    compileSdk = 36

    defaultConfig {
        // Macrobenchmark needs API 29 for its tracing; the app's floor too.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The CI device is an emulator. Its absolute numbers are noisy and
        // optimistic about I/O, so they are a trend line, not the budget; the
        // budget is proved on a phone (docs/quality-bar.md §4).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
    // The test APK instruments itself; it drives :app through UiAutomator.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

baselineProfile {
    // The connected CI emulator, not a Gradle-managed device: the runner
    // already boots one, and a second would not fit the job's time.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
