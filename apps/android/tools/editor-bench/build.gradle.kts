// The editor-engine benchmark (docs/adr/0001-editor-engine.md): one APK
// that opens the same synthetic Markdown in the platform EditText, the
// MarkdownCodeView spike and Sora Editor, and measures open time, scroll
// jank, keystroke latency and heap.
//
// A tool, not a product. It is never shipped and nothing may depend on it
// (checkModuleGraph enforces that), because it carries Sora Editor, which
// is LGPL-2.1 and must never reach :app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("ssmarkdown.static-analysis")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.bench"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scorchsoft.ssmarkdown.bench"
        // Same floor as the app, so the numbers are for the phones we support.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-bench"
    }

    buildTypes {
        release {
            // Measured as a release build (no debuggable ART overhead), but not
            // minified: R8 would only make stack traces from a failed run harder
            // to read, and none of the three engines is what R8 is measuring.
            isMinifyEnabled = false
            // Signed with AGP's debug key so it can be sideloaded. The repo
            // commits no keystore (docs/engineering.md), so each machine has its
            // own: installing a build from another machine needs an uninstall.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // A developer tool with English-only labels built in code; there is no
        // translation to protect.
        disable += setOf("SetTextI18n")
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":feature:editor"))

    implementation(libs.kotlinx.coroutines.android)
    // LGPL-2.1, benchmark baseline only. See the catalogue comment.
    implementation(libs.sora.editor)
}
