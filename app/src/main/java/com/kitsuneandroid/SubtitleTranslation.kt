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
import java.util.concurrent.ConcurrentHashMap

private const val BATCH_MARKER_START = '\uE000'
private const val BATCH_MARKER_END = '\uE001'
private const val TITLE_MARKER_START = '\uE100'
private const val TITLE_MARKER_END = '\uE101'
private const val MAX_BATCH_GROUPS = 8
private const val MAX_BATCH_CHARACTERS = 700
private val BATCH_MARKER_REGEX = Regex("$BATCH_MARKER_START(\\d+):(\\d+)$BATCH_MARKER_END")
private val TITLE_MARKER_REGEX = Regex("$TITLE_MARKER_START([^$TITLE_MARKER_END]*)$TITLE_MARKER_END")
private val TITLE_CASE_REGEX = Regex(
    "(?<![\\p{L}\\p{N}])[\\p{Lu}][\\p{L}\\p{M}'’.\\-]*" +
        "(?:\\s+[\\p{Lu}][\\p{L}\\p{M}'’.\\-]*)+(?![\\p{L}\\p{N}])"
)
private val LOWERCASE_WORD_START_REGEX = Regex("(?<![\\p{L}\\p{N}])\\p{Ll}")

internal data class MarkedSubtitleText(
    val text: String,
    val titleCount: Int
)

internal class LiveSubtitleTranslator(
    val sourceLanguage: String,
    private val targetLanguage: String
) : Closeable {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(requireSupportedLanguage(sourceLanguage))
            .setTargetLanguage(requireSupportedLanguage(targetLanguage))
            .build()
    )
    private val translations = ConcurrentHashMap<String, String>()
    private val translationLock = Any()

    fun prepare() {
        Tasks.await(translator.downloadModelIfNeeded())
    }

    fun translate(cues: List<Cue>, upcoming: List<List<Cue>>): List<Cue> {
        val context = subtitleTranslationContext(cues, upcoming)
        translateBatch(context, cues)
        return cached(cues) ?: throw IOException("A tradução não retornou todas as falas.")
    }

    fun cached(cues: List<Cue>): List<Cue>? {
        return cues.map { cue ->
            val source = cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            if (source == null) {
                cue
            } else {
                val translated = translations[source] ?: return null
                cue.buildUpon().setText(translated).build()
            }
        }
    }

    fun prefetch(cueGroups: List<List<Cue>>) {
        val firstPending = cueGroups
            .filter(::hasSubtitleText)
            .distinctBy(::subtitleCueKey)
            .firstOrNull(::needsTranslation)
            ?: return
        val context = subtitleTranslationContext(firstPending, cueGroups)
        translateBatch(context, firstPending)
    }

    private fun translateBatch(cueGroups: List<List<Cue>>, requiredCues: List<Cue>) {
        synchronized(translationLock) {
            if (!needsTranslation(requiredCues)) {
                return
            }

            val source = cueGroups
                .filter(::hasSubtitleText)
                .distinctBy(::subtitleCueKey)
                .map(::subtitleCueTexts)
            val encoded = encodeSubtitleTranslationBatch(source)
            val marked = markSubtitleTitles(encoded)
            val translatedText = translateWithGoogle(
                marked.text,
                sourceLanguage,
                targetLanguage
            ) ?: Tasks.await(translator.translate(marked.text))
            val restored = restoreSubtitleTitleCase(translatedText, marked.titleCount)
                ?: throw IOException("A tradução não preservou a capitalização dos títulos.")
            val translated = decodeSubtitleTranslationBatch(restored, source)
                ?: throw IOException("A tradução não preservou a separação entre as falas.")

            source.forEachIndexed { groupIndex, sourceCues ->
                sourceCues.forEachIndexed { cueIndex, sourceText ->
                    if (sourceText != null) {
                        translations[sourceText] = requireNotNull(translated[groupIndex][cueIndex])
                    }
                }
            }
        }
    }

    private fun needsTranslation(cues: List<Cue>): Boolean {
        return subtitleCueTexts(cues).filterNotNull().any { text ->
            !translations.containsKey(text)
        }
    }

    override fun close() {
        translator.close()
    }
}

internal fun subtitleCueKey(cues: List<Cue>): String {
    return subtitleCueTexts(cues).joinToString("\u001F") { text -> text.orEmpty() }
}

private fun hasSubtitleText(cues: List<Cue>): Boolean {
    return cues.any { cue -> !cue.text?.toString()?.trim().isNullOrEmpty() }
}

