package com.morningsearch.guard

import android.content.Context

class FocusTimer(context: Context) {
    private val store = GuardStore(context)
    fun remainingMs(): Long = store.effectiveStudyRemainingMs()
    fun endTime(): Long = store.studyEndsAt
}
