import com.android.build.api.dsl.CommonExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * ktlint, detekt and (on Android modules) Android lint, all wired into
 * `check` with zero tolerance: a finding fails the build. Applied by the
 * two library conventions and directly by `:app`, so no module can opt
 * out by forgetting a line.
 *
 * Style rules live in `apps/android/.editorconfig` (ktlint) and
 * `apps/android/config/detekt/detekt.yml` (detekt) rather than here, so
 * the IDE reads the same rules the build enforces.
 */
class StaticAnalysisConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun version(alias: String): String = libs.findVersion(alias).get().requiredVersion

        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        extensions.configure<KtlintExtension> {
            version.set(version("ktlint"))
            filter { exclude { it.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") } }
        }

        pluginManager.apply("io.gitlab.arturbosch.detekt")
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
        }
        tasks.withType<Detekt>().configureEach { jvmTarget = "17" }
        // detekt runs on the Kotlin compiler it was built with. Without this,
        // Gradle aligns its kotlin-* artifacts to the project's newer Kotlin
        // and detekt refuses to start ("compiled with Kotlin 2.0.21").
        val detektKotlin = version("detektKotlin")
        configurations.matching { it.name == "detekt" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
            }
        }

        plugins.withId("com.android.base") {
            extensions.getByType(CommonExtension::class.java).lint {
                warningsAsErrors = true
                abortOnError = true
                checkDependencies = false
                textReport = true
                // These compare against the newest published versions over the
                // network. Pins move deliberately, in the version catalogue,
                // so they are advice, not build failures.
                // OldTargetApi likewise depends on which SDK the machine has:
                // CI's newer lint flagged targetSdk 36 when local lint did not.
                // Raising targetSdk is a tested change, never a lint reflex.
                disable += setOf(
                    "GradleDependency",
                    "AndroidGradlePluginVersion",
                    "NewerVersionAvailable",
                    "OldTargetApi",
                )
            }
        }
    }
}
