package com.kitsuneandroid

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class Episode(
    val number: Int,
    val title: String?,
    val japaneseTitle: String?,
    val romanjiTitle: String?,
    val airedAt: String?,
    val durationSeconds: Int?,
    val filler: Boolean,
    val recap: Boolean,
    val synopsis: String?,
    val thumbnail: String?
)

object EpisodeApi {
    fun list(anime: Anime): List<Episode> = completeEpisodeList(
        anime,
        runCatching { jikanList(anime) }.getOrElse { fallback(anime) }
    )

    private fun jikanList(anime: Anime): List<Episode> {
        val malId = anime.malId ?: return fallback(anime)
        val episodes = mutableListOf<Episode>()
        var page = 1
        do {
            val payload = get("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
            val data = payload.getJSONArray("data")
            repeat(data.length()) { index ->
                episodes += parse(data.getJSONObject(index))
            }
            val pagination = payload.getJSONObject("pagination")
            if (!pagination.getBoolean("has_next_page")) break
            page++
            if (page > 25) throw IOException("A lista de episódios excede o limite suportado.")
            Thread.sleep(350)
        } while (true)
        return episodes.ifEmpty { fallback(anime) }
    }

    fun details(anime: Anime, episode: Int): Episode {
        val jikan = runCatching {
            val malId = requireNotNull(anime.malId)
            parse(get("https://api.jikan.moe/v4/anime/$malId/episodes/$episode").getJSONObject("data"))
        }.getOrNull()
        if (!jikan?.synopsis.isNullOrBlank()) return jikan
        return runCatching { kitsuDetails(anime, episode) }.getOrElse { jikan ?: throw it }
    }

    private fun fallback(anime: Anime): List<Episode> = List(anime.episodes ?: 0) {
        fallbackEpisode(it + 1)
    }

    private fun fallbackEpisode(number: Int) = Episode(number, null, null, null, null, null, false, false, null, null)

    private fun parse(item: JSONObject) = Episode(
        number = item.getInt("mal_id"),
        title = item.text("title"),
        japaneseTitle = item.text("title_japanese"),
        romanjiTitle = item.text("title_romanji"),
        airedAt = item.text("aired"),
        durationSeconds = item.optInt("duration").takeIf { it > 0 },
        filler = item.optBoolean("filler"),
        recap = item.optBoolean("recap"),
        synopsis = item.text("synopsis")?.let(::cleanDescription),
        thumbnail = null
    )

    private fun kitsuDetails(anime: Anime, episode: Int): Episode {
        val query = URLEncoder.encode(anime.romajiTitle, StandardCharsets.UTF_8.name())
        val media = get(
            "https://kitsu.io/api/edge/anime?filter%5Btext%5D=$query&page%5Blimit%5D=1",
            "application/vnd.api+json"
        ).getJSONArray("data").getJSONObject(0)
        val item = get(
            "https://kitsu.io/api/edge/anime/${media.getString("id")}/episodes?filter%5Bnumber%5D=$episode&page%5Blimit%5D=1",
            "application/vnd.api+json"
        ).getJSONArray("data").getJSONObject(0).getJSONObject("attributes")
        return Episode(
            number = item.optInt("number", episode),
            title = item.text("canonicalTitle"),
            japaneseTitle = item.optJSONObject("titles")?.text("ja_jp"),
            romanjiTitle = item.optJSONObject("titles")?.text("en_jp"),
            airedAt = item.text("airdate"),
            durationSeconds = item.optInt("length").takeIf { it > 0 }?.times(60),
            filler = false,
            recap = false,
            synopsis = item.text("synopsis")?.let(::cleanDescription),
            thumbnail = item.optJSONObject("thumbnail")?.text("original")
        )
    }

    private fun get(url: String, accept: String = "application/json"): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
        return try {
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun completeEpisodeList(anime: Anime, listed: List<Episode>): List<Episode> {
    val airedCount = if (anime.status == "RELEASING") {
        anime.nextAiringEpisode?.minus(1)?.coerceAtLeast(0) ?: 0
    } else anime.episodes ?: 0
    val lastEpisode = maxOf(airedCount, listed.maxOfOrNull(Episode::number) ?: 0)
    if (lastEpisode == 0) return listed
    val known = listed.associateBy(Episode::number)
    return (1..lastEpisode).map { number ->
        known[number] ?: Episode(number, null, null, null, null, null, false, false, null, null)
    }
}

private fun JSONObject.text(name: String): String? = optString(name).takeIf { it.isNotBlank() && it != "null" }
