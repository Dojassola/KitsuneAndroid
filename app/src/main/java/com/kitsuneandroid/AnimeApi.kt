package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

data class Anime(
    val id: Int,
    val malId: Int?,
    val title: String,
    val romajiTitle: String,
    val englishTitle: String?,
    val description: String,
    val cover: String,
    val banner: String?,
    val episodes: Int?,
    val score: Int?,
    val year: Int?,
    val season: String?,
    val format: String?,
    val status: String?,
    val genres: List<String>,
    val aliases: List<String> = emptyList(),
    val nextAiringEpisode: Int? = null
)

object AnimeApi {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val FIELDS = """
        id
        idMal
        title { romaji english native }
        synonyms
        description(asHtml: false)
        episodes
        averageScore
        seasonYear
        season
        format
        status
        nextAiringEpisode { episode }
        genres
        coverImage { extraLarge large }
        bannerImage
    """

    private val catalogQuery = """
        query (${ '$' }search: String) {
          Page(page: 1, perPage: 30) {
            media(search: ${ '$' }search, type: ANIME, sort: TRENDING_DESC) { $FIELDS }
          }
        }
    """.trimIndent()

    private val favoritesQuery = """
        query (${ '$' }ids: [Int]) {
          Page(page: 1, perPage: 50) {
            media(id_in: ${ '$' }ids, type: ANIME, sort: POPULARITY_DESC) { $FIELDS }
          }
        }
    """.trimIndent()

    private val relationsQuery = """
        query (${ '$' }id: Int) {
          Media(id: ${ '$' }id, type: ANIME) {
            relations { edges { relationType node { $FIELDS } } }
          }
        }
    """.trimIndent()

    fun catalog(search: String? = null): List<Anime> {
        val variables = JSONObject()
        search?.takeIf(String::isNotBlank)?.let { variables.put("search", it) }
        return request(catalogQuery, variables)
    }

    fun favorites(ids: Set<Int>): List<Anime> = if (ids.isEmpty()) emptyList() else
        request(favoritesQuery, JSONObject().put("ids", JSONArray(ids.toList())))

    fun seasons(anime: Anime): List<Anime> {
        val found = linkedMapOf(anime.id to anime)
        val pending = ArrayDeque<Int>().apply { add(anime.id) }
        val visited = mutableSetOf<Int>()
        while (pending.isNotEmpty() && visited.size < 12) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val edges = post(relationsQuery, JSONObject().put("id", id))
                .getJSONObject("Media").getJSONObject("relations").getJSONArray("edges")
            repeat(edges.length()) { index ->
                val edge = edges.getJSONObject(index)
                if (edge.getString("relationType") !in setOf("PREQUEL", "SEQUEL")) return@repeat
                val related = parseAnime(edge.getJSONObject("node"))
                if (related.format !in setOf("TV", "TV_SHORT", "ONA")) return@repeat
                if (found.putIfAbsent(related.id, related) == null) pending.add(related.id)
            }
        }
        return orderAnimeSeasons(found.values)
    }

    private fun request(query: String, variables: JSONObject): List<Anime> {
        val media = post(query, variables).getJSONObject("Page").getJSONArray("media")
        return List(media.length()) { parseAnime(media.getJSONObject(it)) }
    }

    private fun post(query: String, variables: JSONObject): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val body = JSONObject().put("query", query).put("variables", variables).toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val message = runCatching {
                    JSONObject(response).getJSONArray("errors").getJSONObject(0).getString("message")
                }.getOrDefault("Falha HTTP ${connection.responseCode}")
                throw IOException(message)
            }
            JSONObject(response).getJSONObject("data")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAnime(item: JSONObject): Anime {
        val titles = item.getJSONObject("title")
        val cover = item.getJSONObject("coverImage")
        return Anime(
            id = item.getInt("id"),
            malId = item.optInt("idMal").takeIf { it > 0 },
            title = titles.optString("english").takeIf { it.isNotBlank() && it != "null" }
                ?: titles.optString("romaji", "Sem título"),
            romajiTitle = titles.optString("romaji", "Sem título"),
            englishTitle = titles.optString("english").takeIf { it.isNotBlank() && it != "null" },
            description = cleanDescription(item.optString("description")),
            cover = cover.optString("extraLarge").ifBlank { cover.optString("large") },
            banner = item.optString("bannerImage").takeIf { it.isNotBlank() && it != "null" },
            episodes = item.optInt("episodes").takeIf { it > 0 },
            score = item.optInt("averageScore").takeIf { it > 0 },
            year = item.optInt("seasonYear").takeIf { it > 0 },
            season = item.optString("season").takeIf { it.isNotBlank() && it != "null" },
            format = item.optString("format").takeIf { it.isNotBlank() && it != "null" },
            status = item.optString("status").takeIf { it.isNotBlank() && it != "null" },
            genres = item.getJSONArray("genres").let { genres -> List(genres.length()) { genres.getString(it) } },
            aliases = buildList {
                titles.optString("native").takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                item.getJSONArray("synonyms").let { synonyms ->
                    repeat(synonyms.length()) { index -> synonyms.optString(index).takeIf(String::isNotBlank)?.let(::add) }
                }
            }.distinct(),
            nextAiringEpisode = item.optJSONObject("nextAiringEpisode")?.optInt("episode")?.takeIf { it > 0 }
        )
    }
}

internal fun orderAnimeSeasons(animes: Collection<Anime>): List<Anime> {
    val seasonOrder = mapOf("WINTER" to 0, "SPRING" to 1, "SUMMER" to 2, "FALL" to 3)
    return animes.distinctBy(Anime::id).sortedWith(
        compareBy<Anime>({ it.year ?: Int.MAX_VALUE }, { seasonOrder[it.season] ?: 4 }, Anime::id)
    )
}

fun cleanDescription(value: String): String = value
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .replace("&amp;", "&")
    .trim()
