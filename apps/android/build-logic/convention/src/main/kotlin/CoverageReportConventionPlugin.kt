import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Root-only: merges the Kover coverage of every core module into one
 * report, for CI and for reading (`gradle -p apps/android koverHtmlReport
 * koverXmlReport`, written to `apps/android/build/reports/kover/`). The
 * per-module gate lives in [CoverageConventionPlugin]; this adds the same
 * bar on the merged total.
 */
class CoverageReportConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlinx.kover")
        val core = target.subprojects.filter { it.path.startsWith(":core:") }
        target.dependencies {
            core.forEach { add("kover", it) }
        }
        target.extensions.configure<KoverProjectExtension> {
            reports {
                total {
                    filters { excludes { classes(CoverageConventionPlugin.COMPOSE_SINGLETONS) } }
                    verify { rule("core line coverage") { minBound(CoverageConventionPlugin.MIN_LINE_COVERAGE) } }
                }
            }
        }
    }
}
