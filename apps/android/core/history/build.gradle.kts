// Pure Kotlin: the journal and keyframe-plus-diff version history. No
// Android import may ever appear here; it is tested on the host JVM
// (docs/engineering.md).
plugins {
    id("ssmarkdown.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Version-history property tests sync every record to disk, so `check`
    // runs a modest number of cases. `-Pssmarkdown.heavyTests=true` runs ten
    // times as many, plus the 1,000-version size and restore benchmark.
    systemProperty("ssmarkdown.heavyTests", providers.gradleProperty("ssmarkdown.heavyTests").getOrElse("false"))
    maxHeapSize = "1g"
}
