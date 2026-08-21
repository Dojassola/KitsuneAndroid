package com.kitsuneandroid

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

internal data class PerformanceMetric(
    val name: String,
    val durationMs: Long,
    val recordedAt: Long = System.currentTimeMillis()
)

internal data class AppError(
    val component: String,
    val message: String,
    val details: String,
    val recordedAt: Long = System.currentTimeMillis(),
    val fatal: Boolean = false
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
        AppErrors.initialize(context.applicationContext, appPreferences)
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

    fun diagnosticReport(context: Context): String {
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()

        return formatDiagnosticReport(
            appVersion = version,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            metrics = metrics,
            errors = AppErrors.errors
        )
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

internal object AppErrors {
    private const val KEY = "application_errors"
    private const val LIMIT = 20
    private const val DETAILS_LIMIT = 6_000
    private const val TAG = "KitsuneError"
    private const val LAST_EXIT_KEY = "last_recorded_process_exit"

    private var preferences: SharedPreferences? = null
    private var handlerInstalled = false
    var errors by mutableStateOf<List<AppError>>(emptyList())
        private set

    fun initialize(context: Context, preferences: SharedPreferences) {
        if (this.preferences != null) {
            return
        }

        this.preferences = preferences
        errors = load(preferences)
        installCrashHandler()
        try {
            recordPreviousProcessExit(context, preferences)
        } catch (failure: Exception) {
            record("diagnostics.previousExit", failure)
        }
    }

    fun installCrashHandler() {
        if (handlerInstalled) {
            return
        }

        val current = Thread.getDefaultUncaughtExceptionHandler()
        handlerInstalled = true
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching {
                record(
                    component = "fatal:${thread.name}",
                    failure = failure,
                    fatal = true,
                    synchronous = true
                )
            }
            if (current != null) {
                current.uncaughtException(thread, failure)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    @Synchronized
    fun record(
        component: String,
        failure: Throwable,
        fatal: Boolean = false,
        synchronous: Boolean = false
    ) {
        val safeMessage = sanitizeDiagnosticText(failure.message ?: failure.javaClass.simpleName)
        val safeDetails = sanitizeDiagnosticText(failure.stackTraceToString()).take(DETAILS_LIMIT)
        val error = AppError(
            component = component.take(80),
            message = safeMessage.take(500),
            details = safeDetails,
            fatal = fatal
        )
        Log.e(TAG, "${error.component}: ${error.message}", failure)
        errors = (listOf(error) + errors).take(LIMIT)
        save(synchronous)
    }

    @Synchronized
    fun clear() {
        errors = emptyList()
        preferences?.edit()?.remove(KEY)?.apply()
    }

    private fun load(preferences: SharedPreferences): List<AppError> {
        val stored = preferences.getString(KEY, "[]").orEmpty()
        return try {
            val array = JSONArray(stored)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                AppError(
                    component = item.getString("component"),
                    message = item.getString("message"),
                    details = item.optString("details"),
                    recordedAt = item.getLong("recordedAt"),
                    fatal = item.optBoolean("fatal")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(synchronous: Boolean) {
        val editor = preferences?.edit()?.putString(
            KEY,
            JSONArray().apply {
                errors.forEach { error ->
                    put(
                        JSONObject()
                            .put("component", error.component)
                            .put("message", error.message)
                            .put("details", error.details)
                            .put("recordedAt", error.recordedAt)
                            .put("fatal", error.fatal)
                    )
                }
            }.toString()
        ) ?: return

        if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    private fun recordPreviousProcessExit(
        context: Context,
        preferences: SharedPreferences
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        val lastRecorded = preferences.getLong(LAST_EXIT_KEY, 0)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val exit = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .firstOrNull { info ->
                info.timestamp > lastRecorded && isUnexpectedExit(info.reason)
            }
            ?: return
        val description = exit.description?.takeIf(String::isNotBlank)
        val message = buildString {
            append(processExitReason(exit.reason))
            if (description != null) {
                append(": ")
                append(description)
            }
        }
        record(
            component = "previous_process_exit",
            failure = IllegalStateException(message),
            fatal = true,
            synchronous = true
        )
        preferences.edit().putLong(LAST_EXIT_KEY, exit.timestamp).commit()
    }
}

private fun isUnexpectedExit(reason: Int): Boolean {
    return reason == ApplicationExitInfo.REASON_CRASH ||
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
        reason == ApplicationExitInfo.REASON_ANR
}

private fun processExitReason(reason: Int): String {
    return when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "Application not responding"
        else -> "Application crash"
    }
}

internal fun sanitizeDiagnosticText(value: String): String {
    return value
        .replace(Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE), "[magnet removed]")
        .replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "[url removed]")
}

internal fun formatDiagnosticReport(
    appVersion: String,
    androidVersion: String,
    apiLevel: Int,
    device: String,
    metrics: List<PerformanceMetric>,
    errors: List<AppError> = emptyList()
): String {
    return buildString {
        appendLine("Kitsune $appVersion")
        appendLine("Android $androidVersion (API $apiLevel)")
        appendLine("Device: $device")
        appendLine()
        appendLine("Recent performance:")

        if (metrics.isEmpty()) {
            appendLine("No measurements recorded.")
        } else {
            metrics.forEach { metric ->
                appendLine("${metric.name}: ${metric.durationMs} ms (${metric.recordedAt})")
            }
        }

        appendLine()
        appendLine("Recent errors:")
        if (errors.isEmpty()) {
            appendLine("No errors recorded.")
        } else {
            errors.forEach { error ->
                val severity = if (error.fatal) "fatal" else "handled"
                appendLine("[$severity] ${error.component}: ${error.message} (${error.recordedAt})")
                appendLine(error.details)
            }
        }
    }.trimEnd()
}
