@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

internal data class SubtitleTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val supported: Boolean
) {
    val key = "$groupIndex:$trackIndex"
}

internal fun subtitleTrackOptions(
    tracks: Tracks,
    displayLocale: Locale = PORTUGUESE_LOCALE
): List<SubtitleTrackOption> {
    val options = mutableListOf<SubtitleTrackOption>()

    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) {
            return@forEachIndexed
        }

        for (trackIndex in 0 until group.length) {
            val format = group.mediaTrackGroup.getFormat(trackIndex)
            val label = subtitleDisplayLabel(
                label = format.label,
                language = format.language,
                index = options.size + 1,
                displayLocale = displayLocale
            )
            options.add(
                SubtitleTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = label,
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(trackIndex)
                )
            )
        }
    }

    return options
}

internal fun preferredSubtitleSource(
    options: List<SubtitleTrackOption>,
    targetLanguage: String?
): SubtitleTrackOption? {
    val selected = options.filter { option ->
        option.selected && !isAutomaticTranslationSubtitle(option.label)
    }
    return selected.firstOrNull { option ->
        val language = subtitleTranslationLanguage(option.language, option.label)
        language != null && language != targetLanguage && !option.label.isAuxiliarySubtitle()
    } ?: selected.firstOrNull { option ->
        val language = subtitleTranslationLanguage(option.language, option.label)
        language != null && language != targetLanguage
    }
}

private fun String.isAuxiliarySubtitle(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return listOf("forced", "sign", "song", "placa", "música").any(normalized::contains)
}

internal fun applySubtitleTracks(player: Player, selectedKeys: Set<String>) {
    val builder = player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedKeys.isEmpty())

    player.currentTracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) {
            return@forEachIndexed
        }

        val selectedIndices = (0 until group.length).filter { trackIndex ->
            "$groupIndex:$trackIndex" in selectedKeys
        }

        if (selectedIndices.isNotEmpty()) {
            builder.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, selectedIndices)
            )
        }
    }

    player.trackSelectionParameters = builder.build()
}

@Composable
internal fun SubtitleTracksDialog(
    player: Player,
    tracksRevision: Int,
    suggestedTrackKey: String?,
    onSearchOnlineSubtitles: (() -> Unit)?,
    searchLanguage: String,
    searchMessage: String?,
    translationLanguage: String,
    translationBusy: Boolean,
    translationActive: Boolean,
    onTranslationEnabled: (SubtitleTrackOption, String) -> Unit,
    onTranslationDisabled: () -> Unit,
    onSelectionApplied: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val displayLocale = LocalConfiguration.current.locales[0]
    val options = remember(tracksRevision, displayLocale) {
        subtitleTrackOptions(player.currentTracks, displayLocale)
    }
    val appliedKeys = options
        .filter(SubtitleTrackOption::selected)
        .map(SubtitleTrackOption::key)
        .toSet()
    var selectedKeys by remember(options, suggestedTrackKey) {
        val suggested = suggestedTrackKey?.takeIf { key ->
            options.any { option -> option.key == key }
        }
        mutableStateOf(
            suggested?.let(::setOf)
                ?: options.filter(SubtitleTrackOption::selected).map(SubtitleTrackOption::key).toSet()
        )
    }
    val targetLanguage = subtitleTranslationLanguage(translationLanguage, translationLanguage)
    val selectedOption = preferredSubtitleSource(
        options.map { option -> option.copy(selected = option.key in selectedKeys) },
        targetLanguage
    )
    val sourceLanguage = selectedOption?.let { option ->
        subtitleTranslationLanguage(option.language, option.label)
    }
    val canTranslate = sourceLanguage != null && targetLanguage != null && sourceLanguage != targetLanguage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subtitles)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.choose_subtitle))
                if (options.isEmpty()) {
                    Text(stringResource(R.string.no_subtitles_available))
                }
                options.forEach { option ->
                    val checked = option.key in selectedKeys
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.supported) {
                                selectedKeys = updatedSubtitleSelection(
                                    selectedKeys,
                                    option.key,
                                    !checked
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = option.supported,
                            onCheckedChange = { enabled ->
                                selectedKeys = updatedSubtitleSelection(
                                    selectedKeys,
                                    option.key,
                                    enabled
                                )
                            }
                        )
                        Text(option.label)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.automatic_translation))
                        Text(
                            when {
                                translationBusy -> stringResource(R.string.preparing_translation)
                                translationActive -> stringResource(R.string.translation_active_next_episodes)
                                canTranslate -> stringResource(R.string.translate_to, translationLanguage)
                                selectedOption == null -> stringResource(R.string.select_subtitle_to_translate)
                                sourceLanguage == null -> stringResource(R.string.track_language_unidentified)
                                else -> stringResource(R.string.track_already_in_language, translationLanguage)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = translationActive,
                        enabled = !translationBusy && (translationActive || canTranslate),
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                onTranslationDisabled()
                            } else {
                                val sourceOption = requireNotNull(selectedOption)
                                selectedKeys = setOf(sourceOption.key)
                                applySubtitleTracks(player, selectedKeys)
                                onTranslationEnabled(
                                    sourceOption,
                                    requireNotNull(sourceLanguage)
                                )
                            }
                        }
                    )
                }
                if (selectedOption != null) {
                    if (sourceLanguage == null) {
                        Text(stringResource(R.string.error_identify_track_language))
                    } else if (sourceLanguage == targetLanguage) {
                        Text(stringResource(R.string.track_already_in_language, translationLanguage))
                    }
                }
                if (onSearchOnlineSubtitles != null) {
                    TextButton(onClick = onSearchOnlineSubtitles) {
                        Text(stringResource(R.string.search_subtitle_language_providers, searchLanguage))
                    }
                }
                searchMessage?.let { message ->
                    Text(message)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedKeys != appliedKeys) {
                        applySubtitleTracks(player, selectedKeys)
                    }
                    onSelectionApplied(selectedKeys.singleOrNull())
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    applySubtitleTracks(player, emptySet())
                    onSelectionApplied(null)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.disable))
            }
        }
    )
}

