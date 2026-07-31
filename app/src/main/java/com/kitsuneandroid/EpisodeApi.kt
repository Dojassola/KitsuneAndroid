package com.kitsuneandroid

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Episode(
    val number: Int,
    val title: String?,
    val airedAt: String?,
    val durationSeconds: Int?,
    val filler: Boolean,
    val recap: Boolean
)

object EpisodeApi {
    fun list(anime: Anime): List<Episode> {
        val malId = anime.malId ?: return fallback(anime)
        val episodes = mutableListOf<Episode>()
        var page = 1
        do {
            val payload = get("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
            val data = payload.getJSONArray("data")
            repeat(data.length()) { index ->
                val item = data.getJSONObject(index)
                episodes += Episode(
                    number = item.getInt("mal_id"),
                    title = item.optString("title").takeIf { it.isNotBlank() && it != "null" },
                    airedAt = item.optString("aired").takeIf { it.isNotBlank() && it != "null" },
                    durationSeconds = item.optInt("duration").takeIf { it > 0 },
                    filler = item.optBoolean("filler"),
                    recap = item.optBoolean("recap")
                )
            }
            val pagination = payload.getJSONObject("pagination")
            if (!pagination.getBoolean("has_next_page")) break
            page++
            if (page > 25) throw IOException("A lista de episódios excede o limite suportado.")
            Thread.sleep(350)
        } while (true)
        return episodes.ifEmpty { fallback(anime) }
    }

    private fun fallback(anime: Anime): List<Episode> = List(anime.episodes ?: 0) {
        Episode(it + 1, null, null, null, false, false)
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
        return try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) throw IOException("Jikan HTTP ${connection.responseCode}")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
