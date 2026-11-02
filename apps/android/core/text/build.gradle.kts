// Pure Kotlin: the piece table, line index, graphemes and undo. No
// Android import may ever appear here; it is tested on the host JVM
// (docs/engineering.md).
plugins {
    id("ssmarkdown.jvm.library")
}

// A plain `main` harness rather than JMH: the numbers it reports (load,
// edit and lookup costs at 1/20/100 MB) are coarse budgets, and a source
// set keeps the harness out of both the library and `check`.
val benchmark: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // The property tests run a few hundred thousand ops by default.
    // `-Pssmarkdown.heavyTests=true` turns that into millions (see
    // PieceTreePropertyTest, ~15 s): a deeper soak for before a release.
    systemProperty("ssmarkdown.heavyTests", providers.gradleProperty("ssmarkdown.heavyTests").getOrElse("false"))
    maxHeapSize = "1g"
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Times load, edits and line lookups at 1, 20 and 100 MB. Not part of check."
    classpath = benchmark.runtimeClasspath
    mainClass.set("com.scorchsoft.ssmarkdown.text.benchmark.TextBenchmarkKt")
    maxHeapSize = "2g"
}
