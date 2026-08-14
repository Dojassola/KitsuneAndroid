package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val KITSUNE_ADDON_SCHEMA = "kitsune-addon/v1"

internal data class KitsuneAddonManifest(
    val id: String,
    val version: String,
    val name: String,
    val capabilities: List<String>,
    val acceptedIds: List<String>,
    val endpoints: Map<String, String>
) {
    fun descriptor(): RemoteProviderDescriptor {
        return RemoteProviderDescriptor(
            protocol = RemoteProviderProtocol.KITSUNE,
            id = id,
            name = name,
            version = version,
            capabilities = capabilities
        )
    }
}

internal fun parseKitsuneManifest(payload: JSONObject): KitsuneAddonManifest {
    require(payload.optString("schema") == KITSUNE_ADDON_SCHEMA) {
        "Schema de provider Kitsune não suportado."
    }

    val id = payload.optString("id")
    val version = payload.optString("version")
    val name = payload.optString("name")
    val capabilities = payload.stringList("capabilities")
        .filter(SUPPORTED_KITSUNE_CAPABILITIES::contains)
        .distinct()
    val endpointsObject = payload.optJSONObject("endpoints") ?: JSONObject()
    val endpoints = capabilities.mapNotNull { capability ->
        endpointsObject.stringOrNull(capability)?.let { endpoint -> capability to endpoint }
    }.toMap()

    require(id.matches(Regex("[a-zA-Z0-9._-]{3,100}"))) {
        "ID do provider Kitsune inválido."
    }
    require(version.matches(Regex("\\d+\\.\\d+\\.\\d+(?:[-+][a-zA-Z0-9.-]+)?"))) {
        "Versão do provider Kitsune inválida."
    }
    require(name.isNotBlank() && name.length <= 100) {
        "Nome do provider Kitsune inválido."
    }
    require(capabilities.isNotEmpty()) {
        "O provider Kitsune não oferece capacidades compatíveis."
    }
    require(endpoints.keys.containsAll(capabilities)) {
        "O provider Kitsune não declarou todos os endpoints necessários."
    }

    return KitsuneAddonManifest(
        id = id,
        version = version,
        name = name,
        capabilities = capabilities,
        acceptedIds = payload.stringList("acceptedIds").distinct(),
        endpoints = endpoints
    )
}

internal fun remoteProviderCatalog(
    config: RemoteProviderConfig,
    search: String?
): List<Anime> {
    return when (config.protocol) {
        RemoteProviderProtocol.STREMIO -> stremioCatalog(config, search)
        RemoteProviderProtocol.KITSUNE -> kitsuneCatalog(config, search)
    }
}

internal fun remoteProviderEpisodes(anime: Anime): List<Episode> {
    val manifestUrl = anime.remoteManifestUrl ?: return emptyList()
    val config = RemoteProviderConfig(
        manifestUrl = manifestUrl,
        protocol = anime.remoteProviderProtocol()
    )
    return when (config.protocol) {
        RemoteProviderProtocol.STREMIO -> stremioEpisodes(anime)
        RemoteProviderProtocol.KITSUNE -> kitsuneEpisodes(anime, config)
    }
}

internal fun remoteStreamProvider(config: RemoteProviderConfig): StreamProvider {
    return when (config.protocol) {
        RemoteProviderProtocol.STREMIO -> StremioStreamProvider(config)
        RemoteProviderProtocol.KITSUNE -> KitsuneStreamProvider(config)
    }
}

private fun Anime.remoteProviderProtocol(): RemoteProviderProtocol {
    return remoteProtocol ?: RemoteProviderProtocol.STREMIO
}

private fun kitsuneCatalog(config: RemoteProviderConfig, search: String?): List<Anime> {
    val manifest = loadKitsuneManifest(config)
    if ("catalog" !in manifest.capabilities) {
        return emptyList()
    }

    val endpoint = manifest.endpoint(config.manifestUrl, "catalog")
    val payload = fetchOptionalRemoteJson(
        endpoint.withQuery(
            "search" to search,
            "limit" to "60"
        )
    ) ?: return emptyList()
    val items = payload.optJSONArray("items") ?: return emptyList()

    return buildList {
        for (index in 0 until minOf(items.length(), 60)) {
            val item = items.optJSONObject(index) ?: continue
            parseKitsuneAnime(item, config.manifestUrl)?.let(::add)
        }
    }
}

