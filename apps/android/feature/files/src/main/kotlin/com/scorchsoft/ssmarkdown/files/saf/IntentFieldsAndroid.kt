package com.scorchsoft.ssmarkdown.files.saf

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Reads the fields [IncomingRequests.parse] needs from a real [Intent]. */
fun IntentFields.Companion.from(intent: Intent): IntentFields =
    IntentFields(
        action = intent.action,
        data = intent.dataString,
        type = intent.type,
        streams = streamsOf(intent).map(Uri::toString),
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
        grantsWrite = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
        grantsPersistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0,
    )

private fun streamsOf(intent: Intent): List<Uri> =
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    } else {
        val single =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        listOfNotNull(single)
    }
