package com.scorchsoft.ssmarkdown.platform

import android.content.Context
import java.io.File

/**
 * The app's private directories, worked out from `ApplicationInfo.dataDir`
 * without touching the disk. `Context.getFilesDir()` and its siblings check
 * the directory exists (and create it) on EVERY call, which is a main-thread
 * disk read; CI's StrictMode monkey run caught the graph doing exactly that
 * at start-up. The paths are the same ones the platform returns.
 *
 * [ensure] creates them the way the platform would, and is called off the
 * main thread as the process starts; every store also creates its own
 * subdirectory before it writes.
 */
class AppDirs(
    dataDir: File,
) {
    /** `Context.getFilesDir()`. */
    val files: File = File(dataDir, "files")

    /** `Context.getNoBackupFilesDir()`. */
    val noBackup: File = File(dataDir, "no_backup")

    /** `Context.getCacheDir()`. */
    val cache: File = File(dataDir, "cache")

    companion object {
        /** [context]'s directories; no disk access. */
        fun of(context: Context): AppDirs = AppDirs(File(context.applicationInfo.dataDir))

        /** Creates the directories through the platform. Disk work: call it off the main thread. */
        fun ensure(context: Context) {
            context.filesDir
            context.noBackupFilesDir
            context.cacheDir
        }
    }
}
