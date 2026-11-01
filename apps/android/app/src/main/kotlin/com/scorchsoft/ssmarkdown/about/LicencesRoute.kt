package com.scorchsoft.ssmarkdown.about

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The licences list and, over it, one licence's full text. Which text is
 * open is local state rather than a screen on the app's stack: it is a
 * detail of this one screen, and Back closes it before leaving the list
 * (this BackHandler is registered after the app's own, so it runs first).
 */
@Composable
fun LicencesRoute(
    licences: List<Licence>,
    onBack: () -> Unit,
) {
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    val open = licences.firstOrNull { it.name == openName && it.textAsset != null }
    if (open == null) {
        LicencesScreen(licences, onBack, onOpen = { openName = it.name })
        return
    }
    val assets = LocalContext.current.assets
    val paragraphs by produceState<List<String>?>(null, open.textAsset) {
        value = withContext(Dispatchers.IO) { LicenceTexts.read(assets, open.textAsset.orEmpty()) }
    }
    BackHandler { openName = null }
    LicenceTextScreen(open.name, paragraphs, onBack = { openName = null })
}

/** Reads a packaged licence text. Off the main thread: one of them is 85 KB. */
object LicenceTexts {
    /** The paragraphs of the asset at [path], or empty when it is missing (shown, never thrown). */
    fun read(
        assets: AssetManager,
        path: String,
    ): List<String> =
        try {
            Licences.paragraphs(assets.open(path).bufferedReader().use { it.readText() })
        } catch (_: IOException) {
            emptyList()
        }
}
