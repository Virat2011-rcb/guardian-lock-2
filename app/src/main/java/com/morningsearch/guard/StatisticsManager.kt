package com.morningsearch.guard

import android.content.Context
import com.morningsearch.guard.data.GuardianDatabase
import kotlinx.coroutines.runBlocking

class StatisticsManager(private val context: Context) {
    fun snapshot(): FocusStatistics = runBlocking {
        val dao = GuardianDatabase.get(context).guardianDao()
        val now = System.currentTimeMillis()
        FocusStatistics(
            todayMs = dao.completedFocusMsSince(dayStart(now)),
            weekMs = dao.completedFocusMsSince(now - 7L * DAY_MS),
            monthMs = dao.completedFocusMsSince(now - 30L * DAY_MS),
            lifetimeMs = dao.totalCompletedFocusMs()
        )
    }

    private fun dayStart(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object { private const val DAY_MS = 86_400_000L }
}

data class FocusStatistics(val todayMs: Long, val weekMs: Long, val monthMs: Long, val lifetimeMs: Long)
