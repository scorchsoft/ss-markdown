package com.scorchsoft.ssmarkdown

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import leakcanary.AppWatcher

/**
 * The instrumented tests' runner: AndroidJUnitRunner, plus LeakCanary's
 * object watcher installed in the app's process before any test runs.
 *
 * LeakCanary lives only in the test APK (androidTestImplementation), whose
 * own manifest providers never start in the app's process, so it cannot
 * install itself as it would in an app that ships it. Installed here, it
 * watches every destroyed Activity, Fragment, View and ViewModel, and each
 * test class's `DetectLeaksAfterTestSuccess` rule fails the test when one
 * is still reachable after the test (rubric #11; docs/robustness.md Part 3).
 */
class LeakCheckRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        if (!AppWatcher.isInstalled) AppWatcher.manualInstall(app)
    }
}
