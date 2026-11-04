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
    // `-Pssmarkdown.tidyFuzz=<path>` writes a large Tidy fuzz run for Preview's tidyRender test (TIDY_VECTORS=<path>).
    systemProperty("ssmarkdown.tidyFuzz", providers.gradleProperty("ssmarkdown.tidyFuzz").getOrElse(""))
    systemProperty("ssmarkdown.tidyFuzzSeed", providers.gradleProperty("ssmarkdown.tidyFuzzSeed").getOrElse(""))
    maxHeapSize = "2g"
}
