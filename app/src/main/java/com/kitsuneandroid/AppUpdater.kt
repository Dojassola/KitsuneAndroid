package com.kitsuneandroid

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val version: String, val assetName: String, val downloadUrl: String)

object AppUpdater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/Dojassola/KitsuneAndroid/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun latest(context: Context): AppRelease? {
        val connection = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/${currentVersion(context)}")
        return try {
            if (connection.responseCode !in 200..299) throw IOException("GitHub HTTP ${connection.responseCode}")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.getString("tag_name")
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) && !it.getString("name").contains("unsigned", ignoreCase = true) }
                ?: return null
            val url = asset.getString("browser_download_url")
            require(Uri.parse(url).let { it.scheme == "https" && it.host == "github.com" }) { "Link de atualização inválido." }
            AppRelease(version, asset.getString("name"), url).takeIf { isNewerVersion(version, currentVersion(context)) }
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, release: AppRelease): Long {
        val safeName = release.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Atualização do Kitsune ${release.version}")
            .setDescription("Baixando APK assinado")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeName)
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    fun install(context: Context, downloadId: Long): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java)
        val completed = manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        if (!completed) return false
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return false
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun currentVersion(context: Context): String = context.packageManager
        .getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val left = candidate.removePrefix("v").split(Regex("[^0-9]+")).filter(String::isNotBlank).map { it.toLongOrNull() ?: 0 }
    val right = current.removePrefix("v").split(Regex("[^0-9]+")).filter(String::isNotBlank).map { it.toLongOrNull() ?: 0 }
    repeat(maxOf(left.size, right.size)) { index ->
        val comparison = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison > 0
    }
    return false
}
