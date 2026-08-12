package com.kitsuneandroid

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

internal data class PerformanceMetric(
    val name: String,
    val durationMs: Long,
    val recordedAt: Long = System.currentTimeMillis()
)

internal object AppPerformance {
    private const val PREFERENCES = "kitsune"
    private const val KEY = "performance_metrics"
    private const val LIMIT = 12

    private var preferences: SharedPreferences? = null
    val metrics = mutableStateListOf<PerformanceMetric>()

    fun initialize(context: Context) {
        if (preferences != null) {
            return
        }

        val appPreferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE
        )
        preferences = appPreferences
        metrics.addAll(load(appPreferences))
    }

    fun start(): Long {
        return SystemClock.elapsedRealtime()
    }

    fun record(name: String, startedAt: Long) {
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        metrics.add(0, PerformanceMetric(name, duration))

        while (metrics.size > LIMIT) {
            metrics.removeAt(metrics.lastIndex)
        }
        save()
    }

    private fun load(preferences: SharedPreferences): List<PerformanceMetric> {
        val stored = preferences
            .getString(KEY, "[]")
            .orEmpty()

        return try {
            val array = JSONArray(stored)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PerformanceMetric(
                    name = item.getString("name"),
                    durationMs = item.getLong("durationMs"),
                    recordedAt = item.getLong("recordedAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save() {
        val preferences = preferences ?: return
        val array = JSONArray()

        metrics.forEach { metric ->
            array.put(
                JSONObject()
                    .put("name", metric.name)
                    .put("durationMs", metric.durationMs)
                    .put("recordedAt", metric.recordedAt)
            )
        }
        preferences.edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
