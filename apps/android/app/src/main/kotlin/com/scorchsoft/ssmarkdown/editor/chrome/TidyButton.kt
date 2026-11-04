package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.ui.AppIcons

/** ⋮'s Tidy: evens out the Markdown without changing how it reads; it explains itself and asks first. */
@Composable
internal fun TidyButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(SS.dimens.iconButton)) {
        Icon(AppIcons.tidy, contentDescription = stringResource(R.string.tidy_button), tint = SS.colors.ink)
    }
}
