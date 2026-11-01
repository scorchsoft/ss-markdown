// Fonts: the bundled-font registry, the Google Fonts catalogue and
// downloader, and .ttf/.otf import (docs/ux-spec.md, Themes; mockup
// FontPicker). Android types stay at the edges (AndroidFonts, SafFontSource);
// everything else runs on the host JVM with an injected HTTP transport and
// clock, so no test touches the network.
plugins {
    id("ssmarkdown.android.library")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.fonts"
}

// core:theme's FontFaceSource and coroutines types are in this module's
// public API, so both are `api`.
dependencies {
    api(project(":core:theme"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
