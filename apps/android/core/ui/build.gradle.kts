// The design system: tokens, type and shared components. No app logic.
plugins {
    id("ssmarkdown.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.scorchsoft.ssmarkdown.core.ui"
    buildFeatures { compose = true }
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
}
