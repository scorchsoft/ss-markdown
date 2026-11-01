// The editor surface: MarkdownCodeView, InputConnection, gestures and
// accessibility. Empty until iteration 2; the dependencies below are the
// edges the module graph allows it.
plugins {
    id("ssmarkdown.android.library")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.editor"
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":core:markdown"))
    implementation(project(":core:ui"))
}
