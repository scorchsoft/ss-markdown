package com.scorchsoft.ssmarkdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.shell.ShellScreen
import com.scorchsoft.ssmarkdown.shell.ViewMode

/**
 * The only Activity. It draws edge to edge and handles its own
 * configuration changes (see the manifest), so rotating or switching to
 * dark mode re-lays out the same composition instead of rebuilding it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SSMarkdownTheme {
                var mode by rememberSaveable { mutableStateOf(ViewMode.Code) }
                ShellScreen(mode = mode, onModeSelect = { mode = it })
            }
        }
    }
}
