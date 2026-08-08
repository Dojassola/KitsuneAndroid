@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val PLAYER_PREFERENCES = "kitsune"

@Composable
internal fun PlayerScreen(uri: Uri, onBack: () -> Unit, onNext: (TorrentDownload) -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val progressKey = "progress:$uri"
    val preferences = remember { context.getSharedPreferences(PLAYER_PREFERENCES, Context.MODE_PRIVATE) }
    val initialPosition = remember(uri) { preferences.getLong(progressKey, 0L) }
    val preferredSubtitleLanguage = remember {
        loadSubtitleLanguage(preferences) ?: when (loadReleasePreferences(context).language) {
            ReleaseLanguage.PORTUGUESE -> "pt-BR"
            ReleaseLanguage.ENGLISH -> "en"
            ReleaseLanguage.JAPANESE -> "ja"
            else -> null
        }
    }
    val player = remember(uri) {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(KitsuneDataSourceFactory(context)))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 15_000, 0, 250)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build().apply {
            preferredSubtitleLanguage?.let { language ->
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage(language)
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
            }
            setMediaItem(mediaItem(uri), if (uri.scheme == "kitsune-stream") 0 else initialPosition)
            prepare()
            playWhenReady = true
        }
    }
    var playerSettings by remember { mutableStateOf(loadPlayerSettings(preferences)) }
    var cues by remember(player) { mutableStateOf(player.currentCues.cues) }
    var settingsOpen by remember { mutableStateOf(false) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var playbackState by remember { mutableIntStateOf(player.playbackState) }
    var hasRenderedFirstFrame by remember(player) { mutableStateOf(false) }
    val streamingDownload = uri.getQueryParameter("hash")?.let { hash -> TorrentStore.downloads.firstOrNull { it.infoHash == hash } }
    var nextEpisodeTarget by remember(uri) { mutableStateOf<TorrentEpisodeTarget?>(null) }
    var chapters by remember(player) { mutableStateOf<List<MediaChapter>>(emptyList()) }
    var currentPosition by remember(player) { mutableLongStateOf(0L) }
    var nextEpisodePrefetched by remember(uri) { mutableStateOf(false) }
    var feedbackId by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        var resumeChecked = uri.scheme != "kitsune-stream" || initialPosition <= 0
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = playbackErrorMessage(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_ENDED) {
                    preferences.edit().putLong(progressKey, player.duration.coerceAtLeast(0)).apply()
                    VideoHistory.record(context, uri, player.duration, player.duration, ended = true)
                }
                if (state == Player.STATE_READY && !resumeChecked) {
                    resumeChecked = true
                    val download = uri.getQueryParameter("hash")?.let(TorrentStore::get)
                    safeStreamingResumePosition(
                        initialPosition, player.duration,
                        download?.streamableBytes ?: 0, download?.videoSizeBytes ?: 0
                    ).takeIf { it > 0 }?.let(player::seekTo)
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                chapters = readMediaChapters(tracks)
                tracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_TEXT) null else (0 until group.length).firstNotNullOfOrNull { index ->
                        group.mediaTrackGroup.getFormat(index).language?.takeIf { group.isTrackSelected(index) }
                    }
                }?.let { saveSubtitleLanguage(preferences, it) }
            }
        }
        player.addListener(listener)
        activity?.videoPlaying = true
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val source = Rect().also { activity.window.decorView.getGlobalVisibleRect(it) }
            val params = PictureInPictureParams.Builder().setSourceRectHint(source)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setAutoEnterEnabled(true)
            activity.setPictureInPictureParams(params.build())
        }
        onDispose {
            player.removeListener(listener)
            activity?.videoPlaying = false
            activity?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
            preferences.edit().putLong(progressKey, player.currentPosition).apply()
            VideoHistory.record(context, uri, player.currentPosition, player.duration)
            player.release()
        }
    }
    BackHandler { if (immersive) immersive = false else onBack() }
    LaunchedEffect(immersive, activity) {
        activity?.let {
            val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(seekFeedback?.id) {
        if (seekFeedback != null) {
            delay(700)
            seekFeedback = null
        }
    }
    LaunchedEffect(streamingDownload?.videoFileIndex, streamingDownload?.episode) {
        nextEpisodeTarget = streamingDownload?.let { withContext(Dispatchers.IO) { TorrentContent.nextEpisode(context, it) } }
    }
    LaunchedEffect(player, nextEpisodeTarget?.videoFileIndex) {
        var lastSavedPosition = player.currentPosition
        while (true) {
            currentPosition = player.currentPosition
            if (kotlin.math.abs(currentPosition - lastSavedPosition) >= 10_000) {
                preferences.edit().putLong(progressKey, currentPosition).apply()
                VideoHistory.record(context, uri, currentPosition, player.duration)
                lastSavedPosition = currentPosition
            }
            if (!nextEpisodePrefetched && shouldPrefetchNextEpisode(currentPosition, player.duration)) {
                val target = nextEpisodeTarget
                val download = uri.getQueryParameter("hash")?.let(TorrentStore::get)
                if (target != null && download != null) {
                    TorrentService.prefetchEpisode(context, download, target)
                    nextEpisodePrefetched = true
                }
            }
            delay(500)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).then(
            if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars)
        )
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    keepScreenOn = true
                    setKeepContentOnPlayerReset(true)
                    installSubtitleOverlay()
                    setFullscreenButtonClickListener { immersive = it }
                }
            },
            update = { view ->
                view.setFullscreenButtonState(immersive)
                view.renderSubtitles(cues, playerSettings)
                val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent) = true

                    override fun onDoubleTap(event: MotionEvent): Boolean {
                        val forward = event.x >= view.width / 2f
                        player.seekTo(seekTarget(player.currentPosition, player.duration, playerSettings.seekSeconds, forward))
                        feedbackId++
                        seekFeedback = SeekFeedback(forward, playerSettings.seekSeconds, feedbackId)
                        return true
                    }
                })
                view.setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        seekFeedback?.let { feedback ->
            Box(
                Modifier
                    .align(if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(if (feedback.forward) "+${feedback.seconds}s" else "-${feedback.seconds}s", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        playerError?.let {
            Text(
                "Não foi possível reproduzir: $it",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f)).padding(16.dp)
            )
        }
        if (!hasRenderedFirstFrame && playerError == null && uri.scheme == "kitsune-stream" && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING)) {
            val bufferedDuration = streamingDownload?.let {
                bufferedVideoDurationMs(player.duration, it.streamableBytes, it.videoSizeBytes)
            } ?: 0
            val message = when {
                streamingDownload == null -> "Restaurando o download…"
                streamingDownload.status == TorrentStatus.STALLED -> "O torrent conectou, mas parou de receber dados."
                streamingDownload.peers == 0 && streamingDownload.downloadSpeed == 0L -> "Aguardando peers para carregar o vídeo…"
                bufferedDuration > 0 -> "Preparando vídeo: ${bufferedDuration / 1_000}s prontos • ${formatBytes(streamingDownload.downloadSpeed)}/s"
                else -> "Preparando vídeo: ${formatBytes(streamingDownload.streamableBytes)} disponíveis • ${formatBytes(streamingDownload.downloadSpeed)}/s"
            }
            AsyncImage(
                model = streamingDownload?.animeCoverPath?.let(::File)?.takeIf(File::exists)
                    ?: streamingDownload?.animeCoverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)))
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                streamingDownload?.let {
                    it.animeTitle?.let { title -> Text(title, color = Color.White, fontWeight = FontWeight.Bold) }
                    it.episode?.let { number -> Text("Episódio $number", color = Color.White) }
                    Spacer(Modifier.height(8.dp))
                }
                Text(message, color = Color.White)
            }
        }
        if (introChapterAt(chapters, currentPosition) != null || (streamingDownload != null && nextEpisodeTarget != null)) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
                    .background(Color.Black.copy(alpha = 0.72f), CircleShape).padding(horizontal = 8.dp)
            ) {
                introChapterAt(chapters, currentPosition)?.let { intro ->
                    TextButton(onClick = { player.seekTo(intro.endMs) }) { Text("Pular abertura", color = Color.White) }
                }
                if (streamingDownload != null && nextEpisodeTarget != null) {
                    TextButton(onClick = {
                        onNext(TorrentService.switchEpisode(context, streamingDownload, requireNotNull(nextEpisodeTarget)))
                    }) { Text("Próximo episódio", color = Color.White) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Fechar", color = Color.White) }
            Row {
                TextButton(onClick = {
                    TrackSelectionDialogBuilder(context, "Legendas", player, C.TRACK_TYPE_TEXT)
                        .setShowDisableOption(true)
                        .build()
                        .show()
                }) { Text("Legendas", color = Color.White) }
                TextButton(onClick = { settingsOpen = true }) { Text("Ajustes", color = Color.White) }
            }
        }
    }
    if (settingsOpen) {
        PlayerSettingsDialog(
            settings = playerSettings,
            onChange = {
                playerSettings = it
                savePlayerSettings(preferences, it)
            },
            onDismiss = { settingsOpen = false }
        )
    }
}

