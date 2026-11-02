package com.scorchsoft.ssmarkdown

import android.app.Application
import android.content.res.Resources
import androidx.test.runner.AndroidJUnitRunner
import leakcanary.AppWatcher
import leakcanary.RootViewWatcher

/**
 * The instrumented tests' runner: AndroidJUnitRunner, plus LeakCanary's
 * object watcher installed in the app's process before any test runs.
 *
 * LeakCanary lives only in the test APK (androidTestImplementation), so it
 * never reaches an APK we hand over. Two consequences:
 * - its manifest providers never start in the app's process, so it cannot
 *   install itself; this runner installs it;
 * - its resources (settings booleans, strings) are in the test APK, not in
 *   the app, so LeakCanary reading them through the app's Resources crashes
 *   the app at start-up (`Resources$NotFoundException` from
 *   `InternalLeakCanary.addDynamicShortcut`). LeakCanary is therefore
 *   handed [LeakCanaryHost], the app with the test APK's resources.
 *
 * The watchers themselves are installed on the real app, so every
 * destroyed Activity, Fragment, View and ViewModel is watched, and each test
 * class's `DetectLeaksAfterTestSuccess` rule fails the test when one is
 * still reachable after it (rubric #11; docs/robustness.md Part 3).
 */
class LeakCheckRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        if (AppWatcher.isInstalled) return
        val host = LeakCanaryHost(app, context.resources)
        // RootViewWatcher reads its setting through the new window's own
        // Resources (the app's, not the host's), which lack LeakCanary's
        // booleans: every Dialog, so every modal sheet, crashed the test with
        // `Resources$NotFoundException` the first time the rotation matrix
        // opened one. Activities, fragments, their views, view models and
        // services are still watched.
        val watchers = AppWatcher.appDefaultWatchers(app).filterNot { it is RootViewWatcher }
        AppWatcher.manualInstall(host, watchersToInstall = watchers)
    }

    /** The app under test as LeakCanary sees it: everything delegates to [app] but the resources. */
    private class LeakCanaryHost(
        app: Application,
        private val testResources: Resources,
    ) : Application() {
        init {
            attachBaseContext(app)
        }

        override fun getResources(): Resources = testResources
    }
}
