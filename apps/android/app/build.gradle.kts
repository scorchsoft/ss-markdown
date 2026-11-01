plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("ssmarkdown.static-analysis")
}

// `-Pssmarkdown.minifyDebug=true` turns `assembleDebug` into the hand-over
// artefact: signed with the debug key (there is no release key yet), but
// otherwise built as a release would be. It is NOT debuggable, because AGP
// runs R8 in debug mode for a debuggable variant: no optimisation, no
// renaming, full debug info, and no baseline profile in the APK. That
// alone made the hand-over 15.1 MB instead of ~6 MB (performance review
// PERF-1). Stack traces from it are obfuscated, so mapping.txt is archived
// next to every APK handed over (app/build/outputs/mapping/debug/).
val minifyDebug = providers.gradleProperty("ssmarkdown.minifyDebug").orNull?.toBoolean() == true

// `-Pssmarkdown.screenshots=true` runs ScreenshotTest, which renders the
// screens to PNGs under app/build/screenshots/ for review. It is a review
// artefact, not a regression test (it asserts nothing visual), so it is
// off by default and the unit suite stays fast.
val captureScreenshots = providers.gradleProperty("ssmarkdown.screenshots").orNull?.toBoolean() == true

android {
    namespace = "com.scorchsoft.ssmarkdown"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scorchsoft.ssmarkdown"
        // Android 10: scoped storage and the Storage Access Framework as we
        // need them, with no legacy storage permissions.
        minSdk = 29
        targetSdk = 36
        // versionCode bumps by exactly one per artefact handed to anyone.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // The app ships English only, so drop the ~85 locales AndroidX
        // libraries bring with them: resources.arsc 380 KB -> 60 KB. Remove
        // this when the app is translated.
        localeFilters += "en"
    }

    buildTypes {
        debug {
            isDebuggable = !minifyDebug
            isMinifyEnabled = minifyDebug
            isShrinkResources = minifyDebug
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Half the cores, capped at 4: each Robolectric fork carries a
                // sandbox, and more forks than that were measured slower.
                test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                if (captureScreenshots) {
                    // Native graphics draws through the real rasteriser; the
                    // hardware PixelCopy mode makes it render what a device would.
                    test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                } else {
                    test.exclude("**/ScreenshotTest*")
                }
            }
        }
    }
}

// core:ui puts Compose's @Preview renderer (ui-tooling) on every debug
// classpath for the IDE. Its AAR also declares an EXPORTED PreviewActivity
// that renders any composable on request, which must never reach a build
// handed to anyone, so the hand-over leaves the library out entirely.
if (minifyDebug) {
    configurations.matching { it.name == "debugRuntimeClasspath" }.configureEach {
        exclude(group = "androidx.compose.ui", module = "ui-tooling")
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":core:markdown"))
    implementation(project(":core:history"))
    implementation(project(":core:theme"))
    implementation(project(":core:ui"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:preview"))
    implementation(project(":feature:files"))
    implementation(project(":feature:fonts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Dispatchers.Main for the view models; the same pin as feature:files' core.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
