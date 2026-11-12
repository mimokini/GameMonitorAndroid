package com.gamemonitor.android.domain

import com.gamemonitor.android.data.ScheduleRule
import com.gamemonitor.android.data.ScheduleType
import com.gamemonitor.android.data.TimeUtils

object ScheduleMatcher {

    /** 单条规则在当前时刻是否生效；start > end 视为跨零点。 */
    fun ruleActive(rule: ScheduleRule, now: Long): Boolean {
        if (!rule.enabled) return false
        val start = rule.startMinute
        val end = rule.endMinute
        if (start == end) return true // 视为全天
        val minute = TimeUtils.minuteOfDay(now)
        val today = TimeUtils.isoDay(now)
        return if (start < end) {
            today in rule.days && minute >= start && minute < end
        } else {
            (today in rule.days && minute >= start) ||
                (isYesterdayIso(today) in rule.days && minute < end)
        }
    }

    private fun isYesterdayIso(today: Int): Int = if (today == 1) 7 else today - 1

    /**
     * 是否处于“禁止使用”状态：
     * 1) 命中任一禁止时段 -> 禁止；
     * 2) 存在启用中的开放时段但当前不在任何开放时段内 -> 禁止；
     * 3) 否则放行。
     */
    fun isBlocked(rules: List<ScheduleRule>, now: Long): Boolean {
        val enabled = rules.filter { it.enabled }
        if (enabled.any { it.type == ScheduleType.BLOCK && ruleActive(it, now) }) return true
        val allowRules = enabled.filter { it.type == ScheduleType.ALLOW }
        return allowRules.isNotEmpty() && allowRules.none { ruleActive(it, now) }
    }
}
