@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

internal fun createPlayer(
    context: Context,
    uri: Uri,
    download: TorrentDownload?,
    initialPosition: Long,
    preferredAudioLanguage: String?,
    preferredSubtitleLanguage: String?,
    directTitle: String?,
    directArtworkUrl: String?,
    directSubtitles: List<RemoteSubtitle>,
    subtitleTiming: SubtitleTiming,
    subtitleTimeline: SubtitleCueTimeline,
    playbackSpeed: Float
): ExoPlayer {
    val player = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(KitsuneDataSourceFactory(context))
                .setSubtitleParserFactory(
                    OffsetSubtitleParserFactory(subtitleTiming, subtitleTimeline)
                )
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 15_000, 0, 250)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()

    player.setAudioAttributes(AudioAttributes.DEFAULT, true)
    player.setHandleAudioBecomingNoisy(true)
    player.setPlaybackSpeed(playbackSpeed)

    val trackParameters = player.trackSelectionParameters.buildUpon()
    if (preferredAudioLanguage != null) {
        trackParameters.setPreferredAudioLanguage(preferredAudioLanguage)
    }
    if (preferredSubtitleLanguage != null) {
        trackParameters
            .setPreferredTextLanguage(preferredSubtitleLanguage)
            .setSelectUndeterminedTextLanguage(true)
    }
    player.trackSelectionParameters = trackParameters.build()

    val startPosition = if (uri.scheme == "kitsune-stream") {
        0
    } else {
        initialPosition
    }
    player.setMediaItem(
        mediaItem(context, uri, download, directTitle, directArtworkUrl, directSubtitles),
        startPosition
    )
    player.prepare()
    player.playWhenReady = true
    return player
}

internal fun mediaItem(
    context: Context,
    uri: Uri,
    download: TorrentDownload?,
    directTitle: String?,
    directArtworkUrl: String?,
    directSubtitles: List<RemoteSubtitle>
): MediaItem {
    val subtitles = localSubtitleConfigurations(uri) +
        remoteSubtitleConfigurations(directSubtitles)
    val title = directTitle ?: download?.episode?.let { episode ->
        "${download.animeTitle ?: download.name} • ${context.getString(R.string.episode_number, episode)}"
    } ?: download?.animeTitle
        ?: localVideoFile(uri)?.nameWithoutExtension
        ?: context.getString(R.string.video)
    val artwork = download?.animeCoverPath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.let(Uri::fromFile)
        ?: download?.animeCoverUrl?.let(Uri::parse)
        ?: directArtworkUrl?.let(Uri::parse)
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(download?.animeTitle)
        .setArtworkUri(artwork)
        .build()

    return MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(metadata)
        .setSubtitleConfigurations(subtitles)
        .build()
}

private fun remoteSubtitleConfigurations(
    subtitles: List<RemoteSubtitle>
): List<MediaItem.SubtitleConfiguration> {
    return subtitles.map { subtitle ->
        val uri = Uri.parse(subtitle.url)
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(subtitleMimeType(File(uri.path.orEmpty())))
            .setLanguage(subtitle.language)
            .setLabel(subtitle.label)
            .build()
    }
}

private fun localSubtitleConfigurations(
    uri: Uri
): List<MediaItem.SubtitleConfiguration> {
    if (uri.scheme != "file") {
        return emptyList()
    }

    val video = localVideoFile(uri) ?: return emptyList()
    val directory = video.parentFile ?: return emptyList()
    val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa")

    return directory.walkTopDown()
        .maxDepth(2)
        .filter { file ->
            file.isFile && file.extension.lowercase() in subtitleExtensions
        }
        .take(8)
        .map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle))
                .setMimeType(subtitleMimeType(subtitle))
                .setLanguage(subtitleLanguage(subtitle))
                .setLabel(subtitle.nameWithoutExtension)
                .build()
        }
        .toList()
}

private fun subtitleMimeType(file: File): String {
    return when (file.extension.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private fun subtitleLanguage(file: File): String? {
    val portuguesePattern = Regex("pt[-_. ]?br", RegexOption.IGNORE_CASE)
    if (file.name.contains(portuguesePattern)) {
        return "pt-BR"
    }
    return null
}

internal fun playbackErrorMessage(context: Context, error: PlaybackException): String {
    if (isDecoderPlaybackError(error.errorCode)) {
        return context.getString(R.string.playback_decoder_unsupported)
    }

    return error.cause?.message
        ?: error.message
        ?: context.getString(R.string.playback_failed)
}

internal fun isDecoderPlaybackError(errorCode: Int): Boolean {
    return errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
}
