import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

/**
 * Line coverage with Kover for one core module, gated at the bar in
 * docs/engineering.md (code-craft review CC-4): `koverVerify` runs in
 * `check` and fails below [MIN_LINE_COVERAGE].
 *
 * Applied by `ssmarkdown.jvm.library`, so every pure core module is
 * measured, and directly by `core:ui`. Each module is gated on its own,
 * so a well-covered module cannot carry a poorly covered one. When the
 * gate was switched on every module cleared it (text 90.7%, 97.0% once
 * its benchmark harness stopped counting; ui 91.6%,
 * history 95.6%, markdown 98.0%, theme 98.1%).
 *
 * Kover instruments the host-JVM unit tests; on an Android module that is
 * the debug variant, the only one with unit tests here.
 *
 * A module outside core may apply it too, with its own floor
 * (`ssmarkdownCoverage { minLineCoverage = 80 }`): `feature:files`, the
 * never-lose-work pipeline, is gated that way (code-craft review CC-B).
 *
 * Benchmark harnesses (`*.benchmark.*`, such as core:text's own source
 * set) are not library code and are excluded: they used to count as 69
 * uncovered lines in core:text (CC-A).
 */
class CoverageConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlinx.kover")
        val settings = target.extensions.create<CoverageSettings>("ssmarkdownCoverage")
        settings.minLineCoverage.convention(MIN_LINE_COVERAGE)
        target.extensions.configure<KoverProjectExtension> {
            reports {
                total {
                    filters { excludes { classes(COMPOSE_SINGLETONS, BENCHMARKS) } }
                    verify { rule("line coverage") { minBound(settings.minLineCoverage) } }
                }
            }
        }
    }

    internal companion object {
        /** docs/engineering.md, Testing: the core modules at >= 90% line coverage. */
        const val MIN_LINE_COVERAGE = 90

        /** Compose's generated lambda holders: compiler output, not our code. */
        const val COMPOSE_SINGLETONS = "*ComposableSingletons*"

        /** Benchmark harnesses: measured by hand, never shipped (CC-A). */
        const val BENCHMARKS = "*.benchmark.*"
    }
}

/** A module's coverage floor; [CoverageConventionPlugin.MIN_LINE_COVERAGE] unless it says otherwise. */
abstract class CoverageSettings {
    /** Minimum line coverage, in percent, that `koverVerify` enforces. */
    abstract val minLineCoverage: Property<Int>
}
