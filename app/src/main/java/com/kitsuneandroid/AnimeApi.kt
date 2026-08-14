package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    val nextAiringEpisode: Int? = null,
    val seasonNumber: Int? = null,
    val remoteMediaId: String? = null,
    val remoteMediaType: String? = null,
    val remoteManifestUrl: String? = null,
    val remoteProtocol: RemoteProviderProtocol? = null
)

data class AnimeSeasonRelation(val type: String, val anime: Anime)

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
        query (${ '$' }search: String, ${ '$' }page: Int, ${ '$' }format: MediaFormat, ${ '$' }formatNot: MediaFormat) {
          Page(page: ${ '$' }page, perPage: 30) {
            pageInfo { hasNextPage }
            media(
              search: ${ '$' }search,
              type: ANIME,
              format: ${ '$' }format,
              format_not: ${ '$' }formatNot,
              sort: TRENDING_DESC
            ) { $FIELDS }
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
        query (${ '$' }id: Int, ${ '$' }idMal: Int) {
          Media(id: ${ '$' }id, idMal: ${ '$' }idMal, type: ANIME) {
            relations { edges { relationType node { $FIELDS } } }
          }
        }
    """.trimIndent()
    private val relationCache = ConcurrentHashMap<Int, List<AnimeSeasonRelation>>()

    internal suspend fun catalog(
        search: String? = null,
        page: Int = 1,
        section: CatalogSection = CatalogSection.ANIME,
        providers: Set<CatalogProvider> = CatalogProvider.entries.toSet(),
        remoteProviders: List<RemoteProviderConfig> = emptyList(),
        onUpdate: suspend (CatalogPage) -> Unit = {}
    ): CatalogPage = coroutineScope {
        val builtInRequests = CatalogProvider.entries
            .takeIf { section != CatalogSection.SERIES }
            .orEmpty()
            .filter(providers::contains)
            .map { provider ->
                async {
                    try {
                        when (provider) {
                            CatalogProvider.ANILIST -> anilistCatalog(search, page, section)
                            CatalogProvider.JIKAN -> CatalogFallbacks.jikanCatalog(search, page, section)
                            CatalogProvider.KITSU -> CatalogFallbacks.kitsuCatalog(search, page, section)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        CatalogPage(emptyList(), false)
                    }
                }
            }
        val addonRequests = remoteProviders
            .filter { config ->
                config.enabled && config.catalogEnabled && (
                    config.capabilities.isEmpty() || "catalog" in config.capabilities
                )
            }
            .map { config ->
                async {
                    try {
                        remoteProviderCatalog(config, search, section, page)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        CatalogPage(emptyList(), false)
                    }
                }
            }

        val requests = builtInRequests + addonRequests
        val responses = Channel<CatalogPage>(requests.size)

        requests.forEach { request ->
            launch {
                responses.send(request.await())
            }
        }

        var merged = CatalogPage(emptyList(), false)
        repeat(requests.size) {
            val received = responses.receive()
            val response = received.copy(
                items = received.items.filter(section::accepts)
            )
            merged = mergeCatalogPages(listOf(merged, response))

            if (merged.items.isNotEmpty()) {
                onUpdate(merged)
            }
        }

        merged
    }

    private fun anilistCatalog(search: String?, page: Int, section: CatalogSection): CatalogPage {
        val variables = JSONObject().put("page", page.coerceAtLeast(1))
        search.trimmedOrNull()?.let { query -> variables.put("search", query) }
        when (section) {
            CatalogSection.ANIME -> variables.put("formatNot", "MOVIE")
            CatalogSection.MOVIES -> variables.put("format", "MOVIE")
            CatalogSection.SERIES -> Unit
        }
        val response = post(catalogQuery, variables).getJSONObject("Page")
        val media = response.getJSONArray("media")
        return CatalogPage(
            items = List(media.length()) { parseAnime(media.getJSONObject(it)) },
            hasNextPage = response.optJSONObject("pageInfo")?.optBoolean("hasNextPage") == true
        )
    }

    fun favorites(ids: Set<Int>): List<Anime> = ids.filter { it > 0 }.let { anilistIds ->
        if (anilistIds.isEmpty()) emptyList() else request(favoritesQuery, JSONObject().put("ids", JSONArray(anilistIds)))
    }

    fun seasonRelations(anime: Anime): List<AnimeSeasonRelation> {
        if (anime.id <= -1_000_000_000) {
            return emptyList()
        }

        relationCache[anime.id]?.let { cached ->
            return cached
        }

        val variables = JSONObject().apply {
            if (anime.id > 0) put("id", anime.id) else anime.malId?.let { put("idMal", it) }
        }
        val edges = post(relationsQuery, variables)
            .getJSONObject("Media").getJSONObject("relations").getJSONArray("edges")
        val relations = buildList {
            repeat(edges.length()) { index ->
                val edge = edges.getJSONObject(index)
                val type = edge.getString("relationType")
                if (type !in setOf("PREQUEL", "SEQUEL")) return@repeat
                val related = parseAnime(edge.getJSONObject("node"))
                if (related.format in setOf("TV", "TV_SHORT", "ONA")) add(AnimeSeasonRelation(type, related))
            }
        }.distinctBy { it.anime.id }.sortedBy { if (it.type == "PREQUEL") 0 else 1 }

        relationCache[anime.id] = relations
        return relations
    }

    fun seasonChain(anime: Anime): List<Anime> {
        val earlier = mutableListOf<Anime>()
        val visited = mutableSetOf(anime.id)
        var cursor = anime

        for (step in 0 until 12) {
            val previous = relatedSeason(cursor, "PREQUEL", visited) ?: break
            earlier.add(previous)
            visited.add(previous.id)
            cursor = previous
        }

        val ordered = earlier.asReversed().toMutableList()
        ordered.add(anime)
        cursor = anime

        for (step in 0 until 12) {
            val next = relatedSeason(cursor, "SEQUEL", visited) ?: break
            ordered.add(next)
            visited.add(next.id)
            cursor = next
        }

        return ordered.mapIndexed { index, season ->
            season.copy(seasonNumber = index + 1)
        }
    }

    private fun relatedSeason(
        anime: Anime,
        relationType: String,
        visited: Set<Int>
    ): Anime? {
        val relations = try {
            seasonRelations(anime)
        } catch (_: Exception) {
            return null
        }

        return relations
            .asSequence()
            .filter { relation ->
                relation.type == relationType && relation.anime.id !in visited
            }
            .maxWithOrNull(
                compareBy<AnimeSeasonRelation> { relation ->
                    relation.anime.format == "TV"
                }.thenBy { relation ->
                    sharedTitleWords(anime, relation.anime)
                }.thenBy { relation ->
                    relation.anime.year ?: 0
                }
            )
            ?.anime
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
                val message = try {
                    JSONObject(response).getJSONArray("errors").getJSONObject(0).getString("message")
                } catch (_: Exception) {
                    "Falha HTTP ${connection.responseCode}"
                }
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
            title = titles.stringOrNull("english") ?: titles.optString("romaji", "Sem título"),
            romajiTitle = titles.optString("romaji", "Sem título"),
            englishTitle = titles.stringOrNull("english"),
            description = cleanDescription(item.optString("description")),
            cover = cover.optString("extraLarge").ifBlank { cover.optString("large") },
            banner = item.stringOrNull("bannerImage"),
            episodes = item.optInt("episodes").takeIf { it > 0 },
            score = item.optInt("averageScore").takeIf { it > 0 },
            year = item.optInt("seasonYear").takeIf { it > 0 },
            season = item.stringOrNull("season"),
            format = item.stringOrNull("format"),
            status = item.stringOrNull("status"),
            genres = item.getJSONArray("genres").let { genres -> List(genres.length()) { genres.getString(it) } },
            aliases = buildList {
                titles.stringOrNull("native")?.let(::add)
                addAll(item.getJSONArray("synonyms").strings())
            }.distinct(),
            nextAiringEpisode = item.optJSONObject("nextAiringEpisode")?.optInt("episode")?.takeIf { it > 0 }
        )
    }
}

private fun sharedTitleWords(first: Anime, second: Anime): Int {
    val firstWords = normalizedTitleWords(first)
    val secondWords = normalizedTitleWords(second)
    return firstWords.intersect(secondWords).size
}

private fun normalizedTitleWords(anime: Anime): Set<String> {
    val titles = listOfNotNull(anime.title, anime.romajiTitle, anime.englishTitle) + anime.aliases
    return titles
        .flatMap { title ->
            Normalizer.normalize(title, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
        }
        .filter { word -> word.length >= 3 }
        .toSet()
}

fun cleanDescription(value: String): String = value
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .replace("&amp;", "&")
    .trim()
