@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

internal data class SubtitleTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
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
            builder.addOverride(
                TrackSelectionOverride(group.mediaTrackGroup, selectedIndices)
            )
        }
    }

    player.trackSelectionParameters = builder.build()
}

@Composable
internal fun SubtitleTracksDialog(
    player: Player,
    onSearchPortuguese: (() -> Unit)?,
    searchMessage: String?,
    onDismiss: () -> Unit
) {
    val options = remember(player.currentTracks) {
        subtitleTrackOptions(player.currentTracks)
    }
    var selectedKeys by remember(options) {
        mutableStateOf(options.filter(SubtitleTrackOption::selected).map(SubtitleTrackOption::key).toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Legendas") },
        text = {
            Column {
                Text("Escolha até duas legendas.")
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
                if (onSearchPortuguese != null) {
                    TextButton(onClick = onSearchPortuguese) {
                        Text("Buscar português no OpenSubtitles")
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
                    applySubtitleTracks(player, selectedKeys)
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
    val raw = label?.takeIf(String::isNotBlank)
        ?: language?.takeIf(String::isNotBlank)
        ?: return "Legenda $index"
    val normalized = raw.lowercase().replace('_', '-')
    val source = " • OpenSubtitles".takeIf { normalized.contains("opensubtitles") }.orEmpty()

    return when {
        Regex("(?:^|[^a-z])(?:pt|por)(?:-br)?(?:[^a-z]|$)").containsMatchIn(normalized) ||
            normalized.contains("portugu") || normalized.contains("brazil") -> "Português (Brasil)$source"
        Regex("(?:^|[^a-z])(?:en|eng)(?:[^a-z]|$)").containsMatchIn(normalized) ||
            normalized.contains("english") -> "Inglês$source"
        Regex("(?:^|[^a-z])(?:ja|jpn)(?:[^a-z]|$)").containsMatchIn(normalized) ||
            normalized.contains("japanese") -> "Japonês$source"
        Regex("(?:^|[^a-z])(?:es|spa)(?:[^a-z]|$)").containsMatchIn(normalized) ||
            normalized.contains("spanish") -> "Espanhol$source"
        else -> raw
    }
}

internal fun updatedSubtitleSelection(
    current: Set<String>,
    key: String,
    enabled: Boolean
): Set<String> {
    if (!enabled) {
        return current - key
    }

    if (key in current) {
        return current
    }

    return (current + key).toList().takeLast(2).toSet()
}
