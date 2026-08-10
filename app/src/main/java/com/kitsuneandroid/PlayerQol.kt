@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

internal data class PlayerSettings(
    val subtitleSize: Float = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
    val backgroundOpacity: Float = 0.65f,
    val subtitleOutline: Boolean = true,
    val seekSeconds: Int = 10,
    val subtitleOffsetMs: Long = 0
)

internal data class SeekFeedback(val forward: Boolean, val seconds: Int, val id: Int)

private const val SUBTITLE_SIZE = "player_subtitle_size"
private const val SUBTITLE_BACKGROUND = "player_subtitle_background"
private const val SUBTITLE_OUTLINE = "player_subtitle_outline"
private const val DOUBLE_TAP_SECONDS = "player_double_tap_seconds"
private const val SUBTITLE_LANGUAGE = "player_subtitle_language"
private const val SUBTITLE_OFFSET = "player_subtitle_offset"
internal fun loadPlayerSettings(preferences: SharedPreferences) = PlayerSettings(
    subtitleSize = preferences.getFloat(SUBTITLE_SIZE, SubtitleView.DEFAULT_TEXT_SIZE_FRACTION).coerceIn(0.03f, 0.10f),
    backgroundOpacity = preferences.getFloat(SUBTITLE_BACKGROUND, 0.65f).coerceIn(0f, 1f),
    subtitleOutline = preferences.getFloat(SUBTITLE_OUTLINE, 2f) > 0f,
    seekSeconds = preferences.getInt(DOUBLE_TAP_SECONDS, 10).coerceIn(5, 30),
    subtitleOffsetMs = preferences.getLong(SUBTITLE_OFFSET, 0).coerceIn(-5_000, 5_000)
)

internal fun loadSubtitleLanguage(preferences: SharedPreferences): String? =
    preferences.getString(SUBTITLE_LANGUAGE, null)

internal fun saveSubtitleLanguage(preferences: SharedPreferences, language: String) {
    preferences.edit().putString(SUBTITLE_LANGUAGE, language).apply()
}

internal fun savePlayerSettings(preferences: SharedPreferences, settings: PlayerSettings) {
    preferences.edit()
        .putFloat(SUBTITLE_SIZE, settings.subtitleSize)
        .putFloat(SUBTITLE_BACKGROUND, settings.backgroundOpacity)
        .putFloat(SUBTITLE_OUTLINE, if (settings.subtitleOutline) 2f else 0f)
        .putInt(DOUBLE_TAP_SECONDS, settings.seekSeconds)
        .putLong(SUBTITLE_OFFSET, settings.subtitleOffsetMs)
        .apply()
}

internal fun seekTarget(current: Long, duration: Long, seconds: Int, forward: Boolean): Long {
    val position = current.coerceAtLeast(0)
    val delta = seconds.coerceIn(1, 60) * 1_000L
    val end = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
    return if (forward) {
        if (position >= end - delta) end else position + delta
    } else {
        (position - delta).coerceAtLeast(0)
    }
}

internal fun shouldOfferEpisodeNavigation(position: Long, duration: Long): Boolean {
    if (duration <= 0) {
        return false
    }

    val finalWindow = minOf(90_000L, duration / 10)
    return position >= duration - finalWindow
}

internal fun PlayerView.installSubtitleRenderer() {
    subtitleView?.apply {
        visibility = View.VISIBLE
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
    }
}

internal fun PlayerView.renderSubtitles(cues: List<Cue>, settings: PlayerSettings) {
    subtitleView?.apply {
        visibility = View.VISIBLE
        setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                (settings.backgroundOpacity * 255).roundToInt().coerceIn(0, 255) shl 24,
                Color.TRANSPARENT,
                if (settings.subtitleOutline) {
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE
                } else {
                    CaptionStyleCompat.EDGE_TYPE_NONE
                },
                Color.BLACK,
                null
            )
        )
        setFractionalTextSize(settings.subtitleSize)
        setCues(cues)
    }
}

@Composable
internal fun PlayerSettingsDialog(
    settings: PlayerSettings,
    onChange: (PlayerSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes do player") },
        text = {
            Column {
                Text("Tamanho da legenda: ${(settings.subtitleSize * 100).roundToInt()}% da tela")
                Slider(
                    value = settings.subtitleSize,
                    onValueChange = { onChange(settings.copy(subtitleSize = it)) },
                    valueRange = 0.03f..0.10f,
                    modifier = Modifier.fillMaxWidth()
                )
                val offsetSeconds = settings.subtitleOffsetMs / 1_000f
                Text("Sincronia da legenda: ${"%+.1f".format(offsetSeconds)} s")
                Slider(
                    value = settings.subtitleOffsetMs.toFloat(),
                    onValueChange = { value ->
                        val rounded = (value / 250).roundToInt() * 250L
                        onChange(settings.copy(subtitleOffsetMs = rounded))
                    },
                    valueRange = -5_000f..5_000f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Opacidade do fundo: ${(settings.backgroundOpacity * 100).roundToInt()}% — 0% remove o fundo")
                Slider(
                    value = settings.backgroundOpacity,
                    onValueChange = { onChange(settings.copy(backgroundOpacity = it)) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.subtitleOutline,
                        onCheckedChange = { enabled ->
                            onChange(settings.copy(subtitleOutline = enabled))
                        }
                    )
                    Text("Contorno preto")
                }
                Text("Toque duplo: ${settings.seekSeconds} segundos")
                Slider(
                    value = settings.seekSeconds.toFloat(),
                    onValueChange = { onChange(settings.copy(seekSeconds = it.roundToInt())) },
                    valueRange = 5f..30f,
                    steps = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Concluir") } },
        dismissButton = { TextButton(onClick = { onChange(PlayerSettings()) }) { Text("Restaurar") } }
    )
}
