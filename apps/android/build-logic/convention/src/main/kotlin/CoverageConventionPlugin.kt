import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Line coverage with Kover for one core module, gated at the bar in
 * docs/engineering.md (code-craft review CC-4): `koverVerify` runs in
 * `check` and fails below [MIN_LINE_COVERAGE].
 *
 * Applied by `ssmarkdown.jvm.library`, so every pure core module is
 * measured, and directly by `core:ui`. Each module is gated on its own,
 * so a well-covered module cannot carry a poorly covered one. When the
 * gate was switched on every module cleared it (text 90.7%, ui 91.6%,
 * history 95.6%, markdown 98.0%, theme 98.1%).
 *
 * Kover instruments the host-JVM unit tests; on an Android module that is
 * the debug variant, the only one with unit tests here.
 */
class CoverageConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlinx.kover")
        target.extensions.configure<KoverProjectExtension> {
            reports {
                total {
                    filters { excludes { classes(COMPOSE_SINGLETONS) } }
                    verify { rule("line coverage") { minBound(MIN_LINE_COVERAGE) } }
                }
            }
        }
    }

    internal companion object {
        /** docs/engineering.md, Testing: the core modules at >= 90% line coverage. */
        const val MIN_LINE_COVERAGE = 90

        /** Compose's generated lambda holders: compiler output, not our code. */
        const val COMPOSE_SINGLETONS = "*ComposableSingletons*"
    }
}
