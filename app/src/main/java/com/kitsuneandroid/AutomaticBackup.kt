package com.kitsuneandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object AutomaticBackup {
    private const val PREFS = "kitsune_automatic_backup"
    private const val URI = "uri"
    private const val PASSWORD = "password"
    private const val LAST_SUCCESS = "last_success"
    private const val ERROR = "error"
    private const val INTERVAL_MS = 6 * 60 * 60 * 1_000L
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun enabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(URI, null) != null &&
            SecureLocalStore.load(context, PREFS, PASSWORD) != null
    }

    fun lastSuccessAt(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_SUCCESS, 0)
    }

    fun error(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ERROR, null)
    }

    fun configure(context: Context, uri: Uri, password: CharArray) {
        require(password.size >= 6) { context.getString(R.string.backup_password_too_short) }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers persist access as part of CreateDocument without exposing this API.
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URI, uri.toString())
            .remove(ERROR)
            .apply()
        SecureLocalStore.save(context, PREFS, PASSWORD, String(password))
        runIfNeeded(context, force = true)
    }

    fun disable(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(URI, null)?.let { storedUri ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(storedUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                Unit
            }
        }
        preferences.edit().clear().apply()
        SecureLocalStore.remove(context, PREFS, PASSWORD)
    }

    fun runIfNeeded(context: Context, force: Boolean = false) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = preferences.getString(URI, null)?.let(Uri::parse) ?: return
        val password = SecureLocalStore.load(context, PREFS, PASSWORD)?.toCharArray() ?: return
        if (!force && System.currentTimeMillis() - lastSuccessAt(context) < INTERVAL_MS) {
            password.fill('\u0000')
            return
        }
        if (!running.compareAndSet(false, true)) {
            password.fill('\u0000')
            return
        }
        worker.execute {
            try {
                UserDataBackup.export(context.applicationContext, uri, password)
                preferences.edit()
                    .putLong(LAST_SUCCESS, System.currentTimeMillis())
                    .remove(ERROR)
                    .apply()
            } catch (error: Exception) {
                preferences.edit().putString(ERROR, error.message?.take(300)).apply()
                AppErrors.record("backup.automatic", error)
            } finally {
                password.fill('\u0000')
                running.set(false)
            }
        }
    }
}
