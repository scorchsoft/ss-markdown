// Pure Kotlin: the theme JSON schema, CSS generation and contrast
// checks. No Android import may ever appear here; it is tested on the
// host JVM (docs/engineering.md).
plugins {
    id("ssmarkdown.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
}
