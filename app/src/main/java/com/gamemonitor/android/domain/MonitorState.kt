package com.gamemonitor.android.domain

/** 单机状态机状态，对应 Windows 版的 Idle/Running/... 语义。 */
enum class MonitorState {
    IDLE,
    RUNNING,
    COUNTING_DOWN,
    COOLDOWN,
    QUOTA_EXHAUSTED,
    BLOCKED_BY_SCHEDULE,
    ;

    val displayName: String
        get() = when (this) {
            IDLE -> "空闲"
            RUNNING -> "计时中"
            COUNTING_DOWN -> "倒计时"
            COOLDOWN -> "休息期"
            QUOTA_EXHAUSTED -> "配额用尽"
            BLOCKED_BY_SCHEDULE -> "时段禁止"
        }
}
