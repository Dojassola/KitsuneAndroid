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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

internal fun subtitleTrackOptions(tracks: Tracks): List<SubtitleTrackOption> {
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
                index = options.size + 1
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
    translationEnabled: Boolean,
    translationLanguage: String,
    translationBusy: Boolean,
    onTranslateSelected: (SubtitleTrackOption, String) -> Unit,
    onSelectionApplied: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val options = remember(tracksRevision) {
        subtitleTrackOptions(player.currentTracks)
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
    val selectedOption = options.singleOrNull { option -> option.key in selectedKeys }
    val sourceLanguage = selectedOption?.let { option ->
        subtitleTranslationLanguage(option.language, option.label)
    }
    val targetLanguage = subtitleTranslationLanguage(translationLanguage, translationLanguage)
    val canTranslate = sourceLanguage != null && targetLanguage != null && sourceLanguage != targetLanguage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Legendas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Escolha uma legenda.")
                if (options.isEmpty()) {
                    Text("Nenhuma legenda disponível neste vídeo.")
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
                if (translationEnabled && selectedOption != null) {
                    TextButton(
                        enabled = canTranslate && !translationBusy,
                        onClick = {
                            if (selectedKeys != appliedKeys) {
                                applySubtitleTracks(player, selectedKeys)
                            }
                            onTranslateSelected(selectedOption, requireNotNull(sourceLanguage))
                            onDismiss()
                        }
                    ) {
                        Text(
                            if (translationBusy) {
                                "Preparando tradução…"
                            } else {
                                "Traduzir para $translationLanguage"
                            }
                        )
                    }
                    if (sourceLanguage == null) {
                        Text("Não foi possível identificar o idioma desta faixa.")
                    } else if (sourceLanguage == targetLanguage) {
                        Text("Esta faixa já está em $translationLanguage.")
                    }
                }
                if (onSearchOnlineSubtitles != null) {
                    TextButton(onClick = onSearchOnlineSubtitles) {
                        Text("Buscar $searchLanguage nos provedores")
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
                Text("Aplicar")
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
                Text("Desativar")
            }
        }
    )
}

internal fun subtitleDisplayLabel(
    label: String?,
    language: String?,
    index: Int
): String {
    val labelText = label.trimmedOrNull()
    val languageCode = language.trimmedOrNull()
    val searchableText = subtitleSearchableText(labelText, languageCode)
    val forced = searchableText.contains("forced") || searchableText.contains("forçada")
    val sdh = SDH_PATTERN.containsMatchIn(searchableText) || searchableText.contains("hearing impaired")
    val languageName = subtitleLanguageName(searchableText, languageCode, labelText, forced || sdh)
    val providerName = onlineSubtitleProvider(labelText)

    return formatSubtitleLabel(
        languageName = languageName,
        originalLabel = labelText,
        languageCode = languageCode,
        index = index,
        forced = forced,
        sdh = sdh,
        providerName = providerName
    )
}

internal fun onlineSubtitleProvider(label: String?): String? {
    val normalized = label?.lowercase(Locale.ROOT) ?: return null
    return when {
        normalized.contains("tradução automática") -> "Tradução automática"
        normalized.contains("opensubtitles") -> "OpenSubtitles"
        normalized.contains("subdl") -> "SubDL"
        else -> null
    }
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
    labelIsAccessibilityTag: Boolean
): String? = when {
    searchableText == "ms-ind" -> "Malaio / Indonésio"
    searchableText.contains("traditional") -> "Chinês tradicional"
    PORTUGUESE_PATTERN.containsMatchIn(searchableText) ||
        searchableText.contains("portugu") || searchableText.contains("brazil") -> "Português (Brasil)"
    ENGLISH_PATTERN.containsMatchIn(searchableText) || searchableText.contains("english") -> "Inglês"
    JAPANESE_PATTERN.containsMatchIn(searchableText) || searchableText.contains("japanese") -> "Japonês"
    SPANISH_PATTERN.containsMatchIn(searchableText) || searchableText.contains("spanish") -> "Espanhol"
    else -> localizedSubtitleLanguage(languageCode)
        ?: localizedSubtitleLanguage(label.takeUnless { labelIsAccessibilityTag })
}

private fun formatSubtitleLabel(
    languageName: String?,
    originalLabel: String?,
    languageCode: String?,
    index: Int,
    forced: Boolean,
    sdh: Boolean,
    providerName: String?
): String {
    if (languageName == null) {
        val fallback = when {
            forced -> "Forçada"
            sdh -> "SDH (acessibilidade)"
            originalLabel != null -> originalLabel
            languageCode != null -> languageCode
            else -> "Legenda $index"
        }
        return if (providerName != null && (forced || sdh)) {
            "$fallback • $providerName"
        } else {
            fallback
        }
    }

    val parts = mutableListOf(languageName)
    if (forced) parts += "Forçada" else if (sdh) parts += "SDH"
    providerName?.let(parts::add)
    return parts.joinToString(" • ")
}

private fun localizedSubtitleLanguage(value: String?): String? {
    val raw = value ?: return null
    if (raw.equals("ms-ind", ignoreCase = true)) return "Malaio / Indonésio"
    if (!Regex("^[a-zA-Z]{2,3}(?:[-_][a-zA-Z]{2})?$").matches(raw)) return null
    val locale = Locale.forLanguageTag(raw.replace('_', '-'))
    val display = locale.getDisplayName(PORTUGUESE_LOCALE)
    if (display.isBlank() || display.equals(raw, ignoreCase = true)) return null
    return display.replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(PORTUGUESE_LOCALE) else character.toString()
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
