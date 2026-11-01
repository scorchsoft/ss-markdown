package com.scorchsoft.ssmarkdown.about

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The APK must carry the full text of every licence it lists (code-craft
 * review CC-2): MIT, BSD, Apache and OFL all require the notice to travel
 * with a binary. Reads the merged assets the app actually ships, so a
 * renamed font licence or a dropped generated file fails here.
 */
@RunWith(AndroidJUnit4::class)
class LicenceTextsTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets
    private val licences = Licences.parse(assets.open("licences.txt").bufferedReader().use { it.readText() })

    private fun text(path: String): String = LicenceTexts.read(assets, path).joinToString("\n\n")

    @Test
    fun `every listed component opens a packaged licence text`() {
        assertTrue("the list is empty", licences.size > 10)
        licences.forEach { licence ->
            val path = licence.textAsset
            assertTrue("${licence.name} names no licence text", path != null)
            assertTrue("${licence.name}: $path is not in the APK", text(path.orEmpty()).length > MIN_TEXT)
        }
    }

    @Test
    fun `the ported VS Code piece tree ships its MIT permission notice`() {
        val row = licences.single { it.name.startsWith("Visual Studio Code") }
        val notice = text(row.textAsset.orEmpty())
        assertTrue(notice.contains("Copyright (c) 2015 - present Microsoft Corporation"))
        assertTrue(notice.contains("Permission is hereby granted, free of charge"))
    }

    @Test
    fun `the Apache rows ship the full licence, not a pointer to it`() {
        val apache = text("licences/Apache-2.0.txt")
        assertTrue(apache.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(apache.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun `every font row ships its own OFL text`() {
        licences.filter { it.licence.startsWith("SIL Open Font License") }.forEach { font ->
            assertTrue(font.name, text(font.textAsset.orEmpty()).contains("SIL OPEN FONT LICENSE Version 1.1"))
        }
    }

    @Test
    fun `a missing text reads as empty rather than throwing`() {
        assertEquals(emptyList<String>(), LicenceTexts.read(assets, "licences/nope.txt"))
    }

    private companion object {
        const val MIN_TEXT = 500
    }
}
