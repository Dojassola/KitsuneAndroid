package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class StremioManifest(
    val id: String,
    val name: String,
    val types: List<String>,
    val resources: List<String>,
    val idPrefixes: List<String>,
    val catalogs: List<StremioCatalog> = emptyList()
)

internal data class StremioCatalog(
    val id: String,
    val type: String,
    val name: String,
    val supportsSearch: Boolean,
    val searchRequired: Boolean
)

internal fun parseStremioManifest(payload: JSONObject): StremioManifest {
    val resources = payload.optJSONArray("resources") ?: JSONArray()
    val streamIdPrefixes = mutableListOf<String>()
    val resourceNames = buildList {
        for (index in 0 until resources.length()) {
            val resource = resources.opt(index)

            when (resource) {
                is String -> add(resource)
                is JSONObject -> {
                    val name = resource.stringOrNull("name")
                    name?.let(::add)
                    if (name == "stream") {
                        streamIdPrefixes.addAll(resource.stringList("idPrefixes"))
                    }
                }
            }
        }
    }

    val catalogs = payload.optJSONArray("catalogs") ?: JSONArray()
    val parsedCatalogs = buildList {
        for (index in 0 until catalogs.length()) {
            val catalog = catalogs.optJSONObject(index) ?: continue
            val id = catalog.optString("id")
            val type = catalog.optString("type")
            if (id.isBlank() || type.isBlank()) {
                continue
            }
            val extra = catalog.optJSONArray("extra") ?: JSONArray()
            var supportsSearch = false
            var searchRequired = false
            for (extraIndex in 0 until extra.length()) {
                val option = extra.optJSONObject(extraIndex) ?: continue
                if (option.optString("name") == "search") {
                    supportsSearch = true
                    searchRequired = option.optBoolean("isRequired", false)
                }
            }
            add(
                StremioCatalog(
                    id = id,
                    type = type,
                    name = catalog.optString("name").ifBlank { id },
                    supportsSearch = supportsSearch,
                    searchRequired = searchRequired
                )
            )
        }
    }

    return StremioManifest(
        id = payload.getString("id"),
        name = payload.optString("name").ifBlank { payload.getString("id") },
        types = payload.stringList("types"),
        resources = resourceNames,
        idPrefixes = (payload.stringList("idPrefixes") + streamIdPrefixes).distinct(),
        catalogs = parsedCatalogs
    )
}

internal fun stremioCatalog(config: RemoteProviderConfig, search: String?): List<Anime> {
    val manifest = parseStremioManifest(fetchRemoteManifestJson(config.manifestUrl))
    if ("catalog" !in manifest.resources) {
        return emptyList()
    }

    val baseUrl = config.manifestUrl.removeSuffix("/manifest.json")
    return manifest.catalogs
        .asSequence()
        .filter { catalog -> search != null || !catalog.searchRequired }
        .flatMap { catalog ->
            val path = stremioCatalogPath(baseUrl, catalog, search)
            val payload = fetchOptionalRemoteJson(path) ?: return@flatMap emptySequence()
            parseStremioCatalog(payload, config.manifestUrl, catalog.type)
                .asSequence()
                .filter { anime ->
                    search == null || catalog.supportsSearch ||
                        anime.title.contains(search, ignoreCase = true)
                }
        }
        .take(60)
        .toList()
}

private fun stremioCatalogPath(
    baseUrl: String,
    catalog: StremioCatalog,
    search: String?
): String {
    val type = encodeStremioPath(catalog.type)
    val id = encodeStremioPath(catalog.id)
    if (search == null || !catalog.supportsSearch) {
        return "$baseUrl/catalog/$type/$id.json"
    }

    val query = URLEncoder.encode(search, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    return "$baseUrl/catalog/$type/$id/search=$query.json"
}

internal fun parseStremioCatalog(
    payload: JSONObject,
    manifestUrl: String,
    catalogType: String
): List<Anime> {
    val metas = payload.optJSONArray("metas") ?: return emptyList()
    return buildList {
        for (index in 0 until metas.length()) {
            val meta = metas.optJSONObject(index) ?: continue
            parseStremioAnime(meta, manifestUrl, catalogType)?.let(::add)
        }
    }
}

private fun parseStremioAnime(
    meta: JSONObject,
    manifestUrl: String,
    catalogType: String
): Anime? {
    val stremioId = meta.optString("id")
    val title = meta.optString("name")
    if (stremioId.isBlank() || title.isBlank()) {
        return null
    }
    val type = meta.optString("type").ifBlank { catalogType }
    val videos = meta.optJSONArray("videos")
    val episodeCount = videos?.length()?.takeIf { count -> count > 0 }
    val year = Regex("\\b(19|20)\\d{2}\\b")
        .find(meta.optString("releaseInfo"))
        ?.value
        ?.toIntOrNull()
    val score = meta.optString("imdbRating")
        .toDoubleOrNull()
        ?.times(10)
        ?.toInt()

    return Anime(
        id = stremioAnimeId(manifestUrl, stremioId),
        malId = null,
        title = title,
        romajiTitle = title,
        englishTitle = title,
        description = meta.optString("description"),
        cover = meta.optString("poster").takeIf(::isSafeRemoteUrl).orEmpty(),
        banner = meta.stringOrNull("background")?.takeIf(::isSafeRemoteUrl),
        episodes = episodeCount,
        score = score,
        year = year,
        season = null,
        format = if (type == "movie") "MOVIE" else "TV",
        status = null,
        genres = meta.stringList("genres"),
        remoteMediaId = stremioId,
        remoteMediaType = type,
        remoteManifestUrl = manifestUrl,
        remoteProtocol = RemoteProviderProtocol.STREMIO
    )
}

internal fun stremioEpisodes(anime: Anime): List<Episode> {
    val manifestUrl = anime.remoteManifestUrl ?: return emptyList()
    val stremioId = anime.remoteMediaId ?: return emptyList()
    val type = anime.remoteMediaType ?: "series"
    val baseUrl = manifestUrl.removeSuffix("/manifest.json")
    val endpoint = "$baseUrl/meta/${encodeStremioPath(type)}/${encodeStremioPath(stremioId)}.json"
    val meta = fetchOptionalRemoteJson(endpoint)?.optJSONObject("meta") ?: return emptyList()
    val videos = meta.optJSONArray("videos")
    if (videos == null || videos.length() == 0) {
        return listOf(stremioEpisode(meta, 1, stremioId))
    }

    return buildList {
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            val number = video.optInt("episode", index + 1).takeIf { value -> value > 0 }
                ?: continue
            add(stremioEpisode(video, number, video.optString("id")))
        }
    }.distinctBy(Episode::number).sortedBy(Episode::number)
}