private fun parseKitsuneAnime(item: JSONObject, manifestUrl: String): Anime? {
    val mediaId = item.optString("id")
    val title = item.optString("title")
    if (mediaId.isBlank() || title.isBlank()) {
        return null
    }

    val titles = item.optJSONObject("titles")
    val ids = item.optJSONObject("ids")
    return Anime(
        id = remoteAnimeId(manifestUrl, mediaId),
        malId = ids?.optInt("mal")?.takeIf { value -> value > 0 },
        title = title,
        romajiTitle = titles?.stringOrNull("romaji") ?: title,
        englishTitle = titles?.stringOrNull("english"),
        description = item.optString("description"),
        cover = item.stringOrNull("poster")?.takeIf(::isSafeRemoteUrl).orEmpty(),
        banner = item.stringOrNull("background")?.takeIf(::isSafeRemoteUrl),
        episodes = item.optInt("episodeCount").takeIf { value -> value > 0 },
        score = item.optInt("score").takeIf { value -> value in 1..100 },
        year = item.optInt("year").takeIf { value -> value > 0 },
        season = item.stringOrNull("season"),
        format = item.stringOrNull("format"),
        status = item.stringOrNull("status"),
        genres = item.stringList("genres"),
        aliases = item.stringList("aliases"),
        seasonNumber = item.optInt("seasonNumber").takeIf { value -> value > 0 },
        remoteMediaId = mediaId,
        remoteMediaType = item.optString("contentKind").ifBlank { "anime-series" },
        remoteManifestUrl = manifestUrl,
        remoteProtocol = RemoteProviderProtocol.KITSUNE
    )
}

private fun kitsuneEpisodes(
    anime: Anime,
    config: RemoteProviderConfig
): List<Episode> {
    val mediaId = anime.remoteMediaId ?: return emptyList()
    val manifest = loadKitsuneManifest(config)
    if ("metadata" !in manifest.capabilities) {
        return emptyList()
    }

    val payload = fetchOptionalRemoteJson(
        manifest.endpoint(config.manifestUrl, "metadata").withQuery("id" to mediaId)
    ) ?: return emptyList()
    val item = payload.optJSONObject("item") ?: return emptyList()
    val episodes = item.optJSONArray("episodes") ?: return emptyList()

    return buildList {
        for (index in 0 until minOf(episodes.length(), MAX_KITSUNE_EPISODES)) {
            val episode = episodes.optJSONObject(index) ?: continue
            val number = episode.optInt("number").takeIf { value -> value > 0 } ?: continue
            add(
                Episode(
                    number = number,
                    title = episode.stringOrNull("title"),
                    japaneseTitle = episode.stringOrNull("japaneseTitle"),
                    romanjiTitle = episode.stringOrNull("romajiTitle"),
                    airedAt = episode.stringOrNull("releasedAt"),
                    durationSeconds = episode.optInt("durationSeconds")
                        .takeIf { value -> value > 0 },
                    filler = episode.optBoolean("filler", false),
                    recap = episode.optBoolean("recap", false),
                    synopsis = episode.stringOrNull("description"),
                    thumbnail = episode.stringOrNull("thumbnail")?.takeIf(::isSafeRemoteUrl),
                    remoteVideoId = episode.stringOrNull("id")
                )
            )
        }
    }.distinctBy(Episode::number).sortedBy(Episode::number)
}

private class KitsuneStreamProvider(
    private val config: RemoteProviderConfig
) : StreamProvider {
    override val id = "kitsune:${config.providerId ?: sha1(config.manifestUrl)}"

    override suspend fun streams(
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> {
        val manifest = loadKitsuneManifest(config)
        if ("streams" !in manifest.capabilities) {
            return ProviderResult.Empty
        }

        val mediaId = request.anime.remoteMediaId
            .takeIf { request.anime.remoteManifestUrl == config.manifestUrl }
        val parameters = arrayOf(
            "id" to mediaId,
            "videoId" to request.remoteVideoId,
            "anilist" to request.anime.id.takeIf { value -> value > 0 }?.toString(),
            "mal" to request.anime.malId?.toString(),
            "episode" to request.episode?.toString()
        )
        val endpoint = manifest.endpoint(config.manifestUrl, "streams")
            .withQuery(*parameters)
        val payload = fetchOptionalRemoteJson(endpoint) ?: return ProviderResult.Empty
        val releases = parseKitsuneStreams(payload, manifest, config, request)
        if (releases.isEmpty()) {
            return ProviderResult.Empty
        }

        val subtitles = if ("subtitles" in manifest.capabilities) {
            val subtitleEndpoint = manifest.endpoint(config.manifestUrl, "subtitles")
                .withQuery(*parameters)
            fetchOptionalRemoteJson(subtitleEndpoint)
                ?.optJSONArray("subtitles")
                ?.let(::parseKitsuneSubtitles)
                .orEmpty()
        } else {
            emptyList()
        }
        val enrichedReleases = releases.map { release ->
            release.copy(
                remoteSubtitles = (release.remoteSubtitles + subtitles)
                    .distinctBy { subtitle -> subtitle.url }
            )
        }
        return ProviderResult.Success(enrichedReleases)
    }
}

internal fun parseKitsuneStreams(
    payload: JSONObject,
    manifest: KitsuneAddonManifest,
    config: RemoteProviderConfig,
    request: StreamRequest
): List<ReleaseCandidate> {
    val streams = payload.optJSONArray("streams") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(streams.length(), MAX_KITSUNE_STREAMS)) {
            val stream = streams.optJSONObject(index) ?: continue
            val source = stream.optJSONObject("source") ?: continue
            val kind = source.optString("kind")
            val infoHash = source.optString("infoHash")
                .lowercase()
                .takeIf { hash -> hash.matches(INFO_HASH_PATTERN) }
            val directUrl = source.optString("url").takeIf(::isSafeRemoteUrl)
            if (kind == "torrent" && infoHash == null) {
                continue
            }
            if (kind == "http" && directUrl == null) {
                continue
            }
            if (kind != "torrent" && kind != "http") {
                continue
            }

            val release = stream.optJSONObject("release") ?: JSONObject()
            val availability = stream.optJSONObject("availability") ?: JSONObject()
            val title = release.optString("title").ifBlank {
                "${manifest.name} • Episódio ${request.episode ?: "especial"}"
            }
            val parsed = parseReleaseTitle(title)
            val compatibility = codecCompatibilityScore(parsed, request.playbackCapabilities)
            val seeders = availability.optInt("seeders").coerceAtLeast(0)
            val score = 50 + compatibility.points + minOf(seeders, 100) / 5
            val fileIndex = source.optInt("fileIndex", -1).takeIf { value -> value >= 0 }
            val magnet = infoHash?.let { hash ->
                kitsuneMagnetUri(hash, title, source.optJSONArray("trackers"))
            }
            val remoteSubtitles = parseKitsuneSubtitles(stream.optJSONArray("subtitles"))

            add(
                ReleaseCandidate(
                    id = "kitsune:${manifest.id}:${stream.optString("id")}:$index",
                    title = title,
                    infoHash = infoHash ?: sha1(requireNotNull(directUrl)),
                    sizeBytes = release.optLong("sizeBytes").coerceAtLeast(0),
                    seeders = seeders,
                    leechers = availability.optInt("leechers").coerceAtLeast(0),
                    trusted = false,
                    remake = false,
                    parsed = parsed,
                    score = score + (6 - config.priority * 2).coerceAtLeast(0),
                    reasons = listOf(
                        ReleaseReason.ProvidedBy(manifest.name),
                        compatibility.reason
                    ),
                    providerId = "kitsune:${manifest.id}",
                    sourceUrl = config.manifestUrl,
                    directUrl = directUrl,
                    magnetUri = magnet,
                    torrentFileIndex = fileIndex,
                    remoteSubtitles = remoteSubtitles
                )
            )
        }
    }
}

