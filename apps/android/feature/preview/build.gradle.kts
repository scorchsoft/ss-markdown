// The preview: WebView host, JS bridge, viewer, print and export. Empty
// until its iteration; the dependencies below are the edges the module
// graph allows it.
plugins {
    id("ssmarkdown.android.library")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.preview"
}

dependencies {
    implementation(project(":core:markdown"))
    implementation(project(":core:theme"))
    implementation(project(":core:ui"))
}
