package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R

/** "just now", "2 min ago", "1 h ago", "yesterday", "4 days ago": the status line's clock. */
sealed interface RelativeTime {
    data object JustNow : RelativeTime

    data class Minutes(
        val n: Int,
    ) : RelativeTime

    data class Hours(
        val n: Int,
    ) : RelativeTime

    data class Days(
        val n: Int,
    ) : RelativeTime

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /** How long before [now] [then] was. A clock that went backwards reads "just now". */
        fun between(
            then: Long,
            now: Long,
        ): RelativeTime {
            val age = (now - then).coerceAtLeast(0)
            return when {
                age < MINUTE -> JustNow
                age < HOUR -> Minutes((age / MINUTE).toInt())
                age < DAY -> Hours((age / HOUR).toInt())
                else -> Days((age / DAY).toInt())
            }
        }

        /** Same, from an age. */
        fun ofAge(ageMillis: Long): RelativeTime = between(0, ageMillis)
    }
}

/** [time] as words. */
@Composable
fun relativeTimeText(time: RelativeTime): String =
    when (time) {
        RelativeTime.JustNow -> stringResource(R.string.time_just_now)
        is RelativeTime.Minutes -> pluralStringResource(R.plurals.time_minutes_ago, time.n, time.n)
        is RelativeTime.Hours -> pluralStringResource(R.plurals.time_hours_ago, time.n, time.n)
        is RelativeTime.Days -> pluralStringResource(R.plurals.time_days_ago, time.n, time.n)
    }
