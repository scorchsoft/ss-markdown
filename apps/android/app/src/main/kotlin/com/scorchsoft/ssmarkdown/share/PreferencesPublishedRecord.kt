package com.scorchsoft.ssmarkdown.share

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * [PublishedRecord] in app-private [SharedPreferences]: one string, the
 * specs separated by record and unit separators (U+001E, U+001F). A label
 * holding either character is not recorded (the record is cleared), so the
 * next start reads the platform back instead of trusting a garbled record.
 * The file names it holds are the recent list's own, kept beside it.
 *
 * Read and written on I/O only: the preferences are opened on first use.
 */
class PreferencesPublishedRecord(
    open: () -> SharedPreferences,
) : PublishedRecord {
    private val prefs by lazy(open)

    override fun read(): List<ShortcutSpec>? {
        val raw = prefs.getString(KEY, null) ?: return null
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD).map { line ->
            val fields = line.split(UNIT)
            if (fields.size != FIELDS) return null
            val rank = fields[RANK].toIntOrNull() ?: return null
            ShortcutSpec(fields[ID], fields[SHORT_LABEL], fields[LONG_LABEL], rank)
        }
    }

    override fun write(specs: List<ShortcutSpec>?) {
        val encodable = specs?.all { spec -> listOf(spec.id, spec.shortLabel, spec.longLabel).none(::hasSeparator) }
        val value =
            if (specs == null || encodable != true) {
                null
            } else {
                specs.joinToString(
                    RECORD,
                ) { listOf(it.id, it.rank.toString(), it.shortLabel, it.longLabel).joinToString(UNIT) }
            }
        prefs.edit { if (value == null) remove(KEY) else putString(KEY, value) }
    }

    private fun hasSeparator(text: String) = RECORD in text || UNIT in text

    private companion object {
        const val KEY = "published"
        const val RECORD = "\u001E"
        const val UNIT = "\u001F"
        const val FIELDS = 4
        const val ID = 0
        const val RANK = 1
        const val SHORT_LABEL = 2
        const val LONG_LABEL = 3
    }
}
