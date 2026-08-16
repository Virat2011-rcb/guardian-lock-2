package com.morningsearch.guard

import android.content.Context
import com.morningsearch.guard.data.LocalAuditRepository

class TamperManager(private val context: Context) {
    private val store = GuardStore(context)
    private val audit = LocalAuditRepository(context)

    fun record(type: String, detail: String, points: Int) {
        audit.logTamper(type, detail)
        val score = store.addTamperScore(points)
        if (score >= GuardConfig.TAMPER_LOCK_THRESHOLD) {
            LockManager(context).beginTamperLock("tamper_score_$score")
        }
    }
}
