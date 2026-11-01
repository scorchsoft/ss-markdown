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
import androidx.compose.runtime.remember
import com.scorchsoft.ssmarkdown.about.Licences
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.editor.LaunchRequest
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.IntentFields
import com.scorchsoft.ssmarkdown.files.saf.from
import com.scorchsoft.ssmarkdown.fonts.AndroidFonts
import com.scorchsoft.ssmarkdown.nav.AppRoot
import com.scorchsoft.ssmarkdown.platform.LaunchIntents
import com.scorchsoft.ssmarkdown.platform.PlatformActions
import com.scorchsoft.ssmarkdown.preview.PreviewView
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.ui.ChromeFonts
import com.scorchsoft.ssmarkdown.ui.TickingNow
import java.io.File
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

    /**
     * The one Preview WebView, owned by this Activity: it handles its own
     * configuration changes, so the view survives rotation and folding and
     * is placed wherever the Preview pane is (robustness §5a, rule 2).
     * Created on first use; nothing starts a WebView until Preview shows.
     */
    val previewView: PreviewView by lazy {
        PreviewView(this).apply {
            // Relative pictures (`![](img/a.png)`) through the folder grant, and the theme's installed fonts.
            resourceResolver = graph.pictures
            fontDirectory = File(filesDir, AndroidFonts.DIRECTORY)
            backgroundExecutor = graph.previewEncoder
        }
    }
    private var previewCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.editor.start(launchRequest(intent))
        val platform = PlatformActions(this, graph.io)
        // Parsed when the Licences screen first opens, not on every launch (PERF-21).
        val licences by lazy { Licences.parse(readAsset(LICENCES_ASSET)) }
        setContent {
            val settings by graph.settings.settings.collectAsState()
            val dark =
                when (settings.appearance) {
                    Appearance.System -> isSystemInDarkTheme()
                    Appearance.Light -> false
                    Appearance.Dark -> true
                }
            // Inter and JetBrains Mono from feature/fonts' bundle (UX2-12).
            val fonts = remember { ChromeFonts.families(assets) }
            SSMarkdownTheme(darkTheme = dark, fonts = fonts) {
                TickingNow(graph.clock) {
                    AppRoot(
                        graph,
                        platform,
                        versionName(),
                        { licences },
                        previewView =
                            previewView.also {
                                previewCreated =
                                    true
                            },
                    )
                }
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

    override fun onDestroy() {
        if (previewCreated) {
            graph.editor.preview.detach(previewView)
            previewView.release()
        }
        super.onDestroy()
    }

    override fun onPause() {
        // Group-commit the journal now and let autosave write: we may not get another chance.
        graph.editor.onPause()
        super.onPause()
    }

    /** What [intent] asks to open: see [LaunchIntents] (SEC-2: no URI is taken from an extra). */
    private fun launchRequest(intent: Intent?): LaunchRequest? {
        intent ?: return null
        val fields = IntentFields.from(intent)
        val request =
            LaunchIntents.request(
                fields,
                intent.getStringExtra(LaunchIntents.EXTRA_OPEN_TOKEN),
                graph.openTokens::redeem,
                ::sourceAppName,
            )
        // Keep a persistable grant, so unsaved edits can still reach their file after a restart (R2-DS-2).
        LaunchIntents.grantToPersist(fields, request)?.let { keep ->
            val uri = DocumentUri(keep.uri)
            if (!(
                    keep.writable &&
                        graph.permissions.take(
                            uri,
                            write = true,
                        )
                )
            ) {
                graph.permissions.take(uri, write = false)
            }
        }
        return request
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

    companion object {
        private const val LICENCES_ASSET = "licences.txt"
    }
}
