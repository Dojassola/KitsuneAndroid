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

data class WatchedVideo(
    val uri: String,
    val title: String,
    val positionMs: Long,
    val watchedAt: Long,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val animeTitle: String? = null,
    val animeCoverUrl: String? = null,
    val animeCoverPath: String? = null,
    val episode: Int? = null
)

object VideoHistory {
    val items = mutableStateListOf<WatchedVideo>()
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context) {
        val json = context.getSharedPreferences("kitsune", Context.MODE_PRIVATE).getString("video_history", "[]") ?: "[]"
        val parsed = try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val position = item.getLong("positionMs")
                val duration = item.optLong("durationMs")
                WatchedVideo(
                    uri = item.getString("uri"),
                    title = item.getString("title"),
                    positionMs = position,
                    watchedAt = item.getLong("watchedAt"),
                    durationMs = duration,
                    completed = item.optBoolean("completed", isWatched(position, duration)),
                    animeTitle = item.stringOrNull("animeTitle"),
                    animeCoverUrl = item.stringOrNull("animeCoverUrl"),
                    animeCoverPath = item.stringOrNull("animeCoverPath"),
                    episode = item.optInt("episode").takeIf { !item.isNull("episode") }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        main.post {
            items.clear()
            items.addAll(parsed)
        }
    }

    fun record(
        context: Context,
        uri: Uri,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean = false,
        download: TorrentDownload? = null,
        directTitle: String? = null,
        directArtworkUrl: String? = null
    ) {
        val previous = items.firstOrNull { it.uri == uri.toString() }
        val position = positionMs.coerceAtLeast(0)
        val duration = durationMs.coerceAtLeast(0)
        val entry = WatchedVideo(
            uri = uri.toString(),
            title = directTitle ?: download?.name ?: previous?.title ?: displayName(context, uri),
            positionMs = position,
            watchedAt = System.currentTimeMillis(),
            durationMs = duration,
            completed = ended || previous?.completed == true || isWatched(position, duration),
            animeTitle = download?.animeTitle ?: previous?.animeTitle ?: directTitle,
            animeCoverUrl = download?.animeCoverUrl ?: previous?.animeCoverUrl ?: directArtworkUrl,
            animeCoverPath = download?.animeCoverPath ?: previous?.animeCoverPath,
            episode = download?.episode ?: previous?.episode
        )
        items.removeAll { it.uri == entry.uri }
        items.add(0, entry)
        persist(context)
    }

    fun remove(context: Context, uri: String) {
        items.removeAll { it.uri == uri }
        persist(context)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("title", it.title)
                    .put("positionMs", it.positionMs)
                    .put("watchedAt", it.watchedAt)
                    .put("durationMs", it.durationMs)
                    .put("completed", it.completed)
                    .put("animeTitle", it.animeTitle ?: JSONObject.NULL)
                    .put("animeCoverUrl", it.animeCoverUrl ?: JSONObject.NULL)
                    .put("animeCoverPath", it.animeCoverPath ?: JSONObject.NULL)
                    .put("episode", it.episode ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences("kitsune", Context.MODE_PRIVATE).edit().putString("video_history", array.toString()).apply()
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank { "Vídeo" }
        }

        val queriedName = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
        return queriedName ?: uri.lastPathSegment ?: "Vídeo"
    }
}

internal fun isWatched(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs * 9 / 10
