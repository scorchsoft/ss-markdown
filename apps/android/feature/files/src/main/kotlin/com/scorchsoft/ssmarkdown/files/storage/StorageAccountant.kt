package com.scorchsoft.ssmarkdown.files.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/** The rows of the Storage screen's breakdown (ux-spec "Storage"). */
enum class StorageCategory {
    /** Version history (n files). */
    VersionHistory,

    /** Downloaded fonts. */
    Fonts,

    /** Diagram and preview cache. */
    PreviewCache,

    /** Drafts. */
    Drafts,

    /** Recovery copies of unsaved work: journals and base copies. */
    RecoveryCopies,

    /** The app itself (its APK and native libraries). */
    App,
}

/**
 * One row's numbers.
 *
 * @property bytes space used.
 * @property items how many top-level entries: files with history, drafts, fonts.
 */
data class CategoryUsage(
    val bytes: Long,
    val items: Int,
)

/**
 * The whole breakdown.
 *
 * @property usage one entry per [StorageCategory].
 * @property measuredAtMillis when it was measured, so the screen can show stale values honestly.
 */
data class StorageBreakdown(
    val usage: Map<StorageCategory, CategoryUsage>,
    val measuredAtMillis: Long,
) {
    /** The total shown above the bar. */
    val totalBytes: Long get() = usage.values.sumOf { it.bytes }
}

/**
 * Measures [StorageLayout] off the main thread and remembers the last
 * result, so the Storage screen shows known values at once and refreshes.
 *
 * @param appBytes size of the installed app; injected because it comes from `ApplicationInfo`.
 */
class StorageAccountant(
    private val layout: StorageLayout,
    private val io: CoroutineDispatcher,
    private val nowMillis: () -> Long,
    private val appBytes: () -> Long = { 0 },
) {
    /** The last measurement, or null before the first. */
    @Volatile
    var lastKnown: StorageBreakdown? = null
        private set

    /** Walks every directory on [io] and returns the fresh breakdown. */
    suspend fun measure(): StorageBreakdown =
        withContext(io) {
            val usage =
                mapOf(
                    StorageCategory.VersionHistory to usageOf(layout.history),
                    StorageCategory.Fonts to usageOf(layout.fonts),
                    StorageCategory.PreviewCache to usageOf(layout.previewCache),
                    StorageCategory.Drafts to usageOf(layout.drafts),
                    StorageCategory.RecoveryCopies to combine(usageOf(layout.journals), usageOf(layout.baseCopies)),
                    StorageCategory.App to CategoryUsage(appBytes(), 1),
                )
            StorageBreakdown(usage, nowMillis()).also { lastKnown = it }
        }

    private fun usageOf(dir: File): CategoryUsage {
        val children = dir.listFiles().orEmpty()
        return CategoryUsage(children.sumOf(::sizeOf), children.size)
    }

    private fun combine(
        a: CategoryUsage,
        b: CategoryUsage,
    ) = CategoryUsage(a.bytes + b.bytes, a.items + b.items)

    /** Bytes under [file], not following symbolic links out of the tree. */
    private fun sizeOf(file: File): Long =
        file
            .walkTopDown()
            .onEnter {
                !java.nio.file.Files
                    .isSymbolicLink(it.toPath())
            }.filter { it.isFile }
            .sumOf { it.length() }
}
