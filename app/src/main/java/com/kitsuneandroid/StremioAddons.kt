package com.kitsuneandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class StremioManifest(
    val id: String,
    val name: String,
    val types: List<String>,
    val resources: List<String>,
    val idPrefixes: List<String>
)

internal data class StremioAddonConfig(
    val manifestUrl: String,
    val name: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0
)

private class StremioHttpException(
    val statusCode: Int
) : IOException("Addon Stremio HTTP $statusCode.")

private const val PREFERENCES = "kitsune"
private const val ADDON_URLS = "stremio_addon_urls"
private const val NYAA_ENABLED = "provider_nyaa_enabled"
private const val MAX_STREMIO_RESPONSE_BYTES = 2 * 1024 * 1024

internal fun loadStremioAddonConfigs(context: Context): List<StremioAddonConfig> {
    val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(ADDON_URLS, "[]")
        .orEmpty()

    return try {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                val config = when (value) {
                    is String -> StremioAddonConfig(
                        manifestUrl = value,
                        priority = index
                    )

                    is JSONObject -> StremioAddonConfig(
                        manifestUrl = value.optString("manifestUrl"),
                        name = value.optString("name").takeIf(String::isNotBlank),
                        enabled = value.optBoolean("enabled", true),
                        priority = value.optInt("priority", index)
                    )

                    else -> null
                }

                if (config != null && config.manifestUrl.isNotBlank()) {
                    add(config)
                }
            }
        }.distinctBy(StremioAddonConfig::manifestUrl)
            .sortedBy(StremioAddonConfig::priority)
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun isNyaaProviderEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(NYAA_ENABLED, true)
}

internal fun setNyaaProviderEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(NYAA_ENABLED, enabled)
        .apply()
}

internal fun loadStremioAddonUrls(context: Context): List<String> {
    return loadStremioAddonConfigs(context)
        .filter(StremioAddonConfig::enabled)
        .map(StremioAddonConfig::manifestUrl)
}

internal fun saveStremioAddonConfigs(
    context: Context,
    configs: List<StremioAddonConfig>
) {
    val array = JSONArray()

    configs
        .distinctBy(StremioAddonConfig::manifestUrl)
        .forEachIndexed { index, config ->
            array.put(
                JSONObject()
                    .put("manifestUrl", config.manifestUrl)
                    .put("name", config.name ?: JSONObject.NULL)
                    .put("enabled", config.enabled)
                    .put("priority", index)
            )
        }

    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(ADDON_URLS, array.toString())
        .apply()
}

internal fun moveStremioAddon(
    configs: List<StremioAddonConfig>,
    manifestUrl: String,
    direction: Int
): List<StremioAddonConfig> {
    val ordered = configs.sortedBy(StremioAddonConfig::priority).toMutableList()
    val currentIndex = ordered.indexOfFirst { config -> config.manifestUrl == manifestUrl }

    if (currentIndex < 0) {
        return configs
    }

    val targetIndex = (currentIndex + direction).coerceIn(0, ordered.lastIndex)

    if (targetIndex == currentIndex) {
        return ordered
    }

    val moved = ordered.removeAt(currentIndex)
    ordered.add(targetIndex, moved)
    return ordered.mapIndexed { index, config -> config.copy(priority = index) }
}

internal fun testStremioAddon(manifestUrl: String): StremioManifest {
    val manifest = parseStremioManifest(fetchStremioJson(manifestUrl))

    require(manifest.resources.isNotEmpty()) {
        "O manifesto não oferece recursos compatíveis."
    }

    return manifest
}

internal fun normalizeStremioAddonUrl(value: String): String {
    val source = URL(value.trim())

    require(source.protocol == "https") {
        "O addon precisa usar HTTPS."
    }
    require(source.userInfo == null && source.query == null && source.ref == null) {
        "A URL do addon contém dados não suportados."
    }
    require(isPublicHost(source.host)) {
        "Endereços locais ou privados não são permitidos."
    }

    val path = source.path.trimEnd('/').ifBlank { "" }
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return URL(source.protocol, source.host, source.port, manifestPath).toString()
}

private fun isPublicHost(host: String): Boolean {
    val normalized = host.lowercase().removePrefix("[").removeSuffix("]")

    if (normalized == "localhost" || normalized == "::1" || normalized.startsWith("127.")) {
        return false
    }

    if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) {
        return false
    }

    val secondIpv4Part = normalized
        .takeIf { address -> address.startsWith("172.") }
        ?.split('.')
        ?.getOrNull(1)
        ?.toIntOrNull()

    return secondIpv4Part == null || secondIpv4Part !in 16..31
}

