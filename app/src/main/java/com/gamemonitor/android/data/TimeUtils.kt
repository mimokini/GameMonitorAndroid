package com.gamemonitor.android.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeUtils {

    fun todayKey(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    /** ISO 周几：1=周一 .. 7=周日 */
    fun isoDay(now: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
    }

    fun minuteOfDay(now: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun clockText(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(now))

    fun hhmm(millis: Long?): String =
        if (millis == null) "--:--" else SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

    fun dayLabel(day: Int): String = when (day) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        else -> "日"
    }
}
