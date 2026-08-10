package com.kitsuneandroid

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object NekoBtStreamProvider : StreamProvider {
    override val id = "nekobt"

    override suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>> {
        val titles = animeReleaseTitles(request.anime)
        val wantedSeason = request.anime.seasonNumber ?: animeSeasonNumber(titles) ?: 1
        val releases = linkedMapOf<String, ReleaseCandidate>()
        var providerReached = false

        for (query in releaseSearchQueries(request.anime, request.episode)) {
            val payload = try {
                fetchNekoBt(query)
            } catch (_: Exception) {
                continue
            }
            providerReached = true

            parseNekoBt(
                payload = payload,
                animeTitles = titles,
                wantedEpisode = request.episode,
                wantedSeason = wantedSeason,
                playbackCapabilities = request.playbackCapabilities
            ).forEach { release ->
                releases[release.infoHash] = release
            }

            if (releases.size >= 8) {
                break
            }
        }

        if (!providerReached && releases.isEmpty()) {
            throw IOException("O nekoBT está indisponível.")
        }

        val results = releases.values
            .filter { release -> release.seeders > 0 && release.score >= 10 }
            .sortedWith(RELEASE_ORDER)
        if (results.isEmpty()) {
            return ProviderResult.Empty
        }

        return ProviderResult.Success(results)
    }

    private fun fetchNekoBt(query: String): JSONObject {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://nekobt.to/api/v1/torrents/search" +
            "?query=$encodedQuery&limit=30&sort_by=seeders"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.2")

        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException("nekoBT HTTP ${connection.responseCode}")
            }

            val response = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            if (response.length > MAX_NEKOBT_RESPONSE_CHARACTERS) {
                throw IOException("A resposta do nekoBT é grande demais.")
            }

            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseNekoBt(
    payload: JSONObject,
    animeTitles: List<String>,
    wantedEpisode: Int?,
    wantedSeason: Int? = null,
    playbackCapabilities: PlaybackCapabilities = PlaybackCapabilities.commonAndroid()
): List<ReleaseCandidate> {
    val results = payload.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
    val releases = mutableListOf<ReleaseCandidate>()

    for (index in 0 until results.length()) {
        val item = results.optJSONObject(index) ?: continue
        val title = item.optString("title")
        if (!matchesAnimeTitle(title, animeTitles, wantedSeason)) {
            continue
        }

        val infoHash = item.optString("infohash").lowercase()
        val magnet = item.optString("magnet")
        val sourceId = item.optString("id")
        if (!NEKOBT_INFO_HASH.matches(infoHash) ||
            !magnet.startsWith("magnet:?xt=urn:btih:", ignoreCase = true) ||
            !NEKOBT_SOURCE_ID.matches(sourceId)
        ) {
            continue
        }

        val subtitleLanguages = item.optString("sub_lang").split(',').map(String::trim)
        val audioLanguages = item.optString("audio_lang").split(',').map(String::trim)
        val parsed = parseReleaseTitle(title).let { release ->
            val dubbed = release.dubbed || audioLanguages.any { language ->
                language.isNotBlank() && language != "ja"
            }
            release.copy(
                ptBr = release.ptBr || "pt-br" in subtitleLanguages,
                dubbed = dubbed,
                dualAudio = release.dualAudio || audioLanguages.count { language ->
                    language.isNotBlank()
                } > 1
            )
        }
        if (!releaseContainsEpisode(parsed, wantedEpisode)) {
            continue
        }

        val seeders = item.optString("seeders").toIntOrNull() ?: 0
        val ranking = rankRelease(
            parsed = parsed,
            wantedEpisode = wantedEpisode,
            seeders = seeders,
            trusted = false,
            remake = false,
            playbackCapabilities = playbackCapabilities
        )
        releases += ReleaseCandidate(
            id = "nekobt:$sourceId",
            title = title,
            infoHash = infoHash,
            sizeBytes = item.optString("filesize").toLongOrNull() ?: 0,
            seeders = seeders,
            leechers = item.optString("leechers").toIntOrNull() ?: 0,
            trusted = false,
            remake = false,
            parsed = parsed,
            score = ranking.score,
            reasons = ranking.reasons,
            providerId = "nekobt",
            sourceUrl = "https://nekobt.to/torrents/$sourceId",
            magnetUri = magnet
        )
    }

    return releases.sortedWith(RELEASE_ORDER)
}

private const val MAX_NEKOBT_RESPONSE_CHARACTERS = 2_000_000
private val NEKOBT_INFO_HASH = Regex("[a-f0-9]{40}")
private val NEKOBT_SOURCE_ID = Regex("[0-9]{1,20}")
