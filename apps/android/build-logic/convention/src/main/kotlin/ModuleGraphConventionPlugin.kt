import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Enforces "dependencies point inwards" (docs/engineering.md) on the
 * declared module graph:
 *
 * - `:core:*` never depends on `:feature:*` or `:app`;
 * - `:feature:*` never depends on another `:feature:*` or on `:app`;
 * - nothing depends on `:tools:*`. Tools (the editor-engine benchmark)
 *   are leaves: they may depend on anything, but they carry dependencies
 *   that must never ship (Sora Editor is LGPL-2.1), so no shipping module
 *   may reach them.
 *
 * Applied to the root project only. It reads every module's declared
 * project dependencies, so a violation fails `check` before anything is
 * compiled, with a message naming the offending edge.
 */
class ModuleGraphConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("base")
        val checkGraph = target.tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if a module dependency points outwards (core -> feature, feature -> feature, * -> tools)."
            // Lazy: evaluated after every module is configured.
            edges.set(target.provider { declaredEdges(target) })
            report.set(target.layout.buildDirectory.file("reports/module-graph.txt"))
        }
        target.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checkGraph) }
    }

    private fun declaredEdges(root: Project): List<String> =
        root.subprojects
            .flatMap { module ->
                module.configurations.flatMap { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map { ModuleEdge(module.path, it.path) }
                }
            }.filter { it.from != it.to }
            .map(ModuleEdge::toString)
            .distinct()
            .sorted()
}

/** One declared `from` -> `to` project dependency. */
internal data class ModuleEdge(
    val from: String,
    val to: String,
) {
    /** Why this edge breaks the graph, or null when it is allowed. */
    fun violation(): String? =
        when {
            from.isCore() && (to.isFeature() || to == APP) -> "core must not depend on ${to.layer()}"
            from.isFeature() && to.isFeature() -> "features must not depend on each other"
            from.isFeature() && to == APP -> "features must not depend on the app"
            to.isTool() && !from.isTool() -> "nothing may depend on a tool (tools never ship)"
            else -> null
        }

    override fun toString(): String = "$from$SEPARATOR$to"

    companion object {
        private const val SEPARATOR = " -> "
        private const val APP = ":app"

        fun parse(edge: String): ModuleEdge = edge.split(SEPARATOR).let { (from, to) -> ModuleEdge(from, to) }

        private fun String.isCore() = startsWith(":core:")

        private fun String.isFeature() = startsWith(":feature:")

        private fun String.isTool() = startsWith(":tools:")

        private fun String.layer() = if (isFeature()) "a feature" else "the app"
    }
}

/** Checks the edges collected by [ModuleGraphConventionPlugin]. */
abstract class CheckModuleGraphTask : DefaultTask() {
    /** Every declared project dependency, as `":from -> :to"`. */
    @get:Input
    abstract val edges: ListProperty<String>

    /** The edges that were checked; also makes the task up-to-date aware. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val all = edges.get().map(ModuleEdge::parse)
        val problems = all.mapNotNull { edge -> edge.violation()?.let { "  $edge  ($it)" } }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Module graph violation: dependencies must point inwards (docs/engineering.md).\n" +
                    problems.joinToString("\n"),
            )
        }
        report.get().asFile.writeText(all.joinToString("\n", postfix = "\n"))
    }
}
