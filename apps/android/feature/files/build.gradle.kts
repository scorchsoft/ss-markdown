// Files: Storage Access Framework, DocumentStore, the save pipeline and
// the MemoryGovernor (docs/robustness.md). Empty until its iteration; the
// dependencies below are the edges the module graph allows it.
plugins {
    id("ssmarkdown.android.library")
}

android {
    namespace = "com.scorchsoft.ssmarkdown.feature.files"
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":core:history"))
    implementation(project(":core:ui"))
}
