package com.scorchsoft.ssmarkdown

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

/**
 * Holds the [AppGraph] for the life of the process. The graph is built on
 * first use, not in `onCreate`, so a process started for a share or a
 * backup never pays for the editor.
 */
class SSMarkdownApplication : Application() {
    private val lazyGraph = lazy { AppGraph(this) }

    /** Everything the app is made of; see [AppGraph]. */
    val graph: AppGraph by lazyGraph

    /**
     * Starts reading the settings file now: the platform loads a
     * preferences file on its own thread from the first
     * `getSharedPreferences`, so by the time the graph's first read needs
     * it the main thread no longer waits on the disk (PERF-21).
     */
    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        getSharedPreferences(SETTINGS_PREFERENCES, MODE_PRIVATE)
    }

    /**
     * Debuggable builds log every main-thread disk or network access and
     * every leaked closeable (docs/robustness.md Part 3; performance
     * review PERF-C). Log only, never death: WebView's own start-up reads
     * disk on the main thread, which no app can avoid. The hand-over build
     * is not debuggable, so it never pays for this.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * The platform asks for memory back: the governor walks its shed
     * ladder (docs/robustness.md Part 2a, "Under pressure"). A graph that
     * was never built has nothing to give, and is not built just to say so.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (lazyGraph.isInitialized()) graph.governor.onTrimMemory(level)
    }
}
