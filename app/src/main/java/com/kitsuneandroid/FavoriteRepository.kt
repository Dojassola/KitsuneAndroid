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
        return decodeAnimeList(json).filter { it.id in wanted }
    }

    fun set(context: Context, anime: Anime, favorite: Boolean) {
        val ids = ids(context).toMutableSet()
        val items = items(context).associateBy(Anime::id).toMutableMap()
        if (favorite) {
            ids += anime.id
            items[anime.id] = anime
        } else {
            ids -= anime.id
            items.remove(anime.id)
        }
        save(context, ids, items.values)
    }

    fun refresh(context: Context, remote: List<Anime>) {
        val ids = ids(context)
        val cached = items(context).associateBy(Anime::id).toMutableMap()
        remote.filter { it.id in ids }.forEach { cached[it.id] = it }
        save(context, ids, cached.values)
    }

    private fun save(context: Context, ids: Set<Int>, anime: Collection<Anime>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(FAVORITE_IDS, ids.map(Int::toString).toSet())
            .putString(FAVORITE_CACHE, encodeAnimeList(anime))
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
            .put("nextAiringEpisode", anime.nextAiringEpisode ?: JSONObject.NULL))
    }
}.toString()

internal fun decodeAnimeList(value: String): List<Anime> = runCatching {
    val array = JSONArray(value)
    List(array.length()) { index ->
        val item = array.getJSONObject(index)
        Anime(
            id = item.getInt("id"), malId = item.optInt("malId").takeIf { it > 0 },
            title = item.getString("title"), romajiTitle = item.getString("romajiTitle"),
            englishTitle = item.textOrNull("englishTitle"), description = item.optString("description"),
            cover = item.optString("cover"), banner = item.textOrNull("banner"),
            episodes = item.optInt("episodes").takeIf { it > 0 }, score = item.optInt("score").takeIf { it > 0 },
            year = item.optInt("year").takeIf { it > 0 }, season = item.textOrNull("season"),
            format = item.textOrNull("format"), status = item.textOrNull("status"),
            genres = item.optJSONArray("genres").strings(), aliases = item.optJSONArray("aliases").strings(),
            nextAiringEpisode = item.optInt("nextAiringEpisode").takeIf { it > 0 }
        )
    }
}.getOrDefault(emptyList())

private fun JSONObject.textOrNull(name: String) = optString(name).takeIf { it.isNotBlank() && it != "null" }
private fun JSONArray?.strings(): List<String> = this?.let { array -> List(array.length()) { array.optString(it) }.filter(String::isNotBlank) }.orEmpty()
