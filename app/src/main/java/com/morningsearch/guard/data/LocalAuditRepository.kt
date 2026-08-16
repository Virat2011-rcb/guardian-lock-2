package com.morningsearch.guard.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalAuditRepository(context: Context) {
    private val dao = GuardianDatabase.get(context).guardianDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun logTrigger(packageName: String, category: String, plan: EscalationPlan) {
        scope.launch {
            dao.insertTrigger(
                TriggerEventEntity(
                    detectedAt = System.currentTimeMillis(),
                    sourcePackage = packageName,
                    category = category,
                    lockDurationMs = plan.durationMs,
                    escalationLevel = plan.level
                )
            )
        }
    }

    fun logTamper(type: String, detail: String) {
        scope.launch {
            dao.insertTamper(TamperEventEntity(detectedAt = System.currentTimeMillis(), type = type, detail = detail))
        }
    }
}

data class EscalationPlan(val level: Int, val durationMs: Long, val includeEntertainment: Boolean)
