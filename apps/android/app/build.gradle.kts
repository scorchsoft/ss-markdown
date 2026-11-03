plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Consumes the profile :benchmark generates, and adds the benchmark
    // build types (benchmarkRelease, nonMinifiedRelease) it measures.
    alias(libs.plugins.baselineprofile)
    id("ssmarkdown.static-analysis")
    // Line coverage gated at what it passes, rounded down to 5% (63.1%
    // measured 2026-11-01; Robolectric screens, not the device-only glue).
    id("ssmarkdown.coverage")
}

ssmarkdownCoverage {
    minLineCoverage = 60
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
        versionCode = 4
        versionName = "0.2.2"
        // AndroidJUnitRunner that also installs LeakCanary's watcher, so the
        // instrumented tests fail on a leaked Activity, View or ViewModel.
        testInstrumentationRunner = "com.scorchsoft.ssmarkdown.LeakCheckRunner"
    }

    androidResources {
        // The app ships English only, so drop the ~85 locales AndroidX
        // libraries bring with them: resources.arsc 380 KB -> 60 KB. Remove
        // this when the app is translated.
        localeFilters += "en"
    }

    signingConfigs {
        // Which debug key signs the hand-over APK decides whether a newer
        // build installs over an older one. Left to AGP, the key is generated
        // into ~/.android/debug.keystore on whatever machine or CI container
        // ran the build: a new random key per container, so Android refuses
        // the update (INSTALL_FAILED_UPDATE_INCOMPATIBLE, "App not
        // installed") and the user must uninstall, losing drafts and the
        // recovery journal. `sideload-debug.keystore` is checked in so every
        // build signs the same. It is not a secret: the passwords are the
        // literal "android", as AGP's own debug key, and Play rejects any
        // debug-signed upload. CI checks the signer (expected SHA-256 in
        // apps/android/CLAUDE.md, "The hand-over build").
        getByName("debug") {
            storeFile = rootProject.file("sideload-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
                    // Start from an empty folder, so a shot whose test was
                    // renamed or deleted (a stale shell.png, UX-24) cannot
                    // pass for current. Here, not in the test: the
                    // parameterised runner would run a @BeforeClass per theme.
                    val buildDir = layout.buildDirectory.asFile.get()
                    val shots = buildDir.resolve("screenshots")
                    test.doFirst { shots.deleteRecursively() }
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

// Licence texts that live outside any module's assets but must ship in the
// APK (code-craft review CC-2): our own Apache-2.0 text, which the AndroidX,
// Compose and Kotlin rows share, and the MIT notice for the VS Code piece
// tree ported in core:text. Copied at build time so the repository keeps one
// copy of each; the Open-source licences screen shows them.
abstract class LicenceTextsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apacheLicence: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pieceTreeNotice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licences")
        dir.deleteRecursively()
        dir.mkdirs()
        apacheLicence.get().asFile.copyTo(dir.resolve("Apache-2.0.txt"))
        pieceTreeNotice.get().asFile.copyTo(dir.resolve("vscode-piece-tree-MIT.txt"))
    }
}

val licenceTexts =
    tasks.register<LicenceTextsTask>("licenceTexts") {
        apacheLicence.set(rootProject.layout.projectDirectory.file("../../LICENSE"))
        pieceTreeNotice.set(rootProject.layout.projectDirectory.file("core/text/NOTICE"))
        outputDir.set(layout.buildDirectory.dir("generated/licenceTexts"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(licenceTexts, LicenceTextsTask::outputDir)
    }
}

// The app's own Baseline Profile (rubric #4): generated on the CI
// emulator by :benchmark's BaselineProfileGenerator into
// src/main/generated/baselineProfiles/, where EVERY non-debuggable variant
// compiles it in, the hand-over (minified debug) build included, not only
// release. It is not committed (.gitignore: ~6 MB that changes every run);
// CI's perf job builds the APK with it and uploads the profile in its
// emulator-evidence artifact. Generation needs a device, so an ordinary
// build never runs it:
//   gradle -p apps/android :app:generateBaselineProfile
baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

// Benchmark-only sources (src/perf): a provider that serves generated
// 50 KB, 1 MB and 20 MB Markdown files, so the benchmarks can open them
// through the same content:// path as Files or Drive. Added to the two
// build types the baselineprofile plugin creates, through the variant API,
// because the plugin copies release's manifest over theirs. Never in
// debug, release or the hand-over.
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("benchmarkRelease", "nonMinifiedRelease")) {
            variant.sources.java?.addStaticSourceDirectory("src/perf/kotlin")
            variant.sources.manifests.addStaticManifestFile("src/perf/AndroidManifest.xml")
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
    // Installs the Baseline Profile on sideloaded builds (the hand-over is sideloaded).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":benchmark"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.accessibility.test.framework)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Test APK only: never merged into any app APK, so never handed over.
    androidTestImplementation(libs.leakcanary.android.instrumentation)
}
