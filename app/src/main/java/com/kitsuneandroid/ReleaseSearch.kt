package com.kitsuneandroid

import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
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
    val ptBr: Boolean
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
    val reasons: List<String>
)

object ReleaseSearch {
    fun search(anime: Anime, episode: Int?): List<ReleaseCandidate> {
        val titles = listOfNotNull(anime.romajiTitle, anime.englishTitle).distinct()
        val primary = titles.first()
        val queries = listOfNotNull(
            anime.year?.let { "$primary $it" },
            "$primary${episode?.let { " ${it.toString().padStart(2, '0')}" }.orEmpty()}",
            anime.englishTitle?.takeIf { it != primary }?.let {
                "$it${episode?.let { number -> " ${number.toString().padStart(2, '0')}" }.orEmpty()}"
            }
        ).distinct()
        val found = linkedMapOf<String, ReleaseCandidate>()
        for (query in queries) {
            parseNyaaRss(fetch(query), titles, episode).forEach { found[it.id] = it }
            if (found.size >= 20) break
        }
        return found.values.filter { it.seeders > 0 && it.score >= 10 }
            .sortedWith(compareByDescending<ReleaseCandidate> { it.score }.thenByDescending { it.seeders })
            .take(100)
    }

    private fun fetch(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val connection = URL("https://nyaa.si/?page=rss&q=$encoded&c=1_2&f=0&s=seeders&o=desc")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml")
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Nyaa HTTP $code")
            if (text.length > 2_000_000) throw IOException("A resposta do provedor é grande demais.")
            text
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseNyaaRss(xml: String, animeTitles: List<String>, wantedEpisode: Int?): List<ReleaseCandidate> {
    require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "RSS inválido." }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }
    val items = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).getElementsByTagName("item")
    return buildList {
        repeat(items.length) { index ->
            val item = items.item(index) as? Element ?: return@repeat
            val title = item.text("title")
            if (!matchesAnimeTitle(title, animeTitles)) return@repeat
            val id = Regex("/view/(\\d+)$").find(item.text("guid"))?.groupValues?.get(1) ?: return@repeat
            val hash = item.text("infoHash")
            if (!hash.matches(Regex("[a-fA-F0-9]{40}"))) return@repeat
            val parsed = parseReleaseTitle(title)
            val seeders = item.text("seeders").toIntOrNull() ?: 0
            val trusted = item.text("trusted") == "Yes"
            val remake = item.text("remake") == "Yes"
            val reasons = mutableListOf<String>()
            var score = 35
            reasons += "Título reconhecido"
            if (wantedEpisode != null) {
                score += when {
                    parsed.episode == wantedEpisode -> 30.also { reasons += "Episódio $wantedEpisode corresponde" }
                    parsed.episode != null && parsed.episodeEnd != null && wantedEpisode in parsed.episode..parsed.episodeEnd -> 18
                    parsed.batch -> 8
                    else -> -20
                }
            }
            score += when (parsed.resolution) { 1080 -> 12; 2160 -> 10; 720 -> 6; else -> 0 }
            if (parsed.source == "BLURAY" || parsed.source == "WEB_DL") score += 8
            if (parsed.codec == "HEVC" || parsed.codec == "AV1") score += 5
            if (parsed.ptBr) { score += 5; reasons += "Indica legenda PT-BR" }
            if (trusted) score += 3
            if (seeders > 0) {
                score += ceil(ln((seeders + 1).toDouble()) / ln(2.0)).toInt().coerceAtMost(10)
                reasons += "$seeders seeders"
            } else score -= 15
            if (remake) score -= 25
            add(ReleaseCandidate(
                id, title, hash, sizeToBytes(item.text("size")), seeders,
                item.text("leechers").toIntOrNull() ?: 0, trusted, remake, parsed, score, reasons
            ))
        }
    }.sortedWith(compareByDescending<ReleaseCandidate> { it.score }.thenByDescending { it.seeders })
}

