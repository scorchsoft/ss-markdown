import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * The Android-library settings shared by `core:ui` and every `feature:*`
 * module: compileSdk 36, minSdk 29, Java 17 in and out, plus the static
 * analysis every module gets.
 *
 * Only what is genuinely identical everywhere lives here. A module's
 * namespace, extra plugins, dependencies and anything it has a reason to
 * do differently stay in the module, next to the reason.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
            apply("ssmarkdown.static-analysis")
        }

        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig.minSdk = 29
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        // Kotlin 2.3 removed the old `kotlinOptions` DSL; the JVM target is
        // set through the Kotlin extension's compilerOptions instead.
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