internal fun safeStreamingResumePosition(saved: Long, duration: Long, contiguousBytes: Long, totalBytes: Long): Long {
    val playableUntil = bufferedVideoDurationMs(duration, contiguousBytes, totalBytes)
    return saved.takeIf { it + 10_000 <= playableUntil } ?: 0
}

internal fun bufferedVideoDurationMs(duration: Long, contiguousBytes: Long, videoBytes: Long): Long {
    if (duration <= 0 || contiguousBytes <= 0 || videoBytes <= 0) return 0
    return (duration.toDouble() * contiguousBytes / videoBytes).toLong().coerceIn(0, duration)
}

internal fun shouldPrefetchNextEpisode(position: Long, duration: Long): Boolean =
    duration > 0 && position >= maxOf(duration / 4 * 3, duration - 5 * 60_000L)

private fun mediaItem(uri: Uri): MediaItem {
    val subtitles = localVideoFile(uri)?.takeIf { uri.scheme == "file" }?.let { video ->
        video.parentFile?.walkTopDown()?.maxDepth(2)?.filter { it.isFile && it.extension.lowercase() in setOf("srt", "vtt", "ass", "ssa") }
            ?.take(8)?.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle))
                    .setMimeType(when (subtitle.extension.lowercase()) {
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    })
                    .setLanguage(if (subtitle.name.contains(Regex("pt[-_. ]?br", RegexOption.IGNORE_CASE))) "pt-BR" else null)
                    .setLabel(subtitle.nameWithoutExtension)
                    .build()
            }?.toList().orEmpty()
    }.orEmpty()
    return MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subtitles).build()
}

internal fun playbackErrorMessage(error: androidx.media3.common.PlaybackException): String = when (error.errorCode) {
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "O perfil de vídeo não é compatível com os decodificadores deste aparelho. Tente outra opção H.264/AVC 8-bit em 720p ou 1080p."
    else -> error.cause?.message ?: error.message ?: "Falha ao reproduzir o vídeo."
}
