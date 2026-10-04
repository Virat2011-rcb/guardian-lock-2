package com.morningsearch.guard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class UrgeReflectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val urgeId = intent.getLongExtra(EXTRA_URGE_ID, 0L)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(28, 40, 28, 28)
        }
        root.addView(TextView(this).apply { text = "What triggered the urge?"; textSize = 24f; setPadding(0, 0, 0, 20) })
        listOf("Bored", "Stress", "Lonely", "Late Night", "Social Media", "Other").forEach { category ->
            root.addView(Button(this).apply {
                text = category; isAllCaps = false
                setOnClickListener { saveReflection(urgeId, category); finish() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(Button(this).apply { text = "Skip"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun saveReflection(urgeId: Long, category: String) {
        val prefs = getSharedPreferences("urge_reflections", MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        old.put(JSONObject().put("urgeId", urgeId).put("category", category).put("timestamp", System.currentTimeMillis()))
        while (old.length() > 100) old.remove(0)
        prefs.edit().putString(KEY, old.toString()).apply()
        RecoverTimeline(this).record("urge_reflection", category)
    }

    companion object {
        const val EXTRA_URGE_ID = "urge_id"
        private const val KEY = "items"
    }
}
