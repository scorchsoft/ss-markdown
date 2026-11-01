package com.scorchsoft.ssmarkdown.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why the previous process ended, so the recovery banner can say "SS
 * Markdown was closed before you saved" after a swipe from Recents
 * rather than "closed unexpectedly" (ux-spec "More decisions from review 3").
 * Android 11+ records the reason; on Android 10 we cannot tell and say
 * the neutral thing.
 */
class ExitReasons(
    private val context: Context,
) {
    /** True when the last exit was the user removing the app from Recents. */
    @Suppress("ReturnCount") // Old Android, no service and no record each mean "cannot tell".
    fun lastExitWasUserSwipe(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val last = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return false
        return last.reason == ApplicationExitInfo.REASON_USER_REQUESTED
    }
}
