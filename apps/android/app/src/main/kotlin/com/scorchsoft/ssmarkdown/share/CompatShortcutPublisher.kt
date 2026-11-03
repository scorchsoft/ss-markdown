package com.scorchsoft.ssmarkdown.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.scorchsoft.ssmarkdown.MainActivity
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.platform.LaunchIntents

/**
 * [ShortcutPublisher] over `ShortcutManagerCompat`. Each recent file is a
 * long-lived sharing shortcut in [CATEGORY_APPEND_TARGET] (see
 * `res/xml/shortcuts.xml`), so the system share sheet offers it for text.
 * Its own intent is what the launcher's long-press menu starts:
 * `MainActivity` with [LaunchIntents.ACTION_OPEN_RECENT] and the id, never the URI.
 */
class CompatShortcutPublisher(
    context: Context,
) : ShortcutPublisher {
    private val app = context.applicationContext

    override val maxPerActivity: Int get() = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)

    override fun current(): List<ShortcutSpec> =
        ShortcutManagerCompat
            .getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)
            .filter { it.id.startsWith(DirectShareIds.PREFIX) }
            .map { ShortcutSpec(it.id, it.shortLabel.toString(), it.longLabel?.toString().orEmpty(), it.rank) }

    override fun push(spec: ShortcutSpec): Boolean {
        val shortcut =
            ShortcutInfoCompat
                .Builder(app, spec.id)
                .setShortLabel(spec.shortLabel)
                .setLongLabel(spec.longLabel)
                .setIcon(IconCompat.createWithResource(app, R.drawable.ic_shortcut_document))
                .setIntent(openIntent(app, spec.id))
                .setCategories(setOf(CATEGORY_APPEND_TARGET))
                .setLongLived(true)
                .setRank(spec.rank)
                .build()
        // False while the platform rate-limits us (API 29, our minSdk, through the compat library).
        return ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
    }

    override fun remove(ids: List<String>) {
        ShortcutManagerCompat.removeDynamicShortcuts(app, ids)
        // Long-lived shortcuts the system cached (for the share sheet's ranking) outlive the dynamic ones.
        ShortcutManagerCompat.removeLongLivedShortcuts(app, ids)
    }

    override fun reportUsed(id: String) = ShortcutManagerCompat.reportShortcutUsed(app, id)

    companion object {
        /** The share-target category in `res/xml/shortcuts.xml`. */
        const val CATEGORY_APPEND_TARGET: String = "com.scorchsoft.ssmarkdown.category.APPEND_TARGET"

        /** The intent a recent file's shortcut starts from the launcher. */
        fun openIntent(
            context: Context,
            id: String,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(LaunchIntents.ACTION_OPEN_RECENT)
                .putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
