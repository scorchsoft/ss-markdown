// The design system: tokens, type and shared components. No app logic.
plugins {
    id("ssmarkdown.android.library")
    alias(libs.plugins.kotlin.compose)
}

// `-Pssmarkdown.screenshots=true` renders the components to PNGs under
// core/ui/build/screenshots/ for review, as :app does for screens.
val captureScreenshots = providers.gradleProperty("ssmarkdown.screenshots").orNull?.toBoolean() == true

android {
    namespace = "com.scorchsoft.ssmarkdown.core.ui"
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            // Components read their TalkBack labels from string resources.
            isIncludeAndroidResources = true
            all { test ->
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
}

dependencies {
    // `api`: every screen composes these, so the Compose BOM is exposed
    // rather than re-declared in each feature.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    debugApi(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
}
