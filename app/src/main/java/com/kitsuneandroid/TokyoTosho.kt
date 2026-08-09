package com.kitsuneandroid

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object TokyoToshoStreamProvider : StreamProvider {
    override val id = "tokyotosho"

    override suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>> {
        val titles = animeReleaseTitles(request.anime)
        val wantedSeason = request.anime.seasonNumber ?: animeSeasonNumber(titles) ?: 1
        val releases = linkedMapOf<String, ReleaseCandidate>()

        for (query in releaseSearchQueries(request.anime, request.episode)) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val rss = fetchRss(
                url = "https://www.tokyotosho.info/rss.php?filter=1&terms=$encodedQuery",
                providerName = "Tokyo Toshokan"
            )
            parseTokyoToshoRss(
                xml = rss,
                animeTitles = titles,
                wantedEpisode = request.episode,
                wantedSeason = wantedSeason,
                playbackCapabilities = request.playbackCapabilities
            ).forEach { release -> releases[release.infoHash] = release }

            if (releases.size >= 20) break
        }

        val results = releases.values.filter { release -> release.score >= 10 }.sortedWith(RELEASE_ORDER)
        return if (results.isEmpty()) ProviderResult.Empty else ProviderResult.Success(results)
    }
}

internal fun parseTokyoToshoRss(
    xml: String,
    animeTitles: List<String>,
    wantedEpisode: Int?,
    wantedSeason: Int? = null,
    playbackCapabilities: PlaybackCapabilities = PlaybackCapabilities.commonAndroid()
): List<ReleaseCandidate> {
    return parseRssItems(xml).mapNotNull { item ->
        val title = item.text("title")
        if (!matchesAnimeTitle(title, animeTitles, wantedSeason)) return@mapNotNull null

        val description = item.text("description")
        val infoHash = INFO_HASH_IN_MAGNET.find(description)
            ?.groupValues
            ?.get(1)
            ?.let(::infoHashToHex)
            ?: return@mapNotNull null
        val sourceId = TOKYO_TOSHO_ID.find(description)?.groupValues?.get(1)
            ?: return@mapNotNull null
        val magnetUri = MAGNET_LINK.find(description)
            ?.groupValues
            ?.get(1)
            ?.replace("&amp;", "&")
            ?: "magnet:?xt=urn:btih:$infoHash"
        val rawCategory = item.text("category").equals("Raws", ignoreCase = true)
        val parsedRelease = parseReleaseTitle(title).let { parsed ->
            if (rawCategory) parsed.copy(raw = true) else parsed
        }
        val trusted = AUTHORIZED_PATTERN.containsMatchIn(description)
        val ranking = rankRelease(
            parsed = parsedRelease,
            wantedEpisode = wantedEpisode,
            seeders = null,
            trusted = trusted,
            remake = false,
            playbackCapabilities = playbackCapabilities
        )
        val size = SIZE_IN_DESCRIPTION.find(description)?.groupValues?.get(1).orEmpty()

        ReleaseCandidate(
            id = "tokyotosho:$sourceId",
            title = title,
            infoHash = infoHash,
            sizeBytes = sizeToBytes(size),
            seeders = 0,
            leechers = 0,
            trusted = trusted,
            remake = false,
            parsed = parsedRelease,
            score = ranking.score,
            reasons = ranking.reasons,
            providerId = "tokyotosho",
            sourceUrl = "https://www.tokyotosho.info/details.php?id=$sourceId",
            magnetUri = magnetUri
        )
    }.sortedWith(RELEASE_ORDER)
}

internal fun infoHashToHex(value: String): String? {
    if (HEX_INFO_HASH.matches(value)) return value.lowercase()
    if (!BASE32_INFO_HASH.matches(value)) return null

    var buffer = 0
    var bufferedBits = 0
    val bytes = mutableListOf<Int>()
    for (character in value.uppercase()) {
        buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(character)
        bufferedBits += 5
        if (bufferedBits >= 8) {
            bufferedBits -= 8
            bytes += (buffer shr bufferedBits) and 0xff
            buffer = buffer and ((1 shl bufferedBits) - 1)
        }
    }
    return bytes.joinToString("") { byte -> "%02x".format(byte) }
}

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private val HEX_INFO_HASH = Regex("[a-fA-F0-9]{40}")
private val BASE32_INFO_HASH = Regex("[A-Z2-7]{32}", RegexOption.IGNORE_CASE)
private val INFO_HASH_IN_MAGNET = Regex(
    "magnet:\\?xt=urn:btih:([a-fA-F0-9]{40}|[A-Z2-7]{32})",
    RegexOption.IGNORE_CASE
)
private val MAGNET_LINK = Regex("href=[\"'](magnet:\\?xt=urn:btih:[^\"']+)", RegexOption.IGNORE_CASE)
private val TOKYO_TOSHO_ID = Regex("tokyotosho\\.info/details\\.php\\?id=(\\d{1,12})", RegexOption.IGNORE_CASE)
private val AUTHORIZED_PATTERN = Regex("Authorized:\\s*Yes", RegexOption.IGNORE_CASE)
private val SIZE_IN_DESCRIPTION = Regex(
    "Size:\\s*([\\d.]+\\s*(?:TiB|GiB|MiB|KiB|TB|GB|MB|KB|B))",
    RegexOption.IGNORE_CASE
)
