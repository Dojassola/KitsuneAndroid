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
import java.util.concurrent.Executors

private const val PREFERENCES = "kitsune"
private const val HISTORY = "video_history"
private const val LEGACY_COMPLETED_EPISODES = "completed_episodes"
private const val AUTOMATICALLY_COMPLETED_EPISODES = "automatically_completed_episodes"

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
    val episode: Int? = null,
    val animeId: Int? = null
)

object VideoHistory {
    val items = mutableStateListOf<WatchedVideo>()
    private val automaticallyCompletedEpisodes = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val persistence = Executors.newSingleThreadExecutor()

    fun load(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val json = preferences.getString(HISTORY, "[]") ?: "[]"
        val completedEpisodes = preferences
            .getStringSet(AUTOMATICALLY_COMPLETED_EPISODES, emptySet())
            .orEmpty()
        preferences.edit().remove(LEGACY_COMPLETED_EPISODES).apply()
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
                    episode = item.optInt("episode").takeIf { !item.isNull("episode") },
                    animeId = item.optInt("animeId").takeIf { !item.isNull("animeId") }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        main.post {
            items.clear()
            items.addAll(parsed)
            automaticallyCompletedEpisodes.clear()
            automaticallyCompletedEpisodes.addAll(completedEpisodes)
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
        directArtworkUrl: String? = null,
        directAnimeId: Int? = null,
        directAnimeTitle: String? = null,
        directEpisode: Int? = null
    ) {
        val uriText = uri.toString()
        val animeId = download?.animeId ?: directAnimeId
        val episode = download?.episode ?: directEpisode
        val previous = items.firstOrNull { item ->
            historyMatchesEpisode(item, animeId, episode, uriText)
        }
        val position = positionMs.coerceAtLeast(0)
        val duration = durationMs.coerceAtLeast(0)
        val entry = WatchedVideo(
            uri = uriText,
            title = directTitle ?: download?.name ?: previous?.title ?: displayName(context, uri),
            positionMs = position,
            watchedAt = System.currentTimeMillis(),
            durationMs = duration,
            completed = ended ||
                previous?.completed == true ||
                isWatched(position, duration),
            animeTitle = download?.animeTitle ?: previous?.animeTitle ?: directAnimeTitle ?: directTitle,
            animeCoverUrl = download?.animeCoverUrl ?: previous?.animeCoverUrl ?: directArtworkUrl,
            animeCoverPath = download?.animeCoverPath ?: previous?.animeCoverPath,
            episode = episode ?: previous?.episode,
            animeId = animeId ?: previous?.animeId
        )
        items.removeAll { item ->
            historyMatchesEpisode(item, entry.animeId, entry.episode, entry.uri)
        }
        items.add(0, entry)
        persist(context)
        if (entry.completed) {
            episodeStatusKey(entry.animeId, entry.episode, entry.uri)?.let { key ->
                if (key !in automaticallyCompletedEpisodes) {
                    automaticallyCompletedEpisodes.add(key)
                    persistCompletedEpisodes(context)
                }
            }
        }
    }

    fun isEpisodeCompleted(
        history: WatchedVideo?,
        animeId: Int?,
        episode: Int?,
        uri: String? = null
    ): Boolean {
        if (history?.completed == true) {
            return true
        }
        val key = episodeStatusKey(animeId, episode, uri) ?: return false
        return key in automaticallyCompletedEpisodes
    }

    fun remove(context: Context, uri: String) {
        items.removeAll { it.uri == uri }
        persist(context)
    }

    fun clear(context: Context) {
        items.clear()
        persist(context)
    }

    internal fun flushForBackup() {
        persistence.submit { Unit }.get()
    }

    private fun persist(context: Context) {
        val history = items.toList()
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE
        )
        persistence.execute {
            val array = JSONArray()
            history.forEach { item ->
                array.put(
                    JSONObject()
                        .put("uri", item.uri)
                        .put("title", item.title)
                        .put("positionMs", item.positionMs)
                        .put("watchedAt", item.watchedAt)
                        .put("durationMs", item.durationMs)
                        .put("completed", item.completed)
                        .put("animeTitle", item.animeTitle ?: JSONObject.NULL)
                        .put("animeCoverUrl", item.animeCoverUrl ?: JSONObject.NULL)
                        .put("animeCoverPath", item.animeCoverPath ?: JSONObject.NULL)
                        .put("episode", item.episode ?: JSONObject.NULL)
                        .put("animeId", item.animeId ?: JSONObject.NULL)
                )
            }
            preferences.edit()
                .putString(HISTORY, array.toString())
                .apply()
        }
    }

    private fun persistCompletedEpisodes(context: Context) {
        val completedEpisodes = automaticallyCompletedEpisodes.toSet()
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE
        )
        persistence.execute {
            preferences.edit()
                .putStringSet(AUTOMATICALLY_COMPLETED_EPISODES, completedEpisodes)
                .apply()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank {
                context.getString(R.string.video)
            }
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
        return queriedName ?: uri.lastPathSegment ?: context.getString(R.string.video)
    }
}

private fun episodeStatusKey(animeId: Int?, episode: Int?, uri: String?): String? {
    if (animeId != null && episode != null) {
        return "anime:$animeId:episode:$episode"
    }
    return uri?.takeIf(String::isNotBlank)?.let { value -> "uri:$value" }
}

internal fun historyMatchesEpisode(
    item: WatchedVideo,
    animeId: Int?,
    episode: Int?,
    uri: String?
): Boolean {
    if (uri != null && item.uri == uri) {
        return true
    }
    return animeId != null &&
        episode != null &&
        item.animeId == animeId &&
        item.episode == episode
}

internal fun isWatched(positionMs: Long, durationMs: Long): Boolean {
    return durationMs > 0 && positionMs >= durationMs * 9 / 10
}

internal fun historyForEpisode(
    history: List<WatchedVideo>,
    animeId: Int,
    episode: Int,
    offlineUri: String? = null
): WatchedVideo? {
    return history.firstOrNull { item ->
        item.animeId == animeId && item.episode == episode
    } ?: offlineUri?.let { uri ->
        history.firstOrNull { item -> item.uri == uri }
    }
}
