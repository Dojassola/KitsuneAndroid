@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
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

private const val AUDIO_LANGUAGE = "player_audio_language"
private val AUDIO_DEFAULT_LOCALE = Locale.forLanguageTag("pt-BR")

internal data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val supported: Boolean
) {
    val key = "$groupIndex:$trackIndex"
}

internal fun loadAudioLanguage(preferences: SharedPreferences): String? {
    return preferences.getString(AUDIO_LANGUAGE, null)
}

internal fun saveAudioLanguage(preferences: SharedPreferences, language: String) {
    preferences.edit().putString(AUDIO_LANGUAGE, language).apply()
}

internal fun audioTrackOptions(
    tracks: Tracks,
    displayLocale: Locale = AUDIO_DEFAULT_LOCALE
): List<AudioTrackOption> {
    val options = mutableListOf<AudioTrackOption>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) {
            return@forEachIndexed
        }

        for (trackIndex in 0 until group.length) {
            val format = group.mediaTrackGroup.getFormat(trackIndex)
            options.add(
                AudioTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = audioDisplayLabel(format.label, format.language, options.size + 1, displayLocale),
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(trackIndex)
                )
            )
        }
    }
    return options
}

internal fun audioTrackCount(tracks: Tracks): Int {
    return tracks.groups.sumOf { group ->
        if (group.type == C.TRACK_TYPE_AUDIO) {
            group.length
        } else {
            0
        }
    }
}

internal fun applyAudioTrack(player: Player, selectedKey: String) {
    val builder = player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)

    player.currentTracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) {
            return@forEachIndexed
        }
        val trackIndex = (0 until group.length).firstOrNull { index ->
            "$groupIndex:$index" == selectedKey
        } ?: return@forEachIndexed
        builder.setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
        )
    }
    player.trackSelectionParameters = builder.build()
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

@Composable
internal fun AudioTracksDialog(
    player: Player,
    tracksRevision: Int,
    onSelected: (AudioTrackOption) -> Unit,
    onDismiss: () -> Unit
) {
    val displayLocale = LocalConfiguration.current.locales[0]
    val options = remember(tracksRevision, displayLocale) {
        audioTrackOptions(player.currentTracks, displayLocale)
    }
    var selectedKey by remember(options) {
        mutableStateOf(options.firstOrNull(AudioTrackOption::selected)?.key)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.choose_audio_track))
                if (options.isEmpty()) {
                    Text(stringResource(R.string.no_audio_tracks_available))
                }
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.supported) { selectedKey = option.key }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedKey == option.key,
                            enabled = option.supported,
                            onClick = { selectedKey = option.key }
                        )
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedKey != null,
                onClick = {
                    val option = options.first { item -> item.key == selectedKey }
                    applyAudioTrack(player, option.key)
                    onSelected(option)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
