// SS Markdown Android. `gradle -p apps/android <task>` from the repo root;
// there is no wrapper on purpose (the cloud container and CI both ship Gradle).
pluginManagement {
    // Compiled first; supplies the `ssmarkdown.*` convention plugins.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ss-markdown-android"

// The module map from docs/engineering.md. Dependencies point inwards:
// app -> feature/* -> core/*. `checkModuleGraph` (part of `check`) fails
// the build if a declaration ever points the other way.
include(
    ":app",
    ":core:text",
    ":core:markdown",
    ":core:history",
    ":core:theme",
    ":core:ui",
    ":feature:editor",
    ":feature:preview",
    ":feature:files",
)

// Developer tools: leaves that may depend on anything but that nothing may
// depend on (checkModuleGraph). Never shipped. The editor-engine benchmark
// carries Sora Editor (LGPL-2.1) as a baseline, which is why it lives here.
include(":tools:editor-bench")

// Fonts: bundled registry, Google Fonts catalogue and downloader, import.
include(":feature:fonts")
