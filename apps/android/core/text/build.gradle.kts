// Pure Kotlin: the piece table, line index, graphemes and undo. No
// Android import may ever appear here; it is tested on the host JVM
// (docs/engineering.md).
plugins {
    id("ssmarkdown.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
}
