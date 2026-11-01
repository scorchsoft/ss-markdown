package com.scorchsoft.ssmarkdown

import android.app.Application

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
     * The platform asks for memory back: the governor walks its shed
     * ladder (docs/robustness.md Part 2a, "Under pressure"). A graph that
     * was never built has nothing to give, and is not built just to say so.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (lazyGraph.isInitialized()) graph.governor.onTrimMemory(level)
    }
}
