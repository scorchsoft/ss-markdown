// The preview: WebView host, message-port bridge, renderer recovery, print and
// export. The page itself is the TypeScript bundle in web/, built into
// src/main/assets/preview and committed, so this module needs no Node.
plugins {
    id("ssmarkdown.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.preview"
    buildFeatures { compose = true }
    testOptions {
        // The "Paste as plain text" item's string must resolve under Robolectric.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Same cap as :app: each Robolectric fork carries a sandbox.
            test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        }
    }
}

dependencies {
    implementation(project(":core:markdown"))
    implementation(project(":core:theme"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.webkit)
    // PreviewView.exportHtml suspends until the page answers.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