internal fun parseStremioManifest(payload: JSONObject): StremioManifest {
    val resources = payload.optJSONArray("resources") ?: JSONArray()
    val resourceNames = buildList {
        for (index in 0 until resources.length()) {
            val resource = resources.opt(index)

            when (resource) {
                is String -> add(resource)
                is JSONObject -> resource.optString("name").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    return StremioManifest(
        id = payload.getString("id"),
        name = payload.optString("name").ifBlank { payload.getString("id") },
        types = payload.stringList("types"),
        resources = resourceNames,
        idPrefixes = payload.stringList("idPrefixes")
    )
}

internal class StremioStreamProvider(
    private val config: StremioAddonConfig
) : StreamProvider {
    private val manifestUrl = config.manifestUrl
    override val id = "stremio:${sha1(manifestUrl)}"

    override suspend fun streams(
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> {
        val manifest = parseStremioManifest(fetchStremioJson(manifestUrl))

        if ("stream" !in manifest.resources) {
            return ProviderResult.Empty
        }

        val type = when {
            "anime" in manifest.types -> "anime"
            "series" in manifest.types -> "series"
            request.anime.format == "MOVIE" && "movie" in manifest.types -> "movie"
            else -> return ProviderResult.Empty
        }
        val ids = stremioIds(request, manifest.idPrefixes)
        val baseUrl = manifestUrl.removeSuffix("/manifest.json")
        val releases = mutableListOf<ReleaseCandidate>()

        for (mediaId in ids) {
            val encodedId = URLEncoder.encode(mediaId, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            val endpoint = "$baseUrl/stream/$type/$encodedId.json"
            val payload = fetchOptionalStremioJson(endpoint) ?: continue
            val streams = parseStremioStreams(payload, manifest, manifestUrl, request)

            if (streams.isEmpty()) {
                continue
            }

            val subtitles = if ("subtitles" in manifest.resources) {
                val subtitleEndpoint = "$baseUrl/subtitles/$type/$encodedId.json"
                fetchOptionalStremioJson(subtitleEndpoint)
                    ?.let(::parseStremioSubtitles)
                    .orEmpty()
            } else {
                emptyList()
            }
            releases.addAll(
                streams.map { release ->
                    val priorityBoost = (6 - config.priority * 2).coerceAtLeast(0)
                    release.copy(
                        score = release.score + priorityBoost,
                        reasons = release.reasons + "Prioridade ${config.priority + 1} do provedor",
                        remoteSubtitles = subtitles
                    )
                }
            )

            if (releases.isNotEmpty()) {
                break
            }
        }

        if (releases.isEmpty()) {
            return ProviderResult.Empty
        }

        return ProviderResult.Success(releases)
    }
}

private fun stremioIds(
    request: StreamRequest,
    supportedPrefixes: List<String>
): List<String> {
    val baseIds = buildList {
        request.anime.malId?.let { malId ->
            add("mal:$malId")
        }
        add("anilist:${request.anime.id}")
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
            "Stream direto fornecido por ${manifest.name}"
        } else {
            "Torrent fornecido por ${manifest.name}"
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

        val language = subtitle.optString("lang")
            .takeIf(String::isNotBlank)
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

private fun isSafeRemoteUrl(value: String): Boolean {
    return try {
        val url = URL(value)
        url.protocol == "https" &&
            url.userInfo == null &&
            isPublicHost(url.host)
    } catch (_: Exception) {
        false
    }
}

private fun fetchStremioJson(value: String): JSONObject {
    val connection = URL(value).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")

    return try {
        if (connection.responseCode !in 200..299) {
            throw StremioHttpException(connection.responseCode)
        }

        if (!isSafeRemoteUrl(connection.url.toString())) {
            throw IOException("O addon redirecionou para um endereço não permitido.")
        }

        if (connection.contentLengthLong > MAX_STREMIO_RESPONSE_BYTES) {
            throw IOException("Resposta do addon Stremio grande demais.")
        }

        val bytes = connection.inputStream.use(::readStremioResponse)

        JSONObject(bytes.toString(StandardCharsets.UTF_8))
    } finally {
        connection.disconnect()
    }
}

private fun fetchOptionalStremioJson(value: String): JSONObject? {
    return try {
        fetchStremioJson(value)
    } catch (failure: StremioHttpException) {
        if (failure.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            null
        } else {
            throw failure
        }
    }
}

private fun readStremioResponse(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16_384)

    while (true) {
        val read = input.read(buffer)

        if (read < 0) {
            break
        }

        output.write(buffer, 0, read)

        if (output.size() > MAX_STREMIO_RESPONSE_BYTES) {
            throw IOException("Resposta do addon Stremio grande demais.")
        }
    }

    return output.toByteArray()
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()

    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)

            if (value.isNotBlank()) {
                add(value)
            }
        }
    }
}

private fun sha1(value: String): String {
    return MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
