package com.scorchsoft.ssmarkdown.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.scorchsoft.ssmarkdown.history.versions.HistoryPolicy
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * [AppSettings] as a single JSON string in [SharedPreferences]: one key,
 * one atomic `apply()`, and a schema that tolerates missing fields, so an
 * older or damaged value falls back field by field to the defaults.
 */
class PreferencesSettingsStore(
    private val preferences: SharedPreferences,
) : SettingsStore {
    override fun read(): AppSettings {
        val raw = preferences.getString(KEY, null) ?: return AppSettings()
        return try {
            SettingsJson.decode(JSONObject(raw))
        } catch (_: JSONException) {
            AppSettings()
        }
    }

    override fun write(settings: AppSettings) {
        preferences.edit { putString(KEY, SettingsJson.encode(settings).toString()) }
    }

    private companion object {
        const val KEY = "settings.v1"
    }
}

/** The JSON shape of [AppSettings]. Pure, so the round trip is unit tested. */
@Suppress("TooManyFunctions") // An encode and a decode per nested type.
internal object SettingsJson {
    fun encode(s: AppSettings): JSONObject =
        JSONObject()
            .put("openWith", s.openWith.name)
            .put("onLaunch", s.onLaunch.name)
            .put("zoomCode", s.defaultZoomCode)
            .put("zoomPreview", s.defaultZoomPreview)
            .put("lineNumbers", s.lineNumbers)
            .put("codeWrap", s.codeWrap)
            .put("saveMode", s.saveMode.name)
            .put("tapToEdit", s.tapToEditPreview)
            .put("scrollTogether", s.scrollPanesTogether)
            .put("appearance", s.appearance.name)
            .put("history", encodeHistory(s.history))
            .put("autosave", JSONObject(s.autosaveOverrides))
            .put("recent", JSONArray(s.recentFiles.map(::encodeRecent)))
            .put("lastFile", s.lastFile)
            .put("share", encodeShare(s.share))
            .put("previewTheme", s.previewThemeId)
            .put("previewWrap", s.previewWrap.name)
            .put("export", encodeExport(s.export))
            .put("copyFormat", s.copyFormat.name)
            .put("formattingBar", s.formattingBar.name)
            .put("splitTyping", s.splitTyping.name)

    fun decode(o: JSONObject): AppSettings {
        val d = AppSettings()
        return AppSettings(
            openWith = enumOr(o.optString("openWith"), d.openWith),
            onLaunch = enumOr(o.optString("onLaunch"), d.onLaunch),
            defaultZoomCode = o.optInt("zoomCode", d.defaultZoomCode),
            defaultZoomPreview = o.optInt("zoomPreview", d.defaultZoomPreview),
            // Absent in settings saved before it was wired: those installs keep their numbers.
            lineNumbers = o.optBoolean("lineNumbers", d.lineNumbers),
            codeWrap = o.optBoolean("codeWrap", d.codeWrap),
            saveMode = enumOr(o.optString("saveMode"), d.saveMode),
            tapToEditPreview = o.optBoolean("tapToEdit", d.tapToEditPreview),
            scrollPanesTogether = o.optBoolean("scrollTogether", d.scrollPanesTogether),
            appearance = enumOr(o.optString("appearance"), d.appearance),
            history = o.optJSONObject("history")?.let(::decodeHistory) ?: d.history,
            autosaveOverrides = o.optJSONObject("autosave")?.let(::decodeOverrides).orEmpty(),
            recentFiles = o.optJSONArray("recent")?.let(::decodeRecents).orEmpty(),
            lastFile = o.optString("lastFile").takeIf { it.isNotEmpty() && o.has("lastFile") && !o.isNull("lastFile") },
            share = o.optJSONObject("share")?.let(::decodeShare) ?: d.share,
            previewThemeId = o.optString("previewTheme").ifEmpty { d.previewThemeId },
            // Before No wrap existed this was a boolean, "pageMode"; it still reads.
            previewWrap =
                enumOr(
                    o.optString("previewWrap"),
                    if (o.optBoolean("pageMode", false)) PreviewWrap.Page else d.previewWrap,
                ),
            export = o.optJSONObject("export")?.let(::decodeExport) ?: d.export,
            copyFormat = enumOr(o.optString("copyFormat"), d.copyFormat),
            // Absent before the setting existed: those installs stay on one row.
            formattingBar = enumOr(o.optString("formattingBar"), d.formattingBar),
            // Absent before the setting existed: those installs get the default, the edited pane only.
            splitTyping = enumOr(o.optString("splitTyping"), d.splitTyping),
        )
    }

