package com.scorchsoft.ssmarkdown

import android.app.Application

/**
 * Holds the [AppGraph] for the life of the process. The graph is built on
 * first use, not in `onCreate`, so a process started for a share or a
 * backup never pays for the editor.
 */
class SSMarkdownApplication : Application() {
    /** Everything the app is made of; see [AppGraph]. */
    val graph: AppGraph by lazy { AppGraph(this) }
}
