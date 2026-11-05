package com.scorchsoft.ssmarkdown.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * The system's "save a new file" picker for a file type chosen per launch
 * (`CreateDocument` fixes its type when it is made): Export… makes
 * Markdown, HTML or plain text through one launcher.
 */
internal class CreateTypedDocument : ActivityResultContract<CreateTypedDocument.Request, Uri?>() {
    data class Request(
        val name: String,
        val mimeType: String,
    )

    override fun createIntent(
        context: Context,
        input: Request,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}
