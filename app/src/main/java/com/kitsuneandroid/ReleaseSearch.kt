package com.kitsuneandroid

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.ln

data class ParsedRelease(
    val episode: Int?,
    val episodeEnd: Int?,
    val resolution: Int?,
    val codec: String,
    val source: String,
    val batch: Boolean,
    val dualAudio: Boolean,
    val ptBr: Boolean,
    val tenBit: Boolean = false,
    val dubbed: Boolean = false,
    val raw: Boolean = false
)

enum class ReleaseLanguage { ANY, PORTUGUESE, ENGLISH, JAPANESE, DUBBED }

data class ReleasePreferences(
    val language: ReleaseLanguage = ReleaseLanguage.ANY,
    val resolution: Int? = 1080
)

data class RemoteSubtitle(
    val url: String,
    val language: String?,
    val label: String
)

data class ReleaseCandidate(
    val id: String,
    val title: String,
    val infoHash: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val trusted: Boolean,
    val remake: Boolean,
    val parsed: ParsedRelease,
    val score: Int,
    val reasons: List<String>,
    val providerId: String = "nyaa",
    val providerIds: Set<String> = setOf(providerId),
    val sourceUrl: String? = null,
    val directUrl: String? = null,
    val magnetUri: String? = null,
    val torrentFileIndex: Int? = null,
    val remoteSubtitles: List<RemoteSubtitle> = emptyList()
)

