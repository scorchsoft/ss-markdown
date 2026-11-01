package com.scorchsoft.ssmarkdown.editor.preview

import android.webkit.WebView
import com.scorchsoft.ssmarkdown.preview.PreviewView
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import kotlin.coroutines.resume

/**
 * "Copy as rich text" from the preview renderer itself, so pasted HTML
 * looks like the Preview: the page's own rendering of source lines
 * `[from, to)`, cleaned of editing attributes.
 *
 * The page renders sections lazily, so it is first asked to render
 * everything (the print path, which never opens a dialog unless a print
 * was requested), and the HTML is read once the wanted sections are
 * mounted; then the page goes back to virtualised reading.
 *
 * The bridge has no "give me HTML" message yet, so this reads the DOM
 * with `evaluateJavascript`, host to page only: nothing is exposed to
 * page content. A protocol message in `feature/preview` should replace it.
 */
object RenderedHtml {
    private const val POLL_MILLIS = 100L
    private const val TIMEOUT_MILLIS = 5_000L

    /** The HTML for lines `[from, to)`, or null when the preview cannot give it (then copy plain text). */
    suspend fun read(
        view: PreviewView,
        from: Int,
        to: Int,
    ): String? {
        val web =
            (0 until view.childCount).map(view::getChildAt).filterIsInstance<WebView>().firstOrNull() ?: return null
        view.send(HostMessage.PreparePrint)
        return try {
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                var html: String? = null
                while (html == null) {
                    html = evaluate(web, script(from, to))
                    if (html == null) delay(POLL_MILLIS)
                }
                html
            }
        } finally {
            view.send(HostMessage.FinishPrint)
        }
    }

    private suspend fun evaluate(
        web: WebView,
        script: String,
    ): String? =
        suspendCancellableCoroutine { done ->
            web.evaluateJavascript(script) { raw -> done.resume(decode(raw)) }
        }

    /** evaluateJavascript hands back a JSON value: a string, or `null` while sections are still rendering. */
    private fun decode(raw: String?): String? =
        try {
            raw?.let { JSONArray("[$it]") }?.takeUnless { it.isNull(0) }?.getString(0)
        } catch (_: JSONException) {
            null
        }

    /** The page-side read: the wanted sections' blocks, without editing attributes or chips. */
    private fun script(
        from: Int,
        to: Int,
    ): String =
        """
        (function(a, b) {
          var secs = Array.prototype.filter.call(document.querySelectorAll('section.sec'), function(s) {
            return Number(s.dataset.lineEnd) > a && Number(s.dataset.line) < b;
          });
          if (secs.some(function(s) { return !s.classList.contains('mounted'); })) return null;
          var out = document.createElement('div');
          secs.forEach(function(s) {
            Array.prototype.forEach.call(s.children, function(el) {
              var line = Number(el.getAttribute('data-line'));
              if (isNaN(line) || (line >= a && line < b)) out.appendChild(el.cloneNode(true));
            });
          });
          out.querySelectorAll('button, [data-chip], .edit-chip').forEach(function(e) { e.remove(); });
          out.querySelectorAll('*').forEach(function(e) {
            Array.prototype.slice.call(e.attributes).forEach(function(at) {
              if (at.name === 'contenteditable' || at.name.indexOf('data-') === 0) e.removeAttribute(at.name);
            });
          });
          return out.innerHTML;
        })($from, $to)
        """.trimIndent()
}