internal fun matchesAnimeTitle(releaseTitle: String, animeTitles: List<String>): Boolean {
    val withoutGroup = releaseTitle.replace(Regex("^(?:\\s*\\[[^]]+])+\\s*"), "")
    val segments = withoutGroup.split('|').map(::normalizeReleaseText)
    return animeTitles.any { title ->
        val alias = normalizeReleaseText(title)
        alias.length >= 2 && segments.any { segment ->
            segment == alias || segment.startsWith("$alias ") && segment.removePrefix("$alias ")
                .substringBefore(' ').matches(Regex("(?:\\d{1,4}|s\\d+|e\\d+|ep\\d+|v\\d+|season|part|cour|ova|oad|special|movie|complete|batch|bd|bluray|bdrip|web|hdtv|dvd|remux|dual|multi|vol)", RegexOption.IGNORE_CASE))
        }
    }
}

internal fun parseReleaseTitle(title: String): ParsedRelease {
    val seasonEpisode = Regex("\\bS\\d{1,2}E(\\d{1,4})(?:\\s*[-~]\\s*E?(\\d{1,4}))?\\b", RegexOption.IGNORE_CASE).find(title)
    val range = Regex("\\b(\\d{1,3})\\s*[-~]\\s*(\\d{1,3})\\b").find(title)
    val single = Regex("(?:\\s-\\s|\\bE(?:P)?\\s*)(\\d{1,4})(?:v\\d+)?\\b", RegexOption.IGNORE_CASE).find(title)
    val resolution = Regex("(?:\\b(\\d{3,4})p\\b|\\b\\d{3,4}x(\\d{3,4})\\b)", RegexOption.IGNORE_CASE).find(title)
    val episode = (seasonEpisode?.groupValues?.getOrNull(1) ?: single?.groupValues?.getOrNull(1) ?: range?.groupValues?.getOrNull(1)).orEmpty().toIntOrNull()
    val episodeEnd = (seasonEpisode?.groupValues?.getOrNull(2) ?: range?.groupValues?.getOrNull(2)).orEmpty().toIntOrNull()
    val upper = title.uppercase()
    return ParsedRelease(
        episode,
        episodeEnd,
        resolution?.groupValues?.drop(1)?.firstNotNullOfOrNull(String::toIntOrNull),
        when { Regex("\\bAV1\\b").containsMatchIn(upper) -> "AV1"; Regex("\\b(?:HEVC|H[ .]?265|X265)\\b").containsMatchIn(upper) -> "HEVC"; Regex("\\b(?:H[ .]?264|X264|AVC)\\b").containsMatchIn(upper) -> "H264"; else -> "UNKNOWN" },
        when { Regex("\\b(?:BLU-?RAY|BDRIP|BD)\\b").containsMatchIn(upper) -> "BLURAY"; Regex("\\bWEB[ ._-]?DL\\b").containsMatchIn(upper) -> "WEB_DL"; Regex("\\bWEB(?:RIP)?\\b").containsMatchIn(upper) -> "WEB"; Regex("\\b(?:HDTV|TV)\\b").containsMatchIn(upper) -> "TV"; Regex("\\bDVD\\b").containsMatchIn(upper) -> "DVD"; else -> "UNKNOWN" },
        Regex("\\bBATCH\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) || episodeEnd != null,
        Regex("\\bDUAL[ ._-]?AUDIO\\b", RegexOption.IGNORE_CASE).containsMatchIn(title),
        Regex("\\b(?:PT[ ._-]?BR|BRAZILIAN[ ._-]?PORTUGUESE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)
    )
}

private fun Element.text(name: String): String {
    val children = childNodes
    repeat(children.length) { index ->
        val node = children.item(index)
        if ((node.localName ?: node.nodeName.substringAfter(':')) == name) return node.textContent.trim()
    }
    return ""
}

private fun normalizeReleaseText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun sizeToBytes(value: String): Long {
    val match = Regex("^([\\d.]+)\\s*(KiB|MiB|GiB|TiB|B)$", RegexOption.IGNORE_CASE).find(value) ?: return 0
    val power = listOf("b", "kib", "mib", "gib", "tib").indexOf(match.groupValues[2].lowercase()).coerceAtLeast(0)
    return (match.groupValues[1].toDoubleOrNull().orZero() * Math.pow(1024.0, power.toDouble())).toLong()
}

private fun Double?.orZero() = this ?: 0.0
