package com.scorchsoft.ssmarkdown.preview

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import java.io.File

/** Builds the preview's WebView with every setting locked down before anything loads. */
internal object PreviewWebViews {
    /**
     * JavaScript runs only the bundle shipped in the APK: the CSP allows no
     * other script, DOMPurify strips scripts from documents, and no JavaScript
     * interface is added, so enabling it exposes nothing to page content.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        backgroundColor: Int,
        client: PreviewWebViewClient,
    ): WebView =
        WebView(context).apply {
            // Before the first load: the theme colour shows instead of a white flash.
            setBackgroundColor(backgroundColor)
            with(settings) {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                setGeolocationEnabled(false)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = FULL_TEXT_ZOOM
                mediaPlaybackRequiresUserGesture = true
                // The app is offline; Safe Browsing would phone home for every page.
                safeBrowsingEnabled = false
            }
            // Keep the renderer alive while visible; let the system reclaim it when hidden.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            overScrollMode = View.OVER_SCROLL_NEVER
            // The fast-scroll pill is the only scroll indicator (ux-spec "Fast scroll": no track).
            // A WebView draws the platform's scrollbar by default, which sat beside the pill.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = client
        }

    /**
     * Serves the bundle (and bundled fonts) from assets, relative images from
     * the host's resolver, and installed fonts from the host's font directory.
     */
    fun assetLoader(
        context: Context,
        resolver: () -> DocumentResourceResolver?,
        fonts: () -> File?,
    ): WebViewAssetLoader =
        WebViewAssetLoader
            .Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/doc/", DocumentPathHandler(resolver))
            .addPathHandler("/fonts/", FontPathHandler(fonts))
            .build()

    private const val FULL_TEXT_ZOOM = 100
}
