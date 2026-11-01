plugins {
    `kotlin-dsl`
}

// Must match the JVM the modules compile against, or Gradle refuses to
// load the plugins with a class-file version error.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    // `implementation`, not `compileOnly`: these two are applied by id from
    // inside the convention plugins, so they must be on build-logic's own
    // runtime classpath (nothing in the root build declares them).
    implementation(libs.ktlint.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.kover.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "ssmarkdown.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "ssmarkdown.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("staticAnalysis") {
            id = "ssmarkdown.static-analysis"
            implementationClass = "StaticAnalysisConventionPlugin"
        }
        register("coverage") {
            id = "ssmarkdown.coverage"
            implementationClass = "CoverageConventionPlugin"
        }
        register("coverageReport") {
            id = "ssmarkdown.coverage-report"
            implementationClass = "CoverageReportConventionPlugin"
        }
        register("moduleGraph") {
            id = "ssmarkdown.module-graph"
            implementationClass = "ModuleGraphConventionPlugin"
        }
    }
}
