@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.Tracks
import java.util.Locale

private const val AUDIO_LANGUAGE = "player_audio_language"
private val AUDIO_DEFAULT_LOCALE = Locale.forLanguageTag("pt-BR")

internal fun loadAudioLanguage(preferences: SharedPreferences): String? {
    return preferences.getString(AUDIO_LANGUAGE, null)
}

internal fun saveAudioLanguage(preferences: SharedPreferences, language: String) {
    preferences.edit().putString(AUDIO_LANGUAGE, language).apply()
}

internal fun selectedAudioLanguage(tracks: Tracks): String? {
    tracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_AUDIO) {
            return@forEach
        }

        for (trackIndex in 0 until group.length) {
            if (group.isTrackSelected(trackIndex)) {
                return group.mediaTrackGroup.getFormat(trackIndex).language
            }
        }
    }
    return null
}

internal fun audioDisplayLabel(
    label: String?,
    language: String?,
    index: Int,
    displayLocale: Locale = AUDIO_DEFAULT_LOCALE
): String {
    val rawLanguage = language.trimmedOrNull()
    val languageLabel = rawLanguage?.let { value ->
        val locale = Locale.forLanguageTag(value.replace('_', '-'))
        val displayLanguage = locale.getDisplayLanguage(displayLocale)
            .trimmedOrNull()
            ?.replaceFirstChar { character -> character.titlecase(displayLocale) }
        val displayCountry = locale.getDisplayCountry(displayLocale).trimmedOrNull()
        if (displayLanguage != null && displayCountry != null) {
            "$displayLanguage ($displayCountry)"
        } else {
            displayLanguage
        }
    }
    val rawLabel = label.trimmedOrNull()?.takeUnless { value ->
        value.equals(rawLanguage, ignoreCase = true) || value.equals(languageLabel, ignoreCase = true)
    }
    return listOfNotNull(languageLabel, rawLabel)
        .distinctBy { value -> value.lowercase(displayLocale) }
        .joinToString(" • ")
        .ifBlank { "Áudio $index" }
}
