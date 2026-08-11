package com.kitsuneandroid

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal object MalCatalogFallback {
    private val kitsuIdsByMalId = ConcurrentHashMap<Int, String>()

    fun malCatalog(search: String?): List<Anime> {
        val path = if (search.isNullOrBlank()) {
            "seasons/now?limit=25&sfw=true"
        } else {
            "anime?q=${URLEncoder.encode(search, StandardCharsets.UTF_8.name())}&limit=25&sfw=true&order_by=popularity&sort=asc"
        }
        val data = get("https://api.jikan.moe/v4/$path").getJSONArray("data")
        return List(data.length()) { parseMalAnime(data.getJSONObject(it)) }.distinctBy(Anime::malId)
    }

    fun kitsuCatalog(search: String?): List<Anime> {
        val path = if (search.isNullOrBlank()) {
            "anime?filter%5Bstatus%5D=current&sort=-userCount&page%5Blimit%5D=20"
        } else {
            "anime?filter%5Btext%5D=${URLEncoder.encode(search, StandardCharsets.UTF_8.name())}&page%5Blimit%5D=20"
        }
        val data = get("https://kitsu.io/api/edge/$path", "application/vnd.api+json").getJSONArray("data")
        return List(data.length()) { parseKitsuAnime(data.getJSONObject(it)) }
    }

    fun kitsuId(malId: Int): String? {
        kitsuIdsByMalId[malId]?.let { cached ->
            return cached
        }

        val path = "mappings?filter%5BexternalSite%5D=myanimelist%2Fanime&" +
            "filter%5BexternalId%5D=$malId&include=item"
        val kitsuId = parseKitsuMapping(get("https://kitsu.io/api/edge/$path")) ?: return null
        kitsuIdsByMalId[malId] = kitsuId
        return kitsuId
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
            if (code !in 200..299) throw IOException("MyAnimeList/Jikan HTTP $code")
            if (response.length > 2_000_000) throw IOException("A resposta do provedor é grande demais.")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseKitsuMapping(payload: JSONObject): String? {
    val mappings = payload.optJSONArray("data") ?: return null
    for (index in 0 until mappings.length()) {
        val item = mappings.optJSONObject(index)
            ?.optJSONObject("relationships")
            ?.optJSONObject("item")
            ?.optJSONObject("data")
            ?: continue
        if (item.optString("type") == "anime") {
            return item.stringOrNull("id")
        }
    }
    return null
}

internal fun parseKitsuAnime(item: JSONObject): Anime {
    val kitsuId = item.getString("id").toInt()
    val attributes = item.getJSONObject("attributes")
    val titles = attributes.optJSONObject("titles")
    val english = titles?.stringOrNull("en")
    val romaji = titles?.stringOrNull("en_jp")
        ?: attributes.optString("canonicalTitle", "Sem título")
    val cover = attributes.optJSONObject("posterImage")?.let { it.optString("large").ifBlank { it.optString("original") } }.orEmpty()
    val status = when (attributes.optString("status")) {
        "current" -> "RELEASING"
        "finished" -> "FINISHED"
        else -> "NOT_YET_RELEASED"
    }
    return Anime(
        id = -1_000_000_000 - kitsuId,
        malId = null,
        title = english ?: romaji,
        romajiTitle = romaji,
        englishTitle = english,
        description = attributes.stringOrNull("synopsis")?.let(::cleanDescription).orEmpty(),
        cover = cover,
        banner = attributes.optJSONObject("coverImage")?.stringOrNull("large"),
        episodes = attributes.optInt("episodeCount").takeIf { it > 0 },
        score = attributes.optString("averageRating").toDoubleOrNull()?.toInt(),
        year = attributes.optString("startDate").take(4).toIntOrNull(),
        season = null,
        format = attributes.stringOrNull("subtype")?.uppercase(),
        status = status,
        genres = emptyList(),
        aliases = buildList {
            listOf("en", "en_jp", "ja_jp").mapNotNullTo(this) { key -> titles?.stringOrNull(key) }
            addAll(attributes.optJSONArray("abbreviatedTitles").strings())
        }.filter { it != english && it != romaji }.distinct()
    )
}

internal fun parseMalAnime(item: JSONObject): Anime {
    val malId = item.getInt("mal_id")
    val titles = item.optJSONArray("titles")
    fun title(type: String): String? = (0 until (titles?.length() ?: 0)).firstNotNullOfOrNull { index ->
        titles?.getJSONObject(index)
            ?.takeIf { it.optString("type") == type }
            ?.stringOrNull("title")
    }
    val english = title("English") ?: item.stringOrNull("title_english")
    val romaji = title("Default") ?: item.optString("title").ifBlank { english ?: "Sem título" }
    val images = item.optJSONObject("images")
    val cover = images?.optJSONObject("webp")?.optString("large_image_url").orEmpty().ifBlank {
        images?.optJSONObject("jpg")?.optString("large_image_url").orEmpty()
    }
    val format = item.stringOrNull("type")
        ?.uppercase()
        ?.replace(Regex("[^A-Z0-9]+"), "_")
        ?.trim('_')
        .trimmedOrNull()
    return Anime(
        id = -malId,
        malId = malId,
        title = english ?: romaji,
        romajiTitle = romaji,
        englishTitle = english,
        description = item.stringOrNull("synopsis")?.let(::cleanDescription).orEmpty(),
        cover = cover,
        banner = null,
        episodes = item.optInt("episodes").takeIf { it > 0 },
        score = item.optDouble("score").takeIf { it > 0 }?.times(10)?.toInt(),
        year = item.optInt("year").takeIf { it > 0 },
        season = item.stringOrNull("season")?.uppercase(),
        format = format,
        status = when {
            item.optBoolean("airing") -> "RELEASING"
            item.optString("status") == "Finished Airing" -> "FINISHED"
            else -> "NOT_YET_RELEASED"
        },
        genres = item.optJSONArray("genres")?.let { array ->
            List(array.length()) { array.getJSONObject(it).optString("name") }.filter(String::isNotBlank)
        }.orEmpty(),
        aliases = buildList {
            repeat(titles?.length() ?: 0) { index ->
                titles?.getJSONObject(index)?.stringOrNull("title")?.let(::add)
            }
            addAll(item.optJSONArray("title_synonyms").strings())
        }.filter { it != english && it != romaji }.distinct()
    )
}
