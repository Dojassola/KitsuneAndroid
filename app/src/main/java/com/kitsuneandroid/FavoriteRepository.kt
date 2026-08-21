package com.kitsuneandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFERENCES = "kitsune"
private const val FAVORITE_IDS = "favorites"
private const val FAVORITE_CACHE = "favorite_anime_cache"

object FavoriteRepository {
    fun ids(context: Context): Set<Int> = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getStringSet(FAVORITE_IDS, emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()

    fun items(context: Context): List<Anime> {
        val wanted = ids(context)
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(FAVORITE_CACHE, "[]") ?: "[]"
        return decodeAnimeList(json).filter { it.id in wanted }.asReversed()
    }

    fun set(context: Context, anime: Anime, favorite: Boolean) {
        val ids = ids(context).toMutableSet()
        val items = items(context).filterNot { it.id == anime.id }.toMutableList()
        if (favorite) {
            ids += anime.id
            items.add(0, anime)
        } else {
            ids -= anime.id
        }
        save(context, ids, items)
    }

    fun refresh(context: Context, remote: List<Anime>) {
        val ids = ids(context)
        val cached = items(context).associateBy(Anime::id).toMutableMap()
        remote.filter { it.id in ids }.forEach { cached[it.id] = it }
        save(context, ids, cached.values)
    }

    fun addAll(context: Context, anime: Collection<Anime>) {
        val merged = (anime + items(context)).distinctBy { item -> item.malId ?: item.id }
        save(context, ids(context) + anime.map(Anime::id), merged)
    }

    private fun save(context: Context, ids: Set<Int>, anime: Collection<Anime>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(FAVORITE_IDS, ids.map(Int::toString).toSet())
            .putString(FAVORITE_CACHE, encodeAnimeList(anime.toList().asReversed()))
            .apply()
    }
}

internal fun encodeAnimeList(items: Collection<Anime>): String = JSONArray().apply {
    items.forEach { anime ->
        put(JSONObject()
            .put("id", anime.id).put("malId", anime.malId ?: JSONObject.NULL)
            .put("title", anime.title).put("romajiTitle", anime.romajiTitle)
            .put("englishTitle", anime.englishTitle ?: JSONObject.NULL).put("description", anime.description)
            .put("cover", anime.cover).put("banner", anime.banner ?: JSONObject.NULL)
            .put("episodes", anime.episodes ?: JSONObject.NULL).put("score", anime.score ?: JSONObject.NULL)
            .put("year", anime.year ?: JSONObject.NULL).put("season", anime.season ?: JSONObject.NULL)
            .put("format", anime.format ?: JSONObject.NULL).put("status", anime.status ?: JSONObject.NULL)
            .put("genres", JSONArray(anime.genres)).put("aliases", JSONArray(anime.aliases))
            .put("nextAiringEpisode", anime.nextAiringEpisode ?: JSONObject.NULL)
            .put("nextAiringAt", anime.nextAiringAt ?: JSONObject.NULL)
            .put("seasonNumber", anime.seasonNumber ?: JSONObject.NULL)
            .put("remoteMediaId", anime.remoteMediaId ?: JSONObject.NULL)
            .put("remoteMediaType", anime.remoteMediaType ?: JSONObject.NULL)
            .put("remoteManifestUrl", anime.remoteManifestUrl ?: JSONObject.NULL)
            .put("remoteProtocol", anime.remoteProtocol?.name ?: JSONObject.NULL))
    }
}.toString()

internal fun decodeAnimeList(value: String): List<Anime> {
    return try {
        val array = JSONArray(value)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Anime(
                id = item.getInt("id"), malId = item.optInt("malId").takeIf { it > 0 },
                title = item.getString("title"), romajiTitle = item.getString("romajiTitle"),
                englishTitle = item.stringOrNull("englishTitle"), description = item.optString("description"),
                cover = item.optString("cover"), banner = item.stringOrNull("banner"),
                episodes = item.optInt("episodes").takeIf { it > 0 }, score = item.optInt("score").takeIf { it > 0 },
                year = item.optInt("year").takeIf { it > 0 }, season = item.stringOrNull("season"),
                format = item.stringOrNull("format"), status = item.stringOrNull("status"),
                genres = item.optJSONArray("genres").strings(), aliases = item.optJSONArray("aliases").strings(),
                nextAiringEpisode = item.optInt("nextAiringEpisode").takeIf { it > 0 },
                nextAiringAt = item.optLong("nextAiringAt").takeIf { it > 0 },
                seasonNumber = item.optInt("seasonNumber").takeIf { it > 0 },
                remoteMediaId = item.stringOrNull("remoteMediaId")
                    ?: item.stringOrNull("stremioId"),
                remoteMediaType = item.stringOrNull("remoteMediaType")
                    ?: item.stringOrNull("stremioType"),
                remoteManifestUrl = item.stringOrNull("remoteManifestUrl")
                    ?: item.stringOrNull("stremioManifestUrl"),
                remoteProtocol = item.stringOrNull("remoteProtocol")
                    ?.let { value ->
                        runCatching { RemoteProviderProtocol.valueOf(value) }.getOrNull()
                    }
                    ?: item.stringOrNull("stremioManifestUrl")
                        ?.let { RemoteProviderProtocol.STREMIO }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
