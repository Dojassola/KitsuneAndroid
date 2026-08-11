package com.kitsuneandroid

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub HTTP ${connection.responseCode}")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.getString("tag_name")
            val assets = release.getJSONArray("assets")
            val installableAssets = (0 until assets.length())
                .asSequence()
                .map(assets::getJSONObject)
                .filter(JSONObject::isInstallableApk)
                .toList()
            val assetName = preferredApkAsset(
                assetNames = installableAssets.map { asset -> asset.getString("name") },
                supportedAbis = Build.SUPPORTED_ABIS.toList()
            ) ?: return null
            val asset = installableAssets.firstOrNull { candidate ->
                candidate.getString("name") == assetName
            }
                ?: return null
            val url = asset.getString("browser_download_url")
            require(isGitHubDownload(url)) { "Link de atualização inválido." }
            if (!isNewerVersion(version, currentVersion(context))) return null
            AppRelease(version, asset.getString("name"), url)
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
            if (!cursor.moveToFirst()) return@use false
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL
        }
        if (!completed) return false
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return false
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(installIntent)
        return true
    }

    private fun currentVersion(context: Context): String = context.packageManager
        .getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val left = versionParts(candidate)
    val right = versionParts(current)
    repeat(maxOf(left.size, right.size)) { index ->
        val comparison = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison > 0
    }
    return false
}

private fun versionParts(value: String): List<Long> {
    return VERSION_NUMBER.findAll(value.removePrefix("v"))
        .map { match -> match.value.toLongOrNull() ?: 0 }
        .toList()
}

private fun JSONObject.isInstallableApk(): Boolean {
    val name = getString("name")
    return name.endsWith(".apk", ignoreCase = true) &&
        !name.contains("unsigned", ignoreCase = true)
}

internal fun preferredApkAsset(assetNames: List<String>, supportedAbis: List<String>): String? {
    val installable = assetNames.filter { name ->
        name.endsWith(".apk", ignoreCase = true) &&
            !name.contains("unsigned", ignoreCase = true)
    }
    for (abi in supportedAbis) {
        installable.firstOrNull { name ->
            name.endsWith("-$abi.apk", ignoreCase = true)
        }?.let { match -> return match }
    }

    return installable.firstOrNull { name ->
        SUPPORTED_ABIS.none { abi -> name.endsWith("-$abi.apk", ignoreCase = true) }
    }
}

private fun isGitHubDownload(value: String): Boolean {
    val uri = Uri.parse(value)
    return uri.scheme == "https" && uri.host == "github.com"
}

private val VERSION_NUMBER = Regex("\\d+")
private val SUPPORTED_ABIS = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
