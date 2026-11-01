package com.scorchsoft.ssmarkdown.preview

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * The preview's only door to the outside world, and it is shut:
 *
 * - Requests are answered from the APK's assets (the bundle) or the host's
 *   document folder (relative images) through [WebViewAssetLoader]. Anything
 *   else gets an empty 404, so a document can never reach the network.
 * - Navigation is always refused. Links are reported by the page itself and
 *   classified by [com.scorchsoft.ssmarkdown.preview.bridge.LinkPolicy].
 * - A dead renderer is reported, never left to crash the app.
 */
internal class PreviewWebViewClient(
    private val assetLoader: WebViewAssetLoader,
    private val onPageFinished: () -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse = assetLoader.shouldInterceptRequest(request.url) ?: blocked()

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = true

    override fun onPageFinished(
        view: WebView,
        url: String,
    ) {
        if (url == PREVIEW_URL) onPageFinished()
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }

    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", NOT_FOUND, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        /** The asset loader's origin; the page's CSP treats it as 'self'. */
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PREVIEW_URL = "$ORIGIN/assets/preview/index.html"
        private const val NOT_FOUND = 404
    }
}
