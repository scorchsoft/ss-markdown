package com.scorchsoft.ssmarkdown.share

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.scorchsoft.ssmarkdown.AppGraph
import com.scorchsoft.ssmarkdown.MainActivity
import com.scorchsoft.ssmarkdown.SSMarkdownApplication
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequests
import com.scorchsoft.ssmarkdown.files.saf.IntentFields
import com.scorchsoft.ssmarkdown.files.saf.MarkdownFiles
import com.scorchsoft.ssmarkdown.files.saf.SharedContent
import com.scorchsoft.ssmarkdown.files.saf.from
import com.scorchsoft.ssmarkdown.platform.LaunchIntents
import com.scorchsoft.ssmarkdown.platform.OpenTokens
import com.scorchsoft.ssmarkdown.ui.ChromeFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The share target: a small sheet over the sending app, so the user
 * stays where they were (J9). It shares the process's [AppGraph], so an
 * append to a file open in the editor goes into that buffer.
 */
class ShareActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as SSMarkdownApplication).graph

    internal val viewModel: ShareViewModel by lazy {
        ShareViewModel(
            graph.store,
            graph.drafts,
            graph.historyHook,
            graph.settings,
            graph.editor,
            graph.mainScope,
            dateHeading = {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(graph.clock()))
            },
            onAppended = graph.directShare::reportUsed,
        )
    }

    /** The sheet as it stands: what a test of a recreated sheet reads. */
    internal val sheetState: ShareUiState get() = viewModel.state.value

    /**
     * The recent file chosen in Android's share sheet, if this came from one
     * of our Direct Share targets. Mapped back through our own recents and
     * grants, never read as a URI (SEC-2); it only preselects (ADR 0017).
     * Read on I/O: the grants are a binder call (review r3 L8).
     */
    private suspend fun preselect(): String? {
        val id = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID) ?: return null
        return withContext(graph.io) { graph.directShare.resolve(id) }
    }

    override fun onDestroy() {
        // A grant lost since the last sync takes its shortcut with it. Checked as the sheet goes, never
        // while it starts: shortcut calls stay off every launch path (DirectShareTargets).
        if (isFinishing) graph.directShare.refresh()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // An append that finished before the rebuild (a process death in the "Added to" window) is not offered
        // again: the sheet comes back done and closes.
        savedInstanceState?.getString(KEY_DONE_URI)?.let { uri ->
            viewModel.restoreDone(SharePhase.Done(savedInstanceState.getString(KEY_DONE_NAME).orEmpty(), uri))
        }
        // Every create, not only the first: the sheet's state lives in this Activity's own view model, so
        // a recreated sheet (process death, a locale change) must read what was shared again, or it comes
        // back empty with Append enabled.
        readShared(intent)
        setContent {
            val state by viewModel.state.collectAsState()
            val pick =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    graph.permissions.take(DocumentUri(uri.toString()))
                    viewModel.onPicked(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString())
                }
            LaunchedEffect(state.phase) {
                if (state.phase is SharePhase.Done) {
                    delay(CONFIRMATION_MILLIS)
                    finish()
                }
            }
            val fonts = remember { ChromeFonts.families(assets) }
            SSMarkdownTheme(fonts = fonts) {
                ShareSheet(state) { action ->
                    when (action) {
                        ShareAction.Cancel -> finish()
                        ShareAction.AnotherFile -> pick.launch(MarkdownFiles.MIME_TYPES)
                        ShareAction.OpenResult -> openResult(state.phase)
                        else -> viewModel.onAction(action)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (viewModel.state.value.phase as? SharePhase.Done)?.let { done ->
            outState.putString(KEY_DONE_NAME, done.name)
            outState.putString(KEY_DONE_URI, done.uri)
        }
    }

    private fun readShared(intent: Intent) {
        when (val request = IncomingRequests.parse(IntentFields.from(intent))) {
            is IncomingRequest.SharedText -> {
                lifecycleScope.launch {
                    viewModel.begin(
                        request.text,
                        ShareReducer.sourceOf(request.text, request.title),
                        preselect(),
                    )
                }
            }

            is IncomingRequest.SharedStreams -> {
                readStream(request.uris.first())
            }

            else -> {
                viewModel.unusable(null)
            }
        }
    }

    private fun readStream(uri: DocumentUri) {
        lifecycleScope.launch {
            when (val content = withContext(graph.io) { graph.sharedReader.read(uri) }) {
                is SharedContent.Text -> viewModel.begin(content.text, content.name, preselect())
                is SharedContent.TooLarge -> viewModel.unusable(content.sizeBytes)
                else -> viewModel.unusable(null)
            }
        }
    }

    private fun openResult(phase: SharePhase) {
        val done = phase as? SharePhase.Done ?: return
        startActivity(openIntent(done.uri))
        finish()
    }

    companion object {
        /** "Added to <file> · Open" stays this long, then the sheet closes (ux-spec). */
        private const val CONFIRMATION_MILLIS = 1_500L

        /** Saved state: the finished append, so a rebuilt sheet cannot append it again. */
        private const val KEY_DONE_NAME = "share.done.name"
        private const val KEY_DONE_URI = "share.done.uri"

        /**
         * The editor, asked to open [uri] through its launch sequence, so it
         * cannot race the "reopen the last file" default. The intent carries a
         * one-time ticket from [tokens], never the URI itself (SEC-2).
         */
        fun openIntent(
            context: Context,
            tokens: OpenTokens,
            uri: String,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(LaunchIntents.EXTRA_OPEN_TOKEN, tokens.issue(uri))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
    }

    private fun openIntent(uri: String): Intent = openIntent(this, graph.openTokens, uri)
}
