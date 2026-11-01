plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("ssmarkdown.static-analysis")
}

// `-Pssmarkdown.minifyDebug=true` minifies a debug build. This is the
// hand-over artefact: the only debug build that exercises R8, where
// release-only crashes live.
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

    buildTypes {
        debug {
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
