package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Names a control for TalkBack when what it draws is a glyph (".*" reads "Regular expression"). */
fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
