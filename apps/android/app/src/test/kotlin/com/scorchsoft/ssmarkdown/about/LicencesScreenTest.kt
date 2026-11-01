package com.scorchsoft.ssmarkdown.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scorchsoft.ssmarkdown.ui.Themed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The licences list opens each component's full text (CC-2). */
@RunWith(AndroidJUnit4::class)
class LicencesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val inter = Licence("Inter", "SIL Open Font License 1.1", "github", "fonts/licences/inter-OFL.txt")

    @Test
    fun `a row with a text opens it`() {
        var opened: Licence? = null
        rule.setContent { Themed(false) { LicencesScreen(listOf(inter), {}, onOpen = { opened = it }) } }
        rule.onNodeWithText("Inter").performClick()
        assertEquals(inter, opened)
    }

    @Test
    fun `the text screen shows the licence as shipped`() {
        val paragraphs = Licences.paragraphs("Copyright 2020 The Inter Project Authors\n\nSIL OPEN FONT LICENSE")
        rule.setContent { Themed(true) { LicenceTextScreen("Inter", paragraphs, {}) } }
        rule.onNodeWithText("Copyright 2020 The Inter Project Authors").assertIsDisplayed()
        rule.onNodeWithText("SIL OPEN FONT LICENSE").assertIsDisplayed()
    }

    @Test
    fun `a missing text says so instead of showing nothing`() {
        rule.setContent { Themed(false) { LicenceTextScreen("Inter", emptyList(), {}) } }
        rule.onNodeWithText("This licence text is missing", substring = true).assertIsDisplayed()
    }

    @Test
    fun `rows parse with and without a text asset`() {
        val parsed = Licences.parse("# c\nA | MIT | x | licences/a.txt\nB | MIT | y\nC | MIT | z |\n")
        assertEquals(listOf("licences/a.txt", null, null), parsed.map { it.textAsset })
    }

    @Test
    fun `paragraphs split at blank lines and keep the wrapping inside one`() {
        assertEquals(listOf("one\ntwo", "three"), Licences.paragraphs("one\ntwo\r\n\r\nthree\n"))
    }
}
