package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * edge-cases "Dark mode white flash when the WebView loads": the WebView
 * is painted in the theme's background before it has loaded anything, so
 * there is no white frame before the page's own CSS arrives. And it is
 * locked down before the first load: no file or content access. Its
 * own scrollbar is off: the fast-scroll pill is the only indicator.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewWebViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the WebView wears the theme's background before anything loads`() {
        val dark = Color.rgb(0x16, 0x16, 0x18)
        val client =
            PreviewWebViewClient(PreviewWebViews.assetLoader(context, { null }, { null }), {}, {})

        val view = PreviewWebViews.create(context, dark, client)

        assertEquals(dark, shadowOf(view).backgroundColor)
        assertNull("nothing loaded yet", shadowOf(view).lastLoadedUrl)
        assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.allowContentAccess)
    }

    @Test
    fun `the WebView draws no scrollbar of its own beside the fast-scroll pill`() {
        val client =
            PreviewWebViewClient(PreviewWebViews.assetLoader(context, { null }, { null }), {}, {})

        val view = PreviewWebViews.create(context, Color.BLACK, client)

        assertFalse(view.isVerticalScrollBarEnabled)
        assertFalse(view.isHorizontalScrollBarEnabled)
    }
}
