// Pure Kotlin: the journal and keyframe-plus-diff version history. No
// Android import may ever appear here; it is tested on the host JVM
// (docs/engineering.md).
plugins {
    id("ssmarkdown.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
}
