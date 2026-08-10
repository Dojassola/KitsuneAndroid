package com.kitsuneandroid

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class Episode(
    val number: Int,
    val title: String?,
    val japaneseTitle: String?,
    val romanjiTitle: String?,
    val airedAt: String?,
    val durationSeconds: Int?,
    val filler: Boolean,
    val recap: Boolean,
    val synopsis: String?,
    val thumbnail: String?
)

enum class MetadataLanguage {
    PORTUGUESE,
    ORIGINAL
}

internal fun loadMetadataLanguage(context: Context): MetadataLanguage {
    val saved = context.getSharedPreferences("kitsune", Context.MODE_PRIVATE)
        .getString("metadata_language", null)
    if (saved == null) {
        return MetadataLanguage.PORTUGUESE
    }

    return try {
        MetadataLanguage.valueOf(saved)
    } catch (_: IllegalArgumentException) {
        MetadataLanguage.PORTUGUESE
    }
}

internal fun saveMetadataLanguage(context: Context, language: MetadataLanguage) {
    context.getSharedPreferences("kitsune", Context.MODE_PRIVATE)
        .edit()
        .putString("metadata_language", language.name)
        .apply()
}

object EpisodeApi {
    private val translations = ConcurrentHashMap<String, String>()
    private var translationPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        translationPreferences = context.applicationContext.getSharedPreferences(
            "episode_translations",
            Context.MODE_PRIVATE
        )
    }

    fun list(anime: Anime): List<Episode> {
        val episodes = try {
            jikanList(anime)
        } catch (_: Exception) {
            fallback(anime)
        }

        return completeEpisodeList(anime, episodes)
    }

    private fun jikanList(anime: Anime): List<Episode> {
        val malId = anime.malId ?: return fallback(anime)
        val episodes = mutableListOf<Episode>()
        var page = 1
        do {
            val payload = get("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
            val data = payload.getJSONArray("data")
            repeat(data.length()) { index ->
                episodes += parse(data.getJSONObject(index))
            }
            val pagination = payload.getJSONObject("pagination")
            if (!pagination.getBoolean("has_next_page")) break
            page++
            if (page > 25) throw IOException("A lista de episódios excede o limite suportado.")
            Thread.sleep(350)
        } while (true)
        return episodes.ifEmpty { fallback(anime) }
    }

    fun details(
        anime: Anime,
        episode: Int,
        language: MetadataLanguage = MetadataLanguage.PORTUGUESE
    ): Episode {
        val jikan = try {
            val malId = requireNotNull(anime.malId)
            parse(get("https://api.jikan.moe/v4/anime/$malId/episodes/$episode").getJSONObject("data"))
        } catch (_: Exception) {
            null
        }
        val resolved = if (!jikan?.synopsis.isNullOrBlank()) {
            requireNotNull(jikan)
        } else {
            try {
                kitsuDetails(anime, episode)
            } catch (failure: Exception) {
                jikan ?: throw failure
            }
        }
        if (language == MetadataLanguage.ORIGINAL) {
            return resolved
        }

        return resolved.copy(
            title = resolved.title?.let(::portuguese),
            synopsis = resolved.synopsis?.let(::portuguese)
        )
    }

    fun localized(text: String, language: MetadataLanguage): String {
        if (language == MetadataLanguage.ORIGINAL) {
            return text
        }

        return portuguese(text)
    }

    fun portuguese(text: String): String {
        if (text.isBlank()) {
            return text
        }

        translations[text]?.let { cached ->
            return cached
        }

        val cacheKey = translationCacheKey(text)
        translationPreferences?.getString(cacheKey, null)?.let { cached ->
            translations[text] = cached
            return cached
        }

        val translated = try {
            translationChunks(text)
                .joinToString(" ") { chunk -> translateChunk(chunk) }
                .trim()
                .ifBlank { text }
        } catch (_: Exception) {
            text
        }

        translations[text] = translated
        if (translated != text) {
            translationPreferences?.edit()?.putString(cacheKey, translated)?.apply()
        }

        return translated
    }

    private fun translateChunk(text: String): String {
        val query = URLEncoder.encode(text, StandardCharsets.UTF_8.name())

        try {
            val payload = getArray(
                "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=auto&tl=pt&dt=t&q=$query"
            )
            val segments = payload.optJSONArray(0)

            if (segments != null) {
                val translated = buildString {
                    for (index in 0 until segments.length()) {
                        append(segments.optJSONArray(index)?.optString(0).orEmpty())
                    }
                }.cleanTranslation()

                if (translated.isNotBlank()) {
                    return translated
                }
            }
        } catch (_: Exception) {
            // The second provider below keeps episode metadata usable.
        }

        val payload = get(
            "https://api.mymemory.translated.net/get?q=$query&langpair=en%7Cpt-BR"
        )
        val translated = payload
            .getJSONObject("responseData")
            .getString("translatedText")
            .cleanTranslation()

        return translated.ifBlank { text }
    }

    private fun fallback(anime: Anime): List<Episode> = List(anime.episodes ?: 0) {
        fallbackEpisode(it + 1)
    }

    private fun fallbackEpisode(number: Int) = Episode(number, null, null, null, null, null, false, false, null, null)

    private fun parse(item: JSONObject) = Episode(
        number = item.getInt("mal_id"),
        title = item.stringOrNull("title"),
        japaneseTitle = item.stringOrNull("title_japanese"),
        romanjiTitle = item.stringOrNull("title_romanji"),
        airedAt = item.stringOrNull("aired"),
        durationSeconds = item.optInt("duration").takeIf { it > 0 },
        filler = item.optBoolean("filler"),
        recap = item.optBoolean("recap"),
        synopsis = item.stringOrNull("synopsis")?.let(::cleanDescription),
        thumbnail = null
    )

    private fun kitsuDetails(anime: Anime, episode: Int): Episode {
        val query = URLEncoder.encode(anime.romajiTitle, StandardCharsets.UTF_8.name())
        val media = get(
            "https://kitsu.io/api/edge/anime?filter%5Btext%5D=$query&page%5Blimit%5D=1",
            "application/vnd.api+json"
        ).getJSONArray("data").getJSONObject(0)
        val item = get(
            "https://kitsu.io/api/edge/anime/${media.getString("id")}/episodes?filter%5Bnumber%5D=$episode&page%5Blimit%5D=1",
            "application/vnd.api+json"
        ).getJSONArray("data").getJSONObject(0).getJSONObject("attributes")
        return Episode(
            number = item.optInt("number", episode),
            title = item.stringOrNull("canonicalTitle"),
            japaneseTitle = item.optJSONObject("titles")?.stringOrNull("ja_jp"),
            romanjiTitle = item.optJSONObject("titles")?.stringOrNull("en_jp"),
            airedAt = item.stringOrNull("airdate"),
            durationSeconds = item.optInt("length").takeIf { it > 0 }?.times(60),
            filler = false,
            recap = false,
            synopsis = item.stringOrNull("synopsis")?.let(::cleanDescription),
            thumbnail = item.optJSONObject("thumbnail")?.stringOrNull("original")
        )
    }

    private fun get(url: String, accept: String = "application/json"): JSONObject {
        return JSONObject(getText(url, accept))
    }

    private fun getArray(url: String): JSONArray {
        return JSONArray(getText(url, "application/json"))
    }

    private fun getText(url: String, accept: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
        return try {
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            response
        } finally {
            connection.disconnect()
        }
    }
}

private fun translationCacheKey(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
    return "pt_" + digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.cleanTranslation(): String {
    return replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .trim()
}

internal fun translationChunks(text: String, maxBytes: Int = 450): List<String> {
    val chunks = mutableListOf<String>()
    var current = ""
    text.trim().split(Regex("\\s+")).forEach { word ->
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (candidate.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) current = candidate else {
            if (current.isNotEmpty()) chunks += current
            current = word
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

internal fun completeEpisodeList(anime: Anime, listed: List<Episode>): List<Episode> {
    val airedCount = if (anime.status == "RELEASING") {
        anime.nextAiringEpisode?.minus(1)?.coerceAtLeast(0) ?: 0
    } else anime.episodes ?: 0
    val lastEpisode = maxOf(airedCount, listed.maxOfOrNull(Episode::number) ?: 0)
    if (lastEpisode == 0) return listed
    val known = listed.associateBy(Episode::number)
    return (1..lastEpisode).map { number ->
        known[number] ?: Episode(number, null, null, null, null, null, false, false, null, null)
    }
}
