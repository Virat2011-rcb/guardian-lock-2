package com.morningsearch.guard.data

import android.content.Context
import com.morningsearch.guard.SearchClassifier
import org.json.JSONObject
import java.io.InputStream

object KeywordPackImporter {
    suspend fun import(context: Context, input: InputStream): Int {
        val root = JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val version = root.getInt("version").coerceAtLeast(1)
        val source = root.getJSONArray("keywords")
        require(source.length() in 1..10_000) { "Keyword pack must contain 1-10,000 entries" }
        val items = buildList {
            for (index in 0 until source.length()) {
                val item = source.getJSONObject(index)
                val value = SearchClassifier.normalize(item.getString("value")).take(100)
                if (value.length >= 3) add(
                    KeywordEntity(
                        normalized = value,
                        category = item.optString("category", "explicit").take(40),
                        language = item.optString("language", "und").take(12),
                        fuzzy = item.optBoolean("fuzzy", true),
                        packVersion = version
                    )
                )
            }
        }
        val dao = GuardianDatabase.get(context).guardianDao()
        dao.insertKeywords(items)
        SearchClassifier.updateLocalDatabase(dao.enabledKeywords())
        return items.size
    }
}
