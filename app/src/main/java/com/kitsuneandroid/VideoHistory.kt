package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WatchedVideo(val uri: String, val title: String, val positionMs: Long, val watchedAt: Long)

object VideoHistory {
    val items = mutableStateListOf<WatchedVideo>()
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context) {
        val json = context.getSharedPreferences("kitsune", Context.MODE_PRIVATE).getString("video_history", "[]") ?: "[]"
        val parsed = runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                WatchedVideo(item.getString("uri"), item.getString("title"), item.getLong("positionMs"), item.getLong("watchedAt"))
            }
        }.getOrDefault(emptyList())
        main.post { items.clear(); items.addAll(parsed) }
    }

    fun record(context: Context, uri: Uri, positionMs: Long) {
        val entry = WatchedVideo(uri.toString(), displayName(context, uri), positionMs.coerceAtLeast(0), System.currentTimeMillis())
        items.removeAll { it.uri == entry.uri }
        items.add(0, entry)
        while (items.size > 50) items.removeAt(items.lastIndex)
        persist(context)
    }

    fun remove(context: Context, uri: String) {
        items.removeAll { it.uri == uri }
        persist(context)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("uri", it.uri).put("title", it.title).put("positionMs", it.positionMs).put("watchedAt", it.watchedAt)) }
        context.getSharedPreferences("kitsune", Context.MODE_PRIVATE).edit().putString("video_history", array.toString()).apply()
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name.ifBlank { "Vídeo" }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Vídeo"
    }
}
