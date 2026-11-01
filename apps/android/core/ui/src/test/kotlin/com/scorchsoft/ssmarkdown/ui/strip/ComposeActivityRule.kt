package com.scorchsoft.ssmarkdown.ui.strip

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf

/**
 * A compose rule for a library module. `createComposeRule` launches a
 * plain [ComponentActivity], which an app registers in a debug manifest;
 * a library has no manifest to put it in, and one in `src/debug` would
 * leak into every app that depends on it. So the activity is registered
 * with Robolectric's package manager just before the rule launches it.
 */
class ComposeActivityRule(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>(),
) {
    /** The rule to annotate with `@get:Rule`. */
    val chain: RuleChain = RuleChain.outerRule(RegisterActivity).around(compose)

    private object RegisterActivity : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }
}
