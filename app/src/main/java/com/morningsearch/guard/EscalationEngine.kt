package com.morningsearch.guard

import com.morningsearch.guard.data.EscalationPlan

object EscalationEngine {
    private val durations = longArrayOf(
        2L * 60L * 60L * 1000L,
        6L * 60L * 60L * 1000L,
        12L * 60L * 60L * 1000L,
        24L * 60L * 60L * 1000L,
        48L * 60L * 60L * 1000L
    )

    fun plan(priorTriggersIn24Hours: Int): EscalationPlan {
        val level = (priorTriggersIn24Hours + 1).coerceAtMost(durations.size)
        return EscalationPlan(
            level = level,
            durationMs = durations[level - 1],
            includeEntertainment = level >= 3
        )
    }
}
