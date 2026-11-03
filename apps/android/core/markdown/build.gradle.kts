// Pure Kotlin: the tokenizer, block index and anchor maths. No Android
// import may ever appear here; it is tested on the host JVM
// (docs/engineering.md).
plugins {
    id("ssmarkdown.jvm.library")
}

dependencies {
    // `api`: LineSource adapters for PieceTree and Snapshot are public API.
    api(project(":core:text"))
    testImplementation(libs.junit)
}

tasks.test {
    // `-Pssmarkdown.heavyTests=true` runs more property-test seeds and the
    // tokenizer benchmark (a 20 MB corpus), which `check` skips.
    systemProperty("ssmarkdown.heavyTests", providers.gradleProperty("ssmarkdown.heavyTests").getOrElse("false"))
    // `-Pssmarkdown.updateVectors=true` rewrites Preview's copy of the block-editing matrix.
    systemProperty("ssmarkdown.updateVectors", providers.gradleProperty("ssmarkdown.updateVectors").getOrElse("false"))
    maxHeapSize = "2g"
}