private fun subtitleCueTexts(cues: List<Cue>): List<String?> {
    return cues.map { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
}

internal fun subtitleTranslationContext(
    current: List<Cue>,
    chronological: List<List<Cue>>
): List<List<Cue>> {
    val ordered = chronological
        .filter(::hasSubtitleText)
        .distinctBy(::subtitleCueKey)
    val currentKey = subtitleCueKey(current)
    val currentIndex = ordered.indexOfFirst { cues -> subtitleCueKey(cues) == currentKey }
    if (currentIndex < 0) {
        return limitedSubtitleContext((listOf(current) + ordered).distinctBy(::subtitleCueKey))
    }

    val selected = mutableSetOf(currentIndex)
    var characters = subtitleCharacterCount(ordered[currentIndex])
    var previousIndex = (currentIndex - 1).takeIf { it >= 0 }
    var nextIndex = (currentIndex + 1).takeIf { it < ordered.size }
    while (selected.size < MAX_BATCH_GROUPS && (previousIndex != null || nextIndex != null)) {
        previousIndex?.let { index ->
            val candidateCharacters = subtitleCharacterCount(ordered[index])
            if (characters + candidateCharacters <= MAX_BATCH_CHARACTERS) {
                selected += index
                characters += candidateCharacters
                previousIndex = (index - 1).takeIf { it >= 0 }
            } else {
                previousIndex = null
            }
        }
        nextIndex?.let { index ->
            if (selected.size < MAX_BATCH_GROUPS) {
                val candidateCharacters = subtitleCharacterCount(ordered[index])
                if (characters + candidateCharacters <= MAX_BATCH_CHARACTERS) {
                    selected += index
                    characters += candidateCharacters
                    nextIndex = (index + 1).takeIf { it < ordered.size }
                } else {
                    nextIndex = null
                }
            }
        }
    }
    return selected.sorted().map(ordered::get)
}

private fun limitedSubtitleContext(groups: List<List<Cue>>): List<List<Cue>> {
    val selected = mutableListOf<List<Cue>>()
    var characters = 0
    for (group in groups) {
        val candidateCharacters = subtitleCharacterCount(group)
        if (selected.isNotEmpty() && characters + candidateCharacters > MAX_BATCH_CHARACTERS) {
            break
        }
        selected += group
        characters += candidateCharacters
        if (selected.size == MAX_BATCH_GROUPS) {
            break
        }
    }
    return selected
}

private fun subtitleCharacterCount(cues: List<Cue>): Int {
    return subtitleCueTexts(cues).sumOf { text -> text?.length ?: 0 }
}

internal fun subtitleTranslationBatches(cueGroups: List<List<Cue>>): List<List<List<Cue>>> {
    val batches = mutableListOf<MutableList<List<Cue>>>()
    var current = mutableListOf<List<Cue>>()
    var currentCharacters = 0

    cueGroups.forEach { cues ->
        val characters = subtitleCharacterCount(cues)
        if (
            current.isNotEmpty() &&
            (current.size >= MAX_BATCH_GROUPS || currentCharacters + characters > MAX_BATCH_CHARACTERS)
        ) {
            batches.add(current)
            current = mutableListOf()
            currentCharacters = 0
        }
        current.add(cues)
        currentCharacters += characters
    }
    if (current.isNotEmpty()) {
        batches.add(current)
    }
    return batches
}

internal fun markSubtitleTitles(text: String): MarkedSubtitleText {
    var titleCount = 0
    val marked = TITLE_CASE_REGEX.replace(text) { match ->
        val previous = text.take(match.range.first).lastOrNull { character -> !character.isWhitespace() }
        if (previous?.isLowerCase() != true && previous?.isDigit() != true) {
            match.value
        } else {
            titleCount++
            "$TITLE_MARKER_START${match.value}$TITLE_MARKER_END"
        }
    }
    return MarkedSubtitleText(marked, titleCount)
}

internal fun restoreSubtitleTitleCase(text: String, expectedTitles: Int): String? {
    if (expectedTitles == 0) {
        return text
    }
    if (TITLE_MARKER_REGEX.findAll(text).count() != expectedTitles) {
        return null
    }
    return TITLE_MARKER_REGEX.replace(text) { title ->
        LOWERCASE_WORD_START_REGEX.replace(title.groupValues[1]) { initial ->
            initial.value.uppercase(Locale.ROOT)
        }
    }
}

internal fun encodeSubtitleTranslationBatch(groups: List<List<String?>>): String {
    return buildString {
        groups.forEachIndexed { groupIndex, cues ->
            cues.forEachIndexed { cueIndex, text ->
                if (text != null) {
                    append(BATCH_MARKER_START)
                    append(groupIndex)
                    append(':')
                    append(cueIndex)
                    append(BATCH_MARKER_END)
                    append('\n')
                    append(text)
                    append('\n')
                }
            }
        }
    }.trim()
}

internal fun decodeSubtitleTranslationBatch(
    translated: String,
    source: List<List<String?>>
): List<List<String?>>? {
    val matches = BATCH_MARKER_REGEX.findAll(translated).toList()
    val expectedCount = source.sumOf { cues -> cues.count { text -> text != null } }
    if (matches.size != expectedCount) {
        return null
    }

    val result = source.map { cues -> MutableList<String?>(cues.size) { null } }
    matches.forEachIndexed { index, match ->
        val groupIndex = match.groupValues[1].toIntOrNull() ?: return null
        val cueIndex = match.groupValues[2].toIntOrNull() ?: return null
        if (source.getOrNull(groupIndex)?.getOrNull(cueIndex) == null) {
            return null
        }
        val end = matches.getOrNull(index + 1)?.range?.first ?: translated.length
        val text = translated.substring(match.range.last + 1, end).trim()
        if (text.isEmpty()) {
            return null
        }
        result[groupIndex][cueIndex] = text
    }
    return result
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
