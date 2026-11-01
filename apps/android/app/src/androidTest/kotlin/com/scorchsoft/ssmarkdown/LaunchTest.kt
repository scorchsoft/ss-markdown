package com.scorchsoft.ssmarkdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: the launcher Activity starts and draws the shell. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToAnUntitledDraft() {
        rule.onNodeWithText("Untitled").assertIsDisplayed()
        rule.onNodeWithText("Code").assertIsDisplayed()
    }
}