private fun parseKitsuneSubtitles(values: JSONArray?): List<RemoteSubtitle> {
    if (values == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until minOf(values.length(), MAX_KITSUNE_SUBTITLES)) {
            val subtitle = values.optJSONObject(index) ?: continue
            val url = subtitle.optString("url").takeIf(::isSafeRemoteUrl) ?: continue
            add(
                RemoteSubtitle(
                    url = url,
                    language = subtitle.stringOrNull("language"),
                    label = subtitle.optString("label").ifBlank { "Legenda ${index + 1}" }
                )
            )
        }
    }
}

private fun loadKitsuneManifest(config: RemoteProviderConfig): KitsuneAddonManifest {
    return parseKitsuneManifest(fetchRemoteManifestJson(config.manifestUrl))
}

private fun KitsuneAddonManifest.endpoint(manifestUrl: String, capability: String): String {
    val path = requireNotNull(endpoints[capability])
    val resolved = URL(URL(manifestUrl), path).toString()
    require(isSafeRemoteUrl(resolved)) {
        "O endpoint $capability do provider não é seguro."
    }
    return resolved
}

private fun String.withQuery(vararg parameters: Pair<String, String?>): String {
    val values = parameters.filter { (_, value) -> !value.isNullOrBlank() }
    if (values.isEmpty()) {
        return this
    }
    val separator = if ('?' in this) '&' else '?'
    val query = values.joinToString("&") { (name, value) ->
        "${name.urlEncoded()}=${requireNotNull(value).urlEncoded()}"
    }
    return "$this$separator$query"
}

private fun String.urlEncoded(): String {
    return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

private fun kitsuneMagnetUri(infoHash: String, title: String, trackers: JSONArray?): String {
    val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHash&dn=${title.urlEncoded()}")
    if (trackers == null) {
        return magnet.toString()
    }
    for (index in 0 until trackers.length()) {
        val tracker = trackers.optString(index).takeIf(::isSafeRemoteUrl) ?: continue
        magnet.append("&tr=").append(tracker.urlEncoded())
    }
    return magnet.toString()
}

private fun remoteAnimeId(manifestUrl: String, mediaId: String): Int {
    val suffix = sha1("$manifestUrl|$mediaId").take(7).toInt(16)
    return -1_600_000_000 - suffix
}

private val SUPPORTED_KITSUNE_CAPABILITIES = setOf(
    "catalog",
    "metadata",
    "streams",
    "subtitles"
)
private val INFO_HASH_PATTERN = Regex("[a-f0-9]{40}")
private const val MAX_KITSUNE_EPISODES = 2_000
private const val MAX_KITSUNE_STREAMS = 200
private const val MAX_KITSUNE_SUBTITLES = 100
