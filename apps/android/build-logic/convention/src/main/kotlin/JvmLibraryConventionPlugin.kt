import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure-JVM twin of [AndroidLibraryConventionPlugin], for the `core:*`
 * modules with no Android dependency at all (text, markdown, history,
 * theme). Their tests run as plain JUnit on the host in seconds, which is
 * what makes the text engine fast to iterate on.
 *
 * Explicit API mode is on: these modules are the engine other code is
 * built on, so every public declaration has to be a deliberate choice
 * with a visibility and a type written down (docs/engineering.md).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.jvm")
            apply("ssmarkdown.static-analysis")
            apply("ssmarkdown.coverage")
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        tasks.withType<Test>().configureEach { useJUnit() }
    }
}