    private fun encodeHistory(h: HistoryPolicy) =
        JSONObject()
            .put("recording", h.recording)
            .put("total", (h.totalLimit as? StorageLimit.Bytes)?.bytes ?: NO_LIMIT)
            .put("perFile", (h.perFileLimit as? StorageLimit.Bytes)?.bytes ?: NO_LIMIT)
            .put("forgetDays", h.forgetUnusedAfterDays ?: 0)

    private fun decodeHistory(o: JSONObject): HistoryPolicy {
        val d = HistoryPolicy()

        fun limit(
            key: String,
            fallback: StorageLimit,
        ): StorageLimit {
            if (!o.has(key)) return fallback
            val bytes = o.optLong(key, NO_LIMIT)
            return if (bytes < 0) StorageLimit.None else StorageLimit.Bytes(bytes)
        }
        return HistoryPolicy(
            recording = o.optBoolean("recording", d.recording),
            totalLimit = limit("total", d.totalLimit),
            perFileLimit = limit("perFile", d.perFileLimit),
            forgetUnusedAfterDays = o.optInt("forgetDays", 0).takeIf { it > 0 },
        )
    }

    private fun decodeOverrides(o: JSONObject): Map<String, Boolean> =
        o.keys().asSequence().associateWith {
            o.optBoolean(it)
        }

    private fun encodeRecent(r: RecentFile) =
        JSONObject()
            .put("uri", r.uri)
            .put("name", r.name)
            .put("location", r.location)
            .put("at", r.openedAtMillis)
            .put("pinned", r.pinned)

    private fun decodeRecents(a: JSONArray): List<RecentFile> =
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RecentFile(uri, o.optString("name"), o.optString("location"), o.optLong("at"), o.optBoolean("pinned"))
        }

    private fun encodeShare(s: ShareDefaults) =
        JSONObject()
            .put("target", s.lastTarget)
            .put("position", s.position.name)
            .put("prefix", s.prefix.name)
            .put("directShare", s.directShare)

    private fun decodeShare(o: JSONObject) =
        ShareDefaults(
            lastTarget = o.optString("target").takeIf { it.isNotEmpty() && !o.isNull("target") },
            position = enumOr(o.optString("position"), AppendPosition.Bottom),
            prefix = enumOr(o.optString("prefix"), AppendPrefix.None),
            // Absent before the setting existed: those installs get the default, on.
            directShare = o.optBoolean("directShare", true),
        )

    private fun encodeExport(e: ExportDefaults) =
        JSONObject()
            .put("format", e.format.name)
            .put("scale", e.scale)
            .put("destination", e.destination.name)
            .put("zipFolder", e.zipFolder)

    private fun decodeExport(o: JSONObject): ExportDefaults {
        val d = ExportDefaults()
        return ExportDefaults(
            format = enumOr(o.optString("format"), d.format),
            scale = o.optInt("scale", d.scale).coerceIn(1, MAX_SCALE),
            destination = enumOr(o.optString("destination"), d.destination),
            zipFolder = o.optString("zipFolder").takeIf { it.isNotEmpty() && !o.isNull("zipFolder") },
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(
        name: String,
        fallback: E,
    ): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private const val NO_LIMIT = -1L
    private const val MAX_SCALE = 3
}