internal fun subtitleDisplayLabel(
    label: String?,
    language: String?,
    index: Int,
    displayLocale: Locale = PORTUGUESE_LOCALE
): String {
    val labelText = label.trimmedOrNull()
    val languageCode = language.trimmedOrNull()
    val searchableText = subtitleSearchableText(labelText, languageCode)
    val forced = searchableText.contains("forced") || searchableText.contains("forçada")
    val sdh = SDH_PATTERN.containsMatchIn(searchableText) || searchableText.contains("hearing impaired")
    val languageName = subtitleLanguageName(
        searchableText,
        languageCode,
        labelText,
        forced || sdh,
        displayLocale
    )
    val providerName = onlineSubtitleProvider(labelText, displayLocale)

    return formatSubtitleLabel(
        languageName = languageName,
        originalLabel = labelText,
        languageCode = languageCode,
        index = index,
        forced = forced,
        sdh = sdh,
        providerName = providerName,
        displayLocale = displayLocale
    )
}

internal fun onlineSubtitleProvider(
    label: String?,
    displayLocale: Locale = PORTUGUESE_LOCALE
): String? {
    val normalized = label?.lowercase(Locale.ROOT) ?: return null
    return when {
        isAutomaticTranslationSubtitle(normalized) -> if (displayLocale.language == "pt") {
            "Tradução automática"
        } else {
            "Automatic translation"
        }
        normalized.contains("opensubtitles") -> "OpenSubtitles"
        normalized.contains("subdl") -> "SubDL"
        else -> null
    }
}

internal fun isAutomaticTranslationSubtitle(label: String?): Boolean {
    val normalized = label?.lowercase(Locale.ROOT) ?: return false
    return normalized.contains("tradução automática") ||
        normalized.contains("automatic translation")
}

private fun subtitleSearchableText(label: String?, language: String?): String {
    return buildString {
        label?.let(::append)
        if (label != null && language != null) append(' ')
        language?.let(::append)
    }.lowercase(Locale.ROOT).replace('_', '-')
}

