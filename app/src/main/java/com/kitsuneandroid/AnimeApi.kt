package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    val format: String?,
    val status: String?,
    val genres: List<String>
)

object AnimeApi {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val FIELDS = """
        id
        idMal
        title { romaji english }
        description(asHtml: false)
        episodes
        averageScore
        seasonYear
        format
        status
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

    fun catalog(search: String? = null): List<Anime> {
        val variables = JSONObject()
        search?.takeIf(String::isNotBlank)?.let { variables.put("search", it) }
        return request(catalogQuery, variables)
    }

    fun favorites(ids: Set<Int>): List<Anime> = if (ids.isEmpty()) emptyList() else
        request(favoritesQuery, JSONObject().put("ids", JSONArray(ids.toList())))

    private fun request(query: String, variables: JSONObject): List<Anime> {
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
            parse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): List<Anime> {
        val media = JSONObject(json).getJSONObject("data").getJSONObject("Page").getJSONArray("media")
        return buildList(media.length()) {
            for (index in 0 until media.length()) {
                val item = media.getJSONObject(index)
                val titles = item.getJSONObject("title")
                val cover = item.getJSONObject("coverImage")
                add(
                    Anime(
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
                        format = item.optString("format").takeIf { it.isNotBlank() && it != "null" },
                        status = item.optString("status").takeIf { it.isNotBlank() && it != "null" },
                        genres = item.getJSONArray("genres").let { genres ->
                            List(genres.length()) { genres.getString(it) }
                        }
                    )
                )
            }
        }
    }
}

fun cleanDescription(value: String): String = value
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .replace("&amp;", "&")
    .trim()
