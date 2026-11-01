package com.scorchsoft.ssmarkdown

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.scorchsoft.ssmarkdown.about.Licences
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.editor.LaunchRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequests
import com.scorchsoft.ssmarkdown.files.saf.IntentFields
import com.scorchsoft.ssmarkdown.files.saf.from
import com.scorchsoft.ssmarkdown.nav.AppRoot
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.ui.TickingNow
import java.io.IOException

/**
 * The only editing Activity. It draws edge to edge and handles its own
 * configuration changes (see the manifest), so rotating or switching to
 * dark mode re-lays out the same composition instead of rebuilding it.
 * Everything it shows lives in the process-scoped [AppGraph]; a
 * recreation (locale, process death) rebinds to the same store.
 */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as SSMarkdownApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.editor.start(launchRequest(intent))
        val platform = PlatformActions(this, graph.io)
        val licences = Licences.parse(readAsset(LICENCES_ASSET))
        setContent {
            val settings by graph.settings.settings.collectAsState()
            val dark =
                when (settings.appearance) {
                    Appearance.System -> isSystemInDarkTheme()
                    Appearance.Light -> false
                    Appearance.Dark -> true
                }
            SSMarkdownTheme(darkTheme = dark) {
                TickingNow(graph.clock) { AppRoot(graph, platform, versionName(), licences) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchRequest(intent)?.let(graph.editor::start)
    }

    override fun onResume() {
        super.onResume()
        graph.editor.onResume()
    }

    override fun onPause() {
        // Group-commit the journal now and let autosave write: we may not get another chance.
        graph.editor.onPause()
        super.onPause()
    }

    /** VIEW / EDIT from another app: edit in place (R2), read-only with "Save as…" when no write grant came. */
    @Suppress("ReturnCount") // No intent and an unsupported one both mean nothing to open.
    private fun launchRequest(intent: Intent?): LaunchRequest? {
        intent ?: return null
        val request = IncomingRequests.parse(IntentFields.from(intent)) as? IncomingRequest.OpenInPlace ?: return null
        return LaunchRequest(request.uri.value, request.writable, sourceAppName())
    }

    /** The sending app's name for "Read-only · from Gmail", when Android tells us who it was. */
    private fun sourceAppName(): String? {
        val pkg = referrer?.host ?: return null
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }

    private fun readAsset(name: String): String =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            ""
        }

    private companion object {
        const val LICENCES_ASSET = "licences.txt"
    }
}