private fun subtitleLanguageName(
    searchableText: String,
    languageCode: String?,
    label: String?,
    labelIsAccessibilityTag: Boolean,
    displayLocale: Locale
): String? = when {
    searchableText == "ms-ind" -> if (displayLocale.language == "pt") "Malaio / Indonésio" else "Malay / Indonesian"
    searchableText.contains("traditional") -> if (displayLocale.language == "pt") "Chinês tradicional" else "Traditional Chinese"
    PORTUGUESE_PATTERN.containsMatchIn(searchableText) ||
        searchableText.contains("portugu") || searchableText.contains("brazil") -> displayLanguageName("pt-BR", displayLocale)
    ENGLISH_PATTERN.containsMatchIn(searchableText) || searchableText.contains("english") -> displayLanguageName("en", displayLocale)
    JAPANESE_PATTERN.containsMatchIn(searchableText) || searchableText.contains("japanese") -> displayLanguageName("ja", displayLocale)
    SPANISH_PATTERN.containsMatchIn(searchableText) || searchableText.contains("spanish") -> displayLanguageName("es", displayLocale)
    else -> localizedSubtitleLanguage(languageCode, displayLocale)
        ?: localizedSubtitleLanguage(label.takeUnless { labelIsAccessibilityTag }, displayLocale)
}

private fun formatSubtitleLabel(
    languageName: String?,
    originalLabel: String?,
    languageCode: String?,
    index: Int,
    forced: Boolean,
    sdh: Boolean,
    providerName: String?,
    displayLocale: Locale
): String {
    val portuguese = displayLocale.language == "pt"
    if (languageName == null) {
        val fallback = when {
            forced -> if (portuguese) "Forçada" else "Forced"
            sdh -> if (portuguese) "SDH (acessibilidade)" else "SDH (accessibility)"
            originalLabel != null -> originalLabel
            languageCode != null -> languageCode
            else -> if (portuguese) "Legenda $index" else "Subtitle $index"
        }
        return if (providerName != null && (forced || sdh)) {
            "$fallback • $providerName"
        } else {
            fallback
        }
    }

    val parts = mutableListOf(languageName)
    if (forced) parts += if (portuguese) "Forçada" else "Forced" else if (sdh) parts += "SDH"
    providerName?.let(parts::add)
    return parts.joinToString(" • ")
}

private fun localizedSubtitleLanguage(value: String?, displayLocale: Locale): String? {
    val raw = value ?: return null
    if (raw.equals("ms-ind", ignoreCase = true)) {
        return if (displayLocale.language == "pt") "Malaio / Indonésio" else "Malay / Indonesian"
    }
    if (!Regex("^[a-zA-Z]{2,3}(?:[-_][a-zA-Z]{2})?$").matches(raw)) return null
    val locale = Locale.forLanguageTag(raw.replace('_', '-'))
    val display = locale.getDisplayName(displayLocale)
    if (display.isBlank() || display.equals(raw, ignoreCase = true)) return null
    return display.replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(displayLocale) else character.toString()
    }
}

private fun displayLanguageName(languageTag: String, displayLocale: Locale): String {
    return Locale.forLanguageTag(languageTag)
        .getDisplayName(displayLocale)
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(displayLocale) else character.toString()
        }
}

private val PORTUGUESE_LOCALE = Locale.forLanguageTag("pt-BR")
private val SDH_PATTERN = Regex("(?:^|[^a-z])sdh(?:[^a-z]|$)")
private val PORTUGUESE_PATTERN = Regex("(?:^|[^a-z])(?:pt|por)(?:-br)?(?:[^a-z]|$)")
private val ENGLISH_PATTERN = Regex("(?:^|[^a-z])(?:en|eng)(?:[^a-z]|$)")
private val JAPANESE_PATTERN = Regex("(?:^|[^a-z])(?:ja|jpn)(?:[^a-z]|$)")
private val SPANISH_PATTERN = Regex("(?:^|[^a-z])(?:es|spa)(?:[^a-z]|$)")

internal fun updatedSubtitleSelection(
    current: Set<String>,
    key: String,
    enabled: Boolean
): Set<String> {
    if (!enabled) {
        return current - key
    }

    return setOf(key)
}
