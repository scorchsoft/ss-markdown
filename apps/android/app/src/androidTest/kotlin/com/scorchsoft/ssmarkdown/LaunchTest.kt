package com.scorchsoft.ssmarkdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: the launcher Activity starts straight into an editor with the two bars. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    // Outermost, so it checks for leaks after the Activity has been closed.
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToAnEditor() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }
        rule.onNodeWithText("Code").assertIsDisplayed()
        rule.onNodeWithText("Preview").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}

/** The process's graph, as the Activity sees it. */
fun MainActivity.graph(): AppGraph = (application as SSMarkdownApplication).graph