internal object ReleaseSearch {
    suspend fun search(
        anime: Anime,
        episode: Int?,
        preferences: ReleasePreferences = ReleasePreferences(),
        playbackCapabilities: PlaybackCapabilities = PlaybackCapabilities.commonAndroid()
    ): List<ReleaseCandidate> {
        val titles = animeReleaseTitles(anime)
        val season = anime.seasonNumber ?: animeSeasonNumber(titles) ?: 1
        val found = linkedMapOf<String, ReleaseCandidate>()
        val categories = if (preferences.language == ReleaseLanguage.JAPANESE) listOf("1_3", "1_2") else listOf("1_2")
        var successfulRequests = 0
        for (category in categories) {
            val responses = parallelProviderRequests(releaseSearchQueries(anime, episode)) { query ->
                try {
                    parseNyaaRss(
                        xml = fetchNyaaRss(query, category),
                        animeTitles = titles,
                        wantedEpisode = episode,
                        wantedSeason = season,
                        rawCategory = category == "1_3",
                        playbackCapabilities = playbackCapabilities
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
            successfulRequests += responses.count { releases -> releases != null }
            responses.filterNotNull().flatten().forEach { release ->
                found[release.id] = release
            }
            if (found.size >= 8) {
                break
            }
        }
        if (successfulRequests == 0) {
            throw IOException("O Nyaa está indisponível.")
        }
        return found.values.filter { it.seeders > 0 && it.score >= 10 }
            .sortedWith(compareByDescending<ReleaseCandidate> { it.score }.thenByDescending { it.seeders })
            .take(100)
    }

    private fun fetchNyaaRss(query: String, category: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://nyaa.si/?page=rss&q=$encoded&c=$category&f=0&s=seeders&o=desc"
        return fetchRss(url, "Nyaa")
    }
}

internal fun releaseSearchQueries(anime: Anime, episode: Int?): List<String> {
    val titles = animeReleaseTitles(anime)
    if (episode == null) {
        val titleWithYear = anime.year?.let { year -> "${titles.first()} $year" }
        return (listOfNotNull(titleWithYear) + titles.take(5)).distinct()
    }
    val season = anime.seasonNumber ?: animeSeasonNumber(titles) ?: 1
    val code = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    val directQueries = titles.take(3).map { title ->
        "$title ${episode.toString().padStart(2, '0')}"
    }
    val seasonQueries = titles.map(::seriesTitle).distinct().take(3).map { title ->
        "$title $code"
    }
    val prioritizedQueries = if (season > 1) {
        seasonQueries + directQueries
    } else {
        directQueries + seasonQueries
    }
    return prioritizedQueries.distinct().take(4)
}

internal fun releaseContainsEpisode(parsed: ParsedRelease, wantedEpisode: Int?): Boolean {
    if (wantedEpisode == null) {
        return true
    }
    if (parsed.episode == wantedEpisode) {
        return true
    }

    val firstEpisode = parsed.episode
    val lastEpisode = parsed.episodeEnd
    if (firstEpisode != null && lastEpisode != null && wantedEpisode in firstEpisode..lastEpisode) {
        return true
    }

    return parsed.batch
}

internal fun animeReleaseTitles(anime: Anime): List<String> {
    val titles = (listOf(anime.romajiTitle, anime.englishTitle) + anime.aliases)
        .mapNotNull { title -> title.trimmedOrNull() }
        .distinct()
    return titles.ifEmpty { listOf(anime.title) }
}

internal fun parseNyaaRss(
    xml: String,
    animeTitles: List<String>,
    wantedEpisode: Int?,
    wantedSeason: Int? = null,
    rawCategory: Boolean = false,
    playbackCapabilities: PlaybackCapabilities = PlaybackCapabilities.commonAndroid()
): List<ReleaseCandidate> {
    return parseRssItems(xml).mapNotNull { item ->
        val title = item.text("title")
        if (!matchesAnimeTitle(title, animeTitles, wantedSeason)) return@mapNotNull null

        val id = NYAA_ID_PATTERN.find(item.text("guid"))?.groupValues?.get(1)
            ?: return@mapNotNull null
        val infoHash = item.text("infoHash")
        if (!INFO_HASH_PATTERN.matches(infoHash)) return@mapNotNull null

        val parsedRelease = parseReleaseTitle(title).let { parsed ->
            if (rawCategory) parsed.copy(raw = true) else parsed
        }
        val seeders = item.text("seeders").toIntOrNull() ?: 0
        val trusted = item.text("trusted") == "Yes"
        val remake = item.text("remake") == "Yes"
        val ranking = rankRelease(
            parsed = parsedRelease,
            wantedEpisode = wantedEpisode,
            seeders = seeders,
            trusted = trusted,
            remake = remake,
            playbackCapabilities = playbackCapabilities
        )

        ReleaseCandidate(
            id = id,
            title = title,
            infoHash = infoHash,
            sizeBytes = sizeToBytes(item.text("size")),
            seeders = seeders,
            leechers = item.text("leechers").toIntOrNull() ?: 0,
            trusted = trusted,
            remake = remake,
            parsed = parsedRelease,
            score = ranking.score,
            reasons = ranking.reasons,
            providerId = "nyaa",
            sourceUrl = "https://nyaa.si/view/$id"
        )
    }.sortedWith(RELEASE_ORDER)
}

internal data class ReleaseRanking(val score: Int, val reasons: List<String>)

internal fun rankRelease(
    parsed: ParsedRelease,
    wantedEpisode: Int?,
    seeders: Int?,
    trusted: Boolean,
    remake: Boolean,
    playbackCapabilities: PlaybackCapabilities
): ReleaseRanking {
    val reasons = mutableListOf("Título reconhecido")
    var score = 35

    if (wantedEpisode != null) {
        score += episodeScore(parsed, wantedEpisode, reasons)
    }
    score += when (parsed.resolution) {
        1080 -> 12
        2160 -> 10
        720 -> 6
        else -> 0
    }
    if (parsed.source == "BLURAY" || parsed.source == "WEB_DL") score += 8

    val compatibility = codecCompatibilityScore(parsed, playbackCapabilities)
    score += compatibility.points
    reasons += compatibility.reason

    if (parsed.ptBr) {
        score += 5
        reasons += "Indica legenda PT-BR"
    }
    if (trusted) score += 3
    if (seeders != null) {
        if (seeders > 0) {
            score += seedScore(seeders)
            reasons += "$seeders seeders informados"
        } else {
            score -= 15
        }
    }
    if (remake) score -= 25

    return ReleaseRanking(score, reasons)
}

private fun episodeScore(
    parsed: ParsedRelease,
    wantedEpisode: Int,
    reasons: MutableList<String>
): Int = when {
    parsed.episode == wantedEpisode -> {
        reasons += "Episódio $wantedEpisode corresponde"
        30
    }
    parsed.episode != null && parsed.episodeEnd != null &&
        wantedEpisode in parsed.episode..parsed.episodeEnd -> 18
    parsed.batch -> 8
    else -> -20
}

private fun seedScore(seeders: Int): Int {
    val logarithmicScore = ln((seeders + 1).toDouble()) / ln(2.0)
    return ceil(logarithmicScore).toInt().coerceAtMost(10)
}

internal fun recommendedRelease(
    releases: List<ReleaseCandidate>,
    preferences: ReleasePreferences,
    playbackCapabilities: PlaybackCapabilities = PlaybackCapabilities.commonAndroid(),
    externalSubtitlesMatchPreference: Boolean = false
): ReleaseCandidate? {
    fun ReleaseCandidate.languageMatches() = when (preferences.language) {
        ReleaseLanguage.ANY -> true
        ReleaseLanguage.PORTUGUESE -> parsed.ptBr || externalSubtitlesMatchPreference
        ReleaseLanguage.ENGLISH -> !parsed.raw || externalSubtitlesMatchPreference
        ReleaseLanguage.JAPANESE -> parsed.raw || !parsed.dubbed
        ReleaseLanguage.DUBBED -> parsed.dubbed
    }
    fun ReleaseCandidate.resolutionMatches() = preferences.resolution == null || parsed.resolution == preferences.resolution
    fun ReleaseCandidate.safe(): Boolean {
        return playbackCapabilities.supportFor(parsed) != PlaybackSupport.UNSUPPORTED
    }
    val languageRequired = preferences.language != ReleaseLanguage.ANY
    val pools = buildList {
        add(releases.filter { it.safe() && it.languageMatches() && it.resolutionMatches() })
        add(releases.filter { it.languageMatches() && it.resolutionMatches() })
        add(releases.filter { it.safe() && it.languageMatches() })
        add(releases.filter { it.languageMatches() })

        if (!languageRequired) {
            add(releases.filter { it.safe() && it.resolutionMatches() })
            add(releases.filter(ReleaseCandidate::safe))
            add(releases)
        }
    }
    return pools.firstOrNull(List<ReleaseCandidate>::isNotEmpty)?.maxWithOrNull(
        compareBy<ReleaseCandidate>(ReleaseCandidate::seeders)
            .thenBy(ReleaseCandidate::score)
            .thenBy { if (it.parsed.codec == "H264") 1 else 0 }
    )
}

internal fun matchesAnimeTitle(
    releaseTitle: String,
    animeTitles: List<String>,
    wantedSeason: Int? = null
): Boolean {
    val releaseSeason = parseReleaseSeason(releaseTitle)
    if (wantedSeason != null && releaseSeason != null && releaseSeason != wantedSeason) return false

    val titleWithoutGroup = releaseTitle.replace(RELEASE_GROUP_PREFIX, "")
    val normalized = normalizeReleaseText(titleWithoutGroup)
    val aliases = animeTitles.asSequence()
        .flatMap { title -> sequenceOf(title, seriesTitle(title)) }
        .distinct()

    return aliases.any { title ->
        val alias = normalizeReleaseText(title)
        alias.length >= 4 && Regex("(?:^| )${Regex.escape(alias)}(?: |$)").containsMatchIn(normalized)
    }
}

internal fun animeSeasonNumber(titles: List<String>): Int? = titles.firstNotNullOfOrNull(::parseReleaseSeason)

internal fun parseReleaseSeason(title: String): Int? = listOf(
    Regex("\\bS(?:EASON)?\\s*0*(\\d{1,2})(?:E\\d+|\\b)", RegexOption.IGNORE_CASE),
    Regex("\\b(\\d{1,2})(?:ST|ND|RD|TH)\\s+SEASON\\b", RegexOption.IGNORE_CASE),
    Regex("\\bSEASON\\s*0*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
).firstNotNullOfOrNull { it.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() }

internal fun parseReleaseTitle(title: String): ParsedRelease {
    val seasonEpisode = SEASON_EPISODE_PATTERN.find(title)
    val range = EPISODE_RANGE_PATTERN.find(title)
    val singleEpisode = SINGLE_EPISODE_PATTERN.find(title)
    val episode = listOf(seasonEpisode, singleEpisode, range)
        .firstNotNullOfOrNull { match -> match?.groupValues?.getOrNull(1)?.toIntOrNull() }
    val episodeEnd = listOf(seasonEpisode, range)
        .firstNotNullOfOrNull { match -> match?.groupValues?.getOrNull(2)?.toIntOrNull() }
    val resolution = RESOLUTION_PATTERN.find(title)
        ?.groupValues
        ?.drop(1)
        ?.firstNotNullOfOrNull(String::toIntOrNull)
    val dubbed = DUBBED_PATTERN.containsMatchIn(title)

    return ParsedRelease(
        episode = episode,
        episodeEnd = episodeEnd,
        resolution = resolution,
        codec = releaseCodec(title),
        source = releaseSource(title),
        batch = BATCH_PATTERN.containsMatchIn(title) || episodeEnd != null,
        dualAudio = dubbed,
        ptBr = PORTUGUESE_RELEASE_PATTERN.containsMatchIn(title),
        tenBit = TEN_BIT_PATTERN.containsMatchIn(title),
        dubbed = dubbed,
        raw = RAW_PATTERN.containsMatchIn(title)
    )
}

private fun releaseCodec(title: String): String = when {
    AV1_PATTERN.containsMatchIn(title) -> "AV1"
    HEVC_PATTERN.containsMatchIn(title) -> "HEVC"
    H264_PATTERN.containsMatchIn(title) -> "H264"
    else -> "UNKNOWN"
}

private fun releaseSource(title: String): String = when {
    BLURAY_PATTERN.containsMatchIn(title) -> "BLURAY"
    WEB_DL_PATTERN.containsMatchIn(title) -> "WEB_DL"
    WEB_PATTERN.containsMatchIn(title) -> "WEB"
    TV_PATTERN.containsMatchIn(title) -> "TV"
    DVD_PATTERN.containsMatchIn(title) -> "DVD"
    else -> "UNKNOWN"
}

private fun normalizeReleaseText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(DIACRITICS_PATTERN, "")
        .lowercase()
        .replace(NON_WORD_PATTERN, " ")
        .trim()
}

internal fun seriesTitle(value: String): String = value.replace(
    Regex("\\s+(?:\\d{1,2}(?:st|nd|rd|th)\\s+season|season\\s*\\d{1,2})\\b.*$", RegexOption.IGNORE_CASE), ""
).trim().ifBlank { value }

internal val RELEASE_ORDER = compareByDescending<ReleaseCandidate>(ReleaseCandidate::score)
    .thenByDescending(ReleaseCandidate::seeders)

private val INFO_HASH_PATTERN = Regex("[a-fA-F0-9]{40}")
private val NYAA_ID_PATTERN = Regex("/view/(\\d+)$")
private val RELEASE_GROUP_PREFIX = Regex("^(?:\\s*\\[[^]]+])+\\s*")
private val DIACRITICS_PATTERN = Regex("\\p{M}+")
private val NON_WORD_PATTERN = Regex("[^\\p{L}\\p{N}]+")
private val SEASON_EPISODE_PATTERN = Regex(
    "\\bS\\d{1,2}E(\\d{1,4})(?:v\\d+)?(?:\\s*[-~]\\s*E?(\\d{1,4})(?:v\\d+)?)?\\b",
    RegexOption.IGNORE_CASE
)
private val EPISODE_RANGE_PATTERN = Regex("\\b(\\d{1,3})\\s*[-~]\\s*(\\d{1,3})\\b")
private val SINGLE_EPISODE_PATTERN = Regex(
    "(?:\\s-\\s|\\bE(?:P)?\\s*)(\\d{1,4})(?:v\\d+)?\\b",
    RegexOption.IGNORE_CASE
)
private val RESOLUTION_PATTERN = Regex(
    "(?:\\b(\\d{3,4})p\\b|\\b\\d{3,4}x(\\d{3,4})\\b)",
    RegexOption.IGNORE_CASE
)
private val DUBBED_PATTERN = Regex(
    "\\b(?:DUAL[ ._-]?AUDIO|MULTI[ ._-]?AUDIO|DUBBED|DUBLADO|ENGLISH[ ._-]?DUB|PORTUGUESE[ ._-]?DUB)\\b",
    RegexOption.IGNORE_CASE
)
private val BATCH_PATTERN = Regex(
    "\\b(?:BATCH|COMPLETE(?:\\s+(?:SERIES|SEASON))?)\\b",
    RegexOption.IGNORE_CASE
)
private val PORTUGUESE_RELEASE_PATTERN = Regex(
    "\\b(?:(?:PT|POR)[ ._-]?BR|BRAZILIAN[ ._-]?PORTUGUESE|PORTUGUESE)\\b",
    RegexOption.IGNORE_CASE
)
private val TEN_BIT_PATTERN = Regex("\\b(?:10[ ._-]?BIT|HI10P|YUV420P10)\\b", RegexOption.IGNORE_CASE)
private val RAW_PATTERN = Regex("\\bRAW\\b", RegexOption.IGNORE_CASE)
private val AV1_PATTERN = Regex("\\bAV1\\b", RegexOption.IGNORE_CASE)
private val HEVC_PATTERN = Regex("\\b(?:HEVC|H[ .]?265|X265)\\b", RegexOption.IGNORE_CASE)
private val H264_PATTERN = Regex("\\b(?:H[ .]?264|X264|AVC)\\b", RegexOption.IGNORE_CASE)
private val BLURAY_PATTERN = Regex("\\b(?:BLU-?RAY|BDRIP|BD)\\b", RegexOption.IGNORE_CASE)
private val WEB_DL_PATTERN = Regex("\\bWEB[ ._-]?DL\\b", RegexOption.IGNORE_CASE)
private val WEB_PATTERN = Regex("\\bWEB(?:RIP)?\\b", RegexOption.IGNORE_CASE)
private val TV_PATTERN = Regex("\\b(?:HDTV|TV)\\b", RegexOption.IGNORE_CASE)
private val DVD_PATTERN = Regex("\\bDVD\\b", RegexOption.IGNORE_CASE)
