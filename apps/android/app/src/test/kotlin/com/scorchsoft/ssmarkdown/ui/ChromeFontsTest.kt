package com.scorchsoft.ssmarkdown.ui

import android.content.res.AssetManager
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * UX2-12: the chrome is set in the bundled Inter and the code in the
 * bundled JetBrains Mono, read from feature/fonts' assets, never the
 * system families while those files ship.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChromeFontsTest {
    private val assets: AssetManager = ApplicationProvider.getApplicationContext<android.app.Application>().assets

    @Test
    fun `the chrome families are the bundled ones, not the system's`() {
        val fonts = ChromeFonts.families(assets)
        assertNotEquals(FontFamily.Default, fonts.sans)
        assertNotEquals(FontFamily.Monospace, fonts.mono)
    }

    @Test
    fun `the code pane gets a typeface from the bundled file`() {
        assertNotNull(ChromeFonts.codeTypeface(assets))
    }
}
