package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Calendar
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
    val nextAiringAt: Long? = null,
    val seasonNumber: Int? = null,
    val remoteMediaId: String? = null,
    val remoteMediaType: String? = null,
    val remoteManifestUrl: String? = null,
    val remoteProtocol: RemoteProviderProtocol? = null
)

data class AnimeSeasonRelation(val type: String, val anime: Anime)

internal data class AnimeTrailer(
    val url: String,
    val thumbnail: String?
)

internal data class AnimeCharacter(
    val name: String,
    val image: String?,
    val role: String?,
    val voiceActor: String?,
    val voiceActorImage: String?
)

internal data class AnimeStaffMember(
    val name: String,
    val image: String?,
    val role: String?
)

internal data class AnimeExtras(
    val trailer: AnimeTrailer? = null,
    val studios: List<String> = emptyList(),
    val characters: List<AnimeCharacter> = emptyList(),
    val staff: List<AnimeStaffMember> = emptyList(),
    val recommendations: List<Anime> = emptyList()
)

internal enum class HomeSection {
    AIRING_TODAY,
    TRENDING,
    UPCOMING,
    RECOMMENDED
}

object AnimeApi {
    private const val ENDPOINT = "https://graphql.anilist.co"
    internal const val FIELDS = """
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
        nextAiringEpisode { episode airingAt }
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
    private val trendingQuery = """
        query {
          Page(page: 1, perPage: 14) {
            media(type: ANIME, format_not: MOVIE, sort: TRENDING_DESC) { $FIELDS }
          }
        }
    """.trimIndent()
    private val upcomingQuery = """
        query {
          Page(page: 1, perPage: 14) {
            media(type: ANIME, status: NOT_YET_RELEASED, sort: POPULARITY_DESC) { $FIELDS }
          }
        }
    """.trimIndent()
    private val airingTodayQuery = """
        query (${ '$' }start: Int, ${ '$' }end: Int) {
          Page(page: 1, perPage: 20) {
            airingSchedules(
              airingAt_greater: ${ '$' }start,
              airingAt_lesser: ${ '$' }end,
              sort: TIME
            ) { media { $FIELDS } }
          }
        }
    """.trimIndent()
    private val recommendationsQuery = """
        query (${ '$' }id: Int) {
          Media(id: ${ '$' }id, type: ANIME) {
            recommendations(page: 1, perPage: 14, sort: RATING_DESC) {
              nodes { mediaRecommendation { $FIELDS } }
            }
          }
        }
    """.trimIndent()
    private val extrasQuery = """
        query (${ '$' }id: Int, ${ '$' }idMal: Int) {
          Media(id: ${ '$' }id, idMal: ${ '$' }idMal, type: ANIME) {
            trailer { id site thumbnail }
            studios(isMain: true) { nodes { name } }
            characters(page: 1, perPage: 12, sort: [ROLE, RELEVANCE]) {
              edges {
                role
                node { name { full } image { large } }
                voiceActors(language: JAPANESE, sort: RELEVANCE) {
                  name { full }
                  image { large }
                }
              }
            }
            staff(page: 1, perPage: 10, sort: RELEVANCE) {
              edges {
                role
                node { name { full } image { large } }
              }
            }
            recommendations(page: 1, perPage: 12, sort: RATING_DESC) {
              nodes { mediaRecommendation { $FIELDS } }
            }
          }
        }
    """.trimIndent()
    private val relationCache = ConcurrentHashMap<Int, List<AnimeSeasonRelation>>()
    private val extrasCache = ConcurrentHashMap<Int, AnimeExtras>()

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

    internal suspend fun discovery(
        recommendationSeedId: Int?,
        onUpdate: suspend (HomeSection, List<Anime>) -> Unit
    ): Map<HomeSection, List<Anime>> = coroutineScope {
        val loaders = buildList<Pair<HomeSection, () -> List<Anime>>> {
            add(HomeSection.AIRING_TODAY to ::airingToday)
            add(HomeSection.TRENDING to ::trending)
            add(HomeSection.UPCOMING to ::upcoming)
            recommendationSeedId
                ?.takeIf { id -> id > 0 }
                ?.let { id ->
                    add(HomeSection.RECOMMENDED to { recommendations(id) })
                }
        }
        val requests = loaders.map { (section, loader) ->
            async {
                val items = try {
                    loader()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
                section to items
            }
        }
        val responses = Channel<Pair<HomeSection, List<Anime>>>(requests.size)
        requests.forEach { request ->
            launch {
                responses.send(request.await())
            }
        }

        val result = linkedMapOf<HomeSection, List<Anime>>()
        repeat(requests.size) {
            val (section, items) = responses.receive()
            result[section] = items
            onUpdate(section, items)
        }
        result
    }

    private fun trending(): List<Anime> {
        return mediaPage(trendingQuery)
    }

    private fun upcoming(): List<Anime> {
        return mediaPage(upcomingQuery)
    }

    private fun airingToday(): List<Anime> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = start.clone() as Calendar
        end.add(Calendar.DAY_OF_MONTH, 1)
        val variables = JSONObject()
            .put("start", start.timeInMillis / 1_000)
            .put("end", end.timeInMillis / 1_000)
        val schedules = post(airingTodayQuery, variables)
            .getJSONObject("Page")
            .getJSONArray("airingSchedules")

        return List(schedules.length()) { index ->
            parseAnime(schedules.getJSONObject(index).getJSONObject("media"))
        }.distinctBy(Anime::id)
    }

    private fun recommendations(seedId: Int): List<Anime> {
        val media = post(recommendationsQuery, JSONObject().put("id", seedId))
            .optJSONObject("Media")
            ?: return emptyList()
        val nodes = media
            .getJSONObject("recommendations")
            .getJSONArray("nodes")

        return buildList {
            repeat(nodes.length()) { index ->
                nodes.getJSONObject(index)
                    .optJSONObject("mediaRecommendation")
                    ?.let { item -> add(parseAnime(item)) }
            }
        }.distinctBy(Anime::id)
    }

    private fun mediaPage(query: String): List<Anime> {
        val media = post(query, JSONObject())
            .getJSONObject("Page")
            .getJSONArray("media")
        return List(media.length()) { index ->
            parseAnime(media.getJSONObject(index))
        }
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

    internal fun extras(anime: Anime): AnimeExtras {
        extrasCache[anime.id]?.let { cached ->
            return cached
        }
        if (anime.id <= 0 && anime.malId == null) {
            return AnimeExtras()
        }

        val variables = JSONObject().apply {
            if (anime.id > 0) {
                put("id", anime.id)
            } else {
                anime.malId?.let { id -> put("idMal", id) }
            }
        }
        val media = post(extrasQuery, variables)
            .optJSONObject("Media")
            ?: return AnimeExtras()
        val extras = AnimeExtras(
            trailer = parseTrailer(media.optJSONObject("trailer")),
            studios = media
                .optJSONObject("studios")
                ?.optJSONArray("nodes")
                ?.let { nodes ->
                    List(nodes.length()) { index ->
                        nodes.getJSONObject(index).getString("name")
                    }
                }
                .orEmpty(),
            characters = parseCharacters(media.optJSONObject("characters")),
            staff = parseStaff(media.optJSONObject("staff")),
            recommendations = parseRecommendations(media.optJSONObject("recommendations"))
        )
        extrasCache[anime.id] = extras
        return extras
    }

    private fun parseTrailer(item: JSONObject?): AnimeTrailer? {
        val id = item?.stringOrNull("id") ?: return null
        val url = animeTrailerUrl(item.stringOrNull("site"), id) ?: return null
        return AnimeTrailer(url, item.stringOrNull("thumbnail"))
    }

    private fun parseCharacters(connection: JSONObject?): List<AnimeCharacter> {
        val edges = connection?.optJSONArray("edges") ?: return emptyList()
        return buildList {
            repeat(edges.length()) { index ->
                val edge = edges.getJSONObject(index)
                val character = edge.getJSONObject("node")
                val voiceActor = edge.optJSONArray("voiceActors")
                    ?.optJSONObject(0)
                add(
                    AnimeCharacter(
                        name = character.getJSONObject("name").getString("full"),
                        image = character.optJSONObject("image")?.stringOrNull("large"),
                        role = edge.stringOrNull("role"),
                        voiceActor = voiceActor?.optJSONObject("name")?.stringOrNull("full"),
                        voiceActorImage = voiceActor?.optJSONObject("image")?.stringOrNull("large")
                    )
                )
            }
        }
    }

    private fun parseStaff(connection: JSONObject?): List<AnimeStaffMember> {
        val edges = connection?.optJSONArray("edges") ?: return emptyList()
        return buildList {
            repeat(edges.length()) { index ->
                val edge = edges.getJSONObject(index)
                val staff = edge.getJSONObject("node")
                add(
                    AnimeStaffMember(
                        name = staff.getJSONObject("name").getString("full"),
                        image = staff.optJSONObject("image")?.stringOrNull("large"),
                        role = edge.stringOrNull("role")
                    )
                )
            }
        }
    }

    private fun parseRecommendations(connection: JSONObject?): List<Anime> {
        val nodes = connection?.optJSONArray("nodes") ?: return emptyList()
        return buildList {
            repeat(nodes.length()) { index ->
                nodes.getJSONObject(index)
                    .optJSONObject("mediaRecommendation")
                    ?.let { item -> add(parseAnime(item)) }
            }
        }.distinctBy(Anime::id)
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
            setRequestProperty("User-Agent", "KitsuneAndroid/1.6")
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

    internal fun parseAnime(item: JSONObject): Anime {
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
            nextAiringEpisode = item.optJSONObject("nextAiringEpisode")
                ?.optInt("episode")
                ?.takeIf { it > 0 },
            nextAiringAt = item.optJSONObject("nextAiringEpisode")
                ?.optLong("airingAt")
                ?.takeIf { it > 0 }
        )
    }
}

internal fun animeTrailerUrl(site: String?, id: String): String? {
    return when (site?.lowercase()) {
        "youtube" -> "https://www.youtube.com/watch?v=$id"
        "dailymotion" -> "https://www.dailymotion.com/video/$id"
        else -> null
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