private fun stremioEpisode(source: JSONObject, number: Int, videoId: String): Episode {
    return Episode(
        number = number,
        title = source.stringOrNull("title") ?: source.stringOrNull("name"),
        japaneseTitle = null,
        romanjiTitle = null,
        airedAt = source.stringOrNull("released"),
        durationSeconds = null,
        filler = false,
        recap = false,
        synopsis = source.stringOrNull("overview") ?: source.stringOrNull("description"),
        thumbnail = source.stringOrNull("thumbnail")?.takeIf(::isSafeRemoteUrl),
        remoteVideoId = videoId.takeIf(String::isNotBlank)
    )
}

private fun stremioAnimeId(manifestUrl: String, stremioId: String): Int {
    val suffix = sha1("$manifestUrl|$stremioId").take(7).toInt(16)
    return -1_500_000_000 - suffix
}

private fun encodeStremioPath(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

internal class StremioStreamProvider(
    private val config: RemoteProviderConfig
) : StreamProvider {
    private val manifestUrl = config.manifestUrl
    override val id = "stremio:${sha1(manifestUrl)}"

    override suspend fun streams(
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> {
        val manifest = parseStremioManifest(fetchRemoteManifestJson(manifestUrl))

        if ("stream" !in manifest.resources) {
            return ProviderResult.Empty
        }

        val type = when {
            request.anime.remoteManifestUrl == manifestUrl &&
                request.anime.remoteMediaType in manifest.types -> requireNotNull(request.anime.remoteMediaType)
            "anime" in manifest.types -> "anime"
            "series" in manifest.types -> "series"
            request.anime.format == "MOVIE" && "movie" in manifest.types -> "movie"
            else -> return ProviderResult.Empty
        }
        val ids = stremioIds(request, manifest.idPrefixes, manifestUrl)
        val baseUrl = manifestUrl.removeSuffix("/manifest.json")
        val responses = parallelProviderRequests(ids) { mediaId ->
            val encodedId = URLEncoder.encode(mediaId, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            val endpoint = "$baseUrl/stream/$type/$encodedId.json"
            val streams = fetchOptionalRemoteJson(endpoint)
                ?.let { payload -> parseStremioStreams(payload, manifest, manifestUrl, request) }
                .orEmpty()
            encodedId to streams
        }
        val selected = responses.firstOrNull { (_, streams) -> streams.isNotEmpty() }
        if (selected == null) {
            return ProviderResult.Empty
        }
        val (encodedId, streams) = selected
        val subtitles = if ("subtitles" in manifest.resources) {
            val subtitleEndpoint = "$baseUrl/subtitles/$type/$encodedId.json"
            fetchOptionalRemoteJson(subtitleEndpoint)
                ?.let(::parseStremioSubtitles)
                .orEmpty()
        } else {
            emptyList()
        }
        val priorityBoost = (6 - config.priority * 2).coerceAtLeast(0)
        val releases = streams.map { release ->
            release.copy(
                score = release.score + priorityBoost,
                reasons = release.reasons + ReleaseReason.ProviderPriority(config.priority + 1),
                remoteSubtitles = subtitles
            )
        }

        return ProviderResult.Success(releases)
    }
}

internal fun stremioIds(
    request: StreamRequest,
    supportedPrefixes: List<String>,
    manifestUrl: String
): List<String> {
    if (request.anime.remoteManifestUrl == manifestUrl) {
        request.remoteVideoId?.let { videoId ->
            return listOf(videoId)
        }
        request.anime.remoteMediaId?.let { mediaId ->
            return listOf(mediaId)
        }
    }

    val baseIds = buildList {
        request.anime.malId?.let { malId ->
            add("mal:$malId")
        }
        add("anilist:${request.anime.id}")
        if ("kitsu" in supportedPrefixes) {
            stremioKitsuId(request.anime)?.let { kitsuId ->
                add("kitsu:$kitsuId")
            }
        }
    }
    val filteredIds = if (supportedPrefixes.isEmpty()) {
        baseIds
    } else {
        baseIds.filter { id ->
            supportedPrefixes.any { prefix -> id.startsWith("$prefix:") }
        }
    }

    return filteredIds.map { id ->
        request.episode?.let { episode -> "$id:$episode" } ?: id
    }
}

private fun stremioKitsuId(anime: Anime): String? {
    val catalogId = -1_000_000_000 - anime.id
    if (catalogId > 0 && anime.id > -1_500_000_000) {
        return catalogId.toString()
    }

    return anime.malId?.let(MalCatalogFallback::kitsuId)
}

internal fun parseStremioStreams(
    payload: JSONObject,
    manifest: StremioManifest,
    manifestUrl: String,
    request: StreamRequest
): List<ReleaseCandidate> {
    val streams = payload.optJSONArray("streams") ?: return emptyList()
    val releases = mutableListOf<ReleaseCandidate>()

    for (index in 0 until streams.length()) {
        val stream = streams.optJSONObject(index) ?: continue
        val directUrl = stream.optString("url")
        val infoHash = stream.optString("infoHash")
            .lowercase()
            .takeIf { hash -> hash.matches(Regex("[a-f0-9]{40}")) }
        val isDirectStream = isSafeRemoteUrl(directUrl)

        if (!isDirectStream && infoHash == null) {
            continue
        }

        val title = listOf(
            stream.optJSONObject("behaviorHints")?.optString("filename").orEmpty(),
            stream.optString("name"),
            stream.optString("title")
        ).filter(String::isNotBlank).joinToString(" • ").ifBlank {
            "${manifest.name} • Episódio ${request.episode ?: "especial"}"
        }
        val parsed = parseReleaseTitle(title)
        val preferredResolution = request.preferences.resolution
        val score = if (preferredResolution != null && parsed.resolution == preferredResolution) {
            70
        } else {
            50
        }
        val compatibility = codecCompatibilityScore(parsed, request.playbackCapabilities)
        val candidateHash = infoHash ?: sha1(directUrl)
        val fileIndex = stream.optInt("fileIdx", -1)
            .takeIf { value -> value >= 0 }
        val magnetUri = infoHash?.let { hash ->
            stremioMagnetUri(hash, title, stream.optJSONArray("sources"))
        }
        val reason = if (isDirectStream) {
            ReleaseReason.DirectStreamBy(manifest.name)
        } else {
            ReleaseReason.TorrentBy(manifest.name)
        }

        releases.add(
            ReleaseCandidate(
                id = "stremio:${manifest.id}:$candidateHash:${fileIndex ?: -1}",
                title = title,
                infoHash = candidateHash,
                sizeBytes = 0,
                seeders = 0,
                leechers = 0,
                trusted = false,
                remake = false,
                parsed = parsed,
                score = score + compatibility.points,
                reasons = listOf(reason, compatibility.reason),
                providerId = "stremio:${manifest.id}",
                sourceUrl = manifestUrl,
                directUrl = directUrl.takeIf { isDirectStream },
                magnetUri = magnetUri,
                torrentFileIndex = fileIndex
            )
        )
    }

    return releases
}

internal fun parseStremioSubtitles(payload: JSONObject): List<RemoteSubtitle> {
    val subtitles = payload.optJSONArray("subtitles") ?: return emptyList()
    val tracks = mutableListOf<RemoteSubtitle>()

    for (index in 0 until subtitles.length()) {
        val subtitle = subtitles.optJSONObject(index) ?: continue
        val url = subtitle.optString("url")

        if (!isSafeRemoteUrl(url)) {
            continue
        }

        val language = subtitle.stringOrNull("lang")
        val label = subtitle.optString("id")
            .ifBlank { language ?: "Legenda ${index + 1}" }
        tracks.add(
            RemoteSubtitle(
                url = url,
                language = language,
                label = label
            )
        )

        if (tracks.size == 8) {
            break
        }
    }

    return tracks
}

private fun stremioMagnetUri(
    infoHash: String,
    title: String,
    sources: JSONArray?
): String {
    val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
    val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHash&dn=$encodedTitle")

    if (sources != null) {
        for (index in 0 until sources.length()) {
            val source = sources.optString(index)

            if (!source.startsWith("tracker:")) {
                continue
            }

            val tracker = source.removePrefix("tracker:")
            val encodedTracker = URLEncoder.encode(
                tracker,
                StandardCharsets.UTF_8.name()
            )
            magnet.append("&tr=").append(encodedTracker)
        }
    }

    return magnet.toString()
}
