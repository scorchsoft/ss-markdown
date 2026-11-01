// The convention plugins' own build. Included by the app's
// settings.gradle.kts, so it is compiled before any module is configured
// and shares the same version catalogue.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
