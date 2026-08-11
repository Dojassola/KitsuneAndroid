@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.media3.common.text.Cue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val CURRENT_SUBTITLE_MARKER = "[[KITSUNE_CURRENT]]"
private const val SUBTITLE_CONTEXT_SIZE = 2
private const val MAX_SUBTITLE_CONTEXT_CHARS = 240

internal class LiveSubtitleTranslator(
    private val sourceLanguage: String,
    private val targetLanguage: String
) : Closeable {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(requireSupportedLanguage(sourceLanguage))
            .setTargetLanguage(requireSupportedLanguage(targetLanguage))
            .build()
    )
    private val translations = mutableMapOf<String, String>()
    private val recentSourceCues = ArrayDeque<String>()

    fun prepare() {
        Tasks.await(translator.downloadModelIfNeeded())
    }

    @Synchronized
    fun translate(cues: List<Cue>): List<Cue> {
        val translatedCues = cues.map { cue ->
            val text = cue.text?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                cue
            } else {
                val input = contextualSubtitleInput(recentSourceCues, text)
                val translated = translations.getOrPut(input) {
                    translateWithGoogle(input, sourceLanguage, targetLanguage)
                        ?.let { result ->
                            extractCurrentSubtitleTranslation(
                                translated = result,
                                contextWasIncluded = CURRENT_SUBTITLE_MARKER in input
                            )
                        }
                        ?: Tasks.await(translator.translate(text))
                }
                cue.buildUpon().setText(translated).build()
            }
        }

        cues.mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .forEach(::rememberSourceCue)
        return translatedCues
    }

    @Synchronized
    fun resetContext() {
        recentSourceCues.clear()
    }

    private fun rememberSourceCue(text: String) {
        if (recentSourceCues.lastOrNull() == text) {
            return
        }
        recentSourceCues.addLast(text)
        while (recentSourceCues.size > SUBTITLE_CONTEXT_SIZE) {
            recentSourceCues.removeFirst()
        }
    }

    override fun close() {
        translator.close()
    }
}

internal fun contextualSubtitleInput(context: Collection<String>, current: String): String {
    val previous = context.joinToString("\n").takeLast(MAX_SUBTITLE_CONTEXT_CHARS).trim()
    if (previous.isEmpty()) {
        return current
    }
    return "$previous\n$CURRENT_SUBTITLE_MARKER\n$current"
}

internal fun extractCurrentSubtitleTranslation(
    translated: String,
    contextWasIncluded: Boolean
): String? {
    if (contextWasIncluded && CURRENT_SUBTITLE_MARKER !in translated) {
        return null
    }
    val current = translated.substringAfter(CURRENT_SUBTITLE_MARKER, translated)
    return current.trim().takeIf(String::isNotEmpty)
}

internal fun translateWithGoogle(text: String, sourceLanguage: String, targetLanguage: String): String? {
    val query = URLEncoder.encode(text, StandardCharsets.UTF_8.name())
    val endpoint = "https://translate.googleapis.com/translate_a/single" +
        "?client=gtx&sl=$sourceLanguage&tl=$targetLanguage&dt=t&q=$query"
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    connection.connectTimeout = 3_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.3")

    return try {
        if (connection.responseCode !in 200..299) {
            return null
        }
        val payload = JSONArray(connection.inputStream.bufferedReader().use { reader -> reader.readText() })
        val segments = payload.optJSONArray(0) ?: return null
        buildString {
            for (index in 0 until segments.length()) {
                append(segments.optJSONArray(index)?.optString(0).orEmpty())
            }
        }.trim().takeIf(String::isNotEmpty)
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

internal fun subtitleTranslationLanguage(language: String?, label: String?): String? {
    val rawCode = language
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase(Locale.ROOT)
    val code = when {
        rawCode?.length == 2 -> rawCode
        rawCode?.length == 3 -> Locale.getISOLanguages().firstOrNull { iso2 ->
            runCatching { Locale.forLanguageTag(iso2).isO3Language }.getOrNull() == rawCode
        }
        else -> null
    } ?: languageFromLabel(label)

    return code?.takeIf { candidate ->
        TranslateLanguage.fromLanguageTag(candidate) != null
    }
}

private fun languageFromLabel(label: String?): String? {
    val normalized = label?.lowercase(Locale.ROOT) ?: return null
    return when {
        normalized.contains("portugu") -> "pt"
        normalized.contains("ingl") || normalized.contains("english") -> "en"
        normalized.contains("japon") || normalized.contains("japanese") -> "ja"
        normalized.contains("espan") || normalized.contains("spanish") -> "es"
        normalized.contains("tailand") || normalized.contains("thai") -> "th"
        normalized.contains("malaio") || normalized.contains("indon") -> "id"
        else -> null
    }
}

private fun requireSupportedLanguage(language: String): String {
    return TranslateLanguage.fromLanguageTag(language)
        ?: throw IOException("O idioma $language não é compatível com a tradução no aparelho.")
}
