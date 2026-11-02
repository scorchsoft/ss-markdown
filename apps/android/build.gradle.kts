// Root build. Every plugin is declared here with `apply false` so the
// version catalogue is the single place a version lives.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Gives the root its own `check`, which runs the module-graph rule.
    id("ssmarkdown.module-graph")
    // Merged Kover coverage of every core module (koverHtmlReport, koverXmlReport).
    id("ssmarkdown.coverage-report")
}

// Unit tests run on DEBUG only. The release unit-test component of every
// Android module is removed: it compiles the same sources a second time
// against the same JVM and proves nothing a debug run does not, and R8
// (the thing that actually differs in release) never runs under a unit
// test anyway. Release is proven by building the minified artefact.
subprojects {
    plugins.withId("com.android.base") {
        extensions.configure<com.android.build.api.variant.AndroidComponentsExtension<*, *, *>>(
            "androidComponents",
        ) {
            beforeVariants(selector().withBuildType("release")) { variant ->
                variant.enableUnitTest = false
            }
        }
    }
}
