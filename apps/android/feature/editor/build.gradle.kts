// The editor surface: MarkdownCodeView, InputConnection, gestures and
// accessibility. Holds the editor-engine spike (docs/adr/0001-editor-engine.md);
// the dependencies below are the edges the module graph allows it.
plugins {
    id("ssmarkdown.android.library")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.editor"
    testOptions {
        unitTests.all { test ->
            // Same cap as :app: each Robolectric fork carries a sandbox.
            test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        }
    }
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":core:markdown"))
    implementation(project(":core:ui"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
