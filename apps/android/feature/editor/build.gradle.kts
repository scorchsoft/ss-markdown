// The editor surface: MarkdownCodeView, InputConnection, gestures,
// selection, accessibility and its Compose host. Grew from the editor-engine
// spike (docs/adr/0001-editor-engine.md); the dependencies below are the
// edges the module graph allows it.
plugins {
    id("ssmarkdown.android.library")
    // For the MarkdownEditor composable that hosts the view.
    alias(libs.plugins.kotlin.compose)
    // Line coverage gated at what it passes, rounded down to 5%
    // (85.8% measured 2026-11-01): a floor that only moves up.
    id("ssmarkdown.coverage")
}

ssmarkdownCoverage {
    minLineCoverage = 85
}

// `-Pssmarkdown.screenshots=true` runs EditorScreenshotTest, which renders the
// Code pane to PNGs under feature/editor/build/screenshots/ for review (the
// same opt-in harness as :app). Off by default so the unit suite stays fast.
val captureScreenshots = providers.gradleProperty("ssmarkdown.screenshots").orNull?.toBoolean() == true

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.editor"
    buildFeatures { compose = true }
    testOptions {
        // The view's accessibility strings (hint, heading actions) must resolve under Robolectric.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Same cap as :app: each Robolectric fork carries a sandbox.
            test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            if (captureScreenshots) {
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            } else {
                test.exclude("**/*ScreenshotTest*")
            }
        }
    }
}

dependencies {
    // `api`: TextDocument and SSColors appear in this module's public API.
    api(project(":core:text"))
    api(project(":core:markdown")) // FenceStates is in the view's API (fenceStates()).

    api(project(":core:ui"))
    // `api`: EditorTheme.withSyntax takes the theme's SyntaxColors, so the Code view and Preview share one palette.
    api(project(":core:theme"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
