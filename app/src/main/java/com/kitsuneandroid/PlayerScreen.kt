@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaSession
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val PLAYER_PREFERENCES = "kitsune"
private const val PREVIOUS_EPISODE_RESTART_THRESHOLD_MS = 5_000L

@Composable
internal fun PlayerScreen(
    uri: Uri,
    download: TorrentDownload?,
    directTitle: String? = null,
    directArtworkUrl: String? = null,
    directSubtitles: List<RemoteSubtitle> = emptyList(),
    offlineEpisodes: List<TorrentDownload>,
    onBack: () -> Unit,
    onEpisodeChange: (TorrentDownload) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val progressKey = "progress:$uri"
    val preferences = remember { context.getSharedPreferences(PLAYER_PREFERENCES, Context.MODE_PRIVATE) }
    val initialPosition = remember(uri) { preferences.getLong(progressKey, 0L) }
    val playbackStartedAt = remember(uri) { AppPerformance.start() }
    val initialPlayerSettings = remember { loadPlayerSettings(preferences) }
    val subtitleTiming = remember(uri) {
        SubtitleTiming(initialPlayerSettings.subtitleOffsetMs)
    }
    val preferredSubtitleLanguage = remember {
        loadSubtitleLanguage(preferences) ?: when (loadReleasePreferences(context).language) {
            ReleaseLanguage.PORTUGUESE -> "pt-BR"
            ReleaseLanguage.ENGLISH -> "en"
            ReleaseLanguage.JAPANESE -> "ja"
            else -> null
        }
    }
    var activeSubtitles by remember(uri) {
        mutableStateOf(directSubtitles)
    }
    val openSubtitlesSettings = remember {
        loadOpenSubtitlesSettings(context)
    }
    val player = remember(uri) {
        createPlayer(
            context = context,
            uri = uri,
            download = download,
            initialPosition = initialPosition,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            directTitle = directTitle,
            directArtworkUrl = directArtworkUrl,
            directSubtitles = activeSubtitles,
            subtitleTiming = subtitleTiming
        )
    }
    val mediaSession = remember(player) {
        MediaSession.Builder(context, player)
            .setId(UUID.randomUUID().toString())
            .build()
    }
    val currentPlayer by rememberUpdatedState(player)
    var playerSettings by remember { mutableStateOf(initialPlayerSettings) }
    var appliedSubtitleOffsetMs by remember(uri) {
        mutableLongStateOf(initialPlayerSettings.subtitleOffsetMs)
    }
    val currentPlayerSettings by rememberUpdatedState(playerSettings)
    var cues by remember(player) { mutableStateOf(player.currentCues.cues) }
    var settingsOpen by remember { mutableStateOf(false) }
    var subtitleTracksOpen by remember { mutableStateOf(false) }
    var subtitleSearchRevision by remember(uri) { mutableIntStateOf(0) }
    var subtitleSearchMessage by remember(uri) { mutableStateOf<String?>(null) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var playerError by remember(player) { mutableStateOf<String?>(null) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var hasRenderedFirstFrame by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val storedDownloads = TorrentStore.downloads.toList()
    val storedDownload = uri.getQueryParameter("hash")?.let { infoHash ->
        storedDownloads.firstOrNull { candidate -> candidate.infoHash == infoHash }
    }
    val playbackDownload = if (download != null && storedDownload != null) {
        storedDownload.copy(
            episode = download.episode,
            videoPath = download.videoPath,
            videoFileIndex = download.videoFileIndex
        )
    } else {
        download ?: storedDownload
    }
    var previousDownloadedEpisode by remember(uri) { mutableStateOf<TorrentDownload?>(null) }
    var nextDownloadedEpisode by remember(uri) { mutableStateOf<TorrentDownload?>(null) }
    var nextEpisodeTarget by remember(uri) { mutableStateOf<TorrentEpisodeTarget?>(null) }
    var chapters by remember(player) { mutableStateOf<List<MediaChapter>>(emptyList()) }
    var currentPosition by remember(player) { mutableLongStateOf(0L) }
    var nextEpisodePrefetched by remember(uri) { mutableStateOf(false) }
    var feedbackId by remember { mutableIntStateOf(0) }
    LaunchedEffect(subtitleSearchRevision) {
        if (subtitleSearchRevision == 0) {
            return@LaunchedEffect
        }

        val title = download?.animeTitle
        val episode = download?.episode

        if (title.isNullOrBlank() || episode == null) {
            subtitleSearchMessage = "Este vídeo não tem anime e episódio identificados."
            return@LaunchedEffect
        }

        subtitleSearchMessage = "Buscando legenda em português…"
        try {
            val subtitle = withContext(Dispatchers.IO) {
                OpenSubtitles.downloadPortuguese(
                    context = context,
                    title = title,
                    episode = episode,
                    apiKey = openSubtitlesSettings.apiKey,
                    videoFile = download
                        ?.takeIf { item -> item.status == TorrentStatus.COMPLETED }
                        ?.videoPath
                        ?.let(::File)
                        ?: uri.takeIf { value -> value.scheme == "file" }?.path?.let(::File)
                )
            }
            val updatedSubtitles = (activeSubtitles + subtitle).distinctBy(RemoteSubtitle::url)
            val position = player.currentPosition
            val playWhenReady = player.playWhenReady
            activeSubtitles = updatedSubtitles
            player.setMediaItem(
                mediaItem(
                    uri = uri,
                    download = download,
                    directTitle = directTitle,
                    directArtworkUrl = directArtworkUrl,
                    directSubtitles = updatedSubtitles
                ),
                position
            )
            player.prepare()
            player.playWhenReady = playWhenReady
            subtitleSearchMessage = "Legenda em português adicionada."
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            subtitleSearchMessage = failure.message
                ?: "Não foi possível buscar a legenda."
        }
    }
    DisposableEffect(player) {
        var resumeChecked = uri.scheme != "kitsune-stream" || initialPosition <= 0
        var firstFrameRecorded = false
        var playbackWasReady = false
        var bufferingStartedAt: Long? = null
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = playbackErrorMessage(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_BUFFERING && playbackWasReady && bufferingStartedAt == null) {
                    bufferingStartedAt = AppPerformance.start()
                }
                if (state == Player.STATE_READY) {
                    bufferingStartedAt?.let { startedAt ->
                        AppPerformance.record("Recuperação de buffer", startedAt)
                        bufferingStartedAt = null
                    }
                    playbackWasReady = true
                }
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
                if (!firstFrameRecorded) {
                    AppPerformance.record("Player até primeiro quadro", playbackStartedAt)
                    firstFrameRecorded = true
                }
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
            mediaSession.release()
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
    LaunchedEffect(playbackDownload?.videoPath, offlineEpisodes) {
        val currentDownload = playbackDownload

        if (currentDownload == null) {
            previousDownloadedEpisode = null
            nextDownloadedEpisode = null
            nextEpisodeTarget = null
            return@LaunchedEffect
        }

        previousDownloadedEpisode = previousOfflineEpisode(offlineEpisodes, currentDownload)
        nextDownloadedEpisode = nextOfflineEpisode(offlineEpisodes, currentDownload)
        nextEpisodeTarget = if (nextDownloadedEpisode == null) {
            withContext(Dispatchers.IO) {
                nextTorrentEpisode(context, currentDownload)
            }
        } else {
            null
        }
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
                val currentDownload = playbackDownload

                if (target != null && currentDownload != null) {
                    TorrentService.prefetchEpisode(context, currentDownload, target)
                    nextEpisodePrefetched = true
                }
            }
            delay(500)
        }
    }

    val hasPreviousEpisode = previousDownloadedEpisode != null ||
        shouldRestartCurrentEpisode(currentPosition)
    val hasNextEpisode = nextDownloadedEpisode != null ||
        (playbackDownload != null && nextEpisodeTarget != null)
    val segment = skippableSegmentAt(chapters, currentPosition)
    val ending = endingChapterAt(chapters, currentPosition)
    val showEpisodeNavigation = playbackState == Player.STATE_ENDED ||
        ending != null ||
        shouldOfferEpisodeNavigation(currentPosition, player.duration)

    fun playPreviousEpisode() {
        if (shouldRestartCurrentEpisode(player.currentPosition)) {
            player.seekTo(0)
            return
        }

        val previousEpisode = previousDownloadedEpisode

        if (previousEpisode != null) {
            onEpisodeChange(previousEpisode)
        }
    }

    fun playNextEpisode() {
        val downloadedEpisode = nextDownloadedEpisode

        if (downloadedEpisode != null) {
            onEpisodeChange(downloadedEpisode)
            return
        }

        val currentDownload = playbackDownload
        val torrentTarget = nextEpisodeTarget

        if (currentDownload == null || torrentTarget == null) {
            return
        }

        val nextDownload = TorrentService.switchEpisode(
            context = context,
            download = currentDownload,
            target = torrentTarget
        )
        onEpisodeChange(nextDownload)
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
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        }
                    )
                    val detector = GestureDetector(
                        context,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(event: MotionEvent): Boolean {
                                return true
                            }

                            override fun onDoubleTap(event: MotionEvent): Boolean {
                                val forward = event.x >= width / 2f
                                val seekSeconds = currentPlayerSettings.seekSeconds
                                val activePlayer = currentPlayer
                                activePlayer.seekTo(
                                    seekTarget(
                                        current = activePlayer.currentPosition,
                                        duration = activePlayer.duration,
                                        seconds = seekSeconds,
                                        forward = forward
                                    )
                                )
                                feedbackId++
                                seekFeedback = SeekFeedback(forward, seekSeconds, feedbackId)
                                return true
                            }
                        }
                    )
                    setOnTouchListener { _, event ->
                        detector.onTouchEvent(event)
                        false
                    }
                }
            },
            update = { view ->
                if (view.player !== player) {
                    view.player = player
                }

                view.setFullscreenButtonState(immersive)
                view.renderSubtitles(cues, playerSettings)
                view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                    val markerTimes = chapters.map(MediaChapter::startMs).toLongArray()
                    setAdGroupTimesMs(
                        markerTimes,
                        BooleanArray(markerTimes.size),
                        markerTimes.size
                    )
                }
                view.findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.apply {
                    isEnabled = hasPreviousEpisode
                    alpha = if (hasPreviousEpisode) 1f else 0.35f
                    setOnClickListener {
                        playPreviousEpisode()
                    }
                }
                view.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.apply {
                    isEnabled = hasNextEpisode
                    alpha = if (hasNextEpisode) 1f else 0.35f
                    setOnClickListener {
                        playNextEpisode()
                    }
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
            val bufferedDuration = playbackDownload?.let {
                bufferedVideoDurationMs(player.duration, it.streamableBytes, it.videoSizeBytes)
            } ?: 0
            val message = when {
                playbackDownload == null -> "Restaurando o download…"
                playbackDownload.status == TorrentStatus.STALLED -> "O torrent conectou, mas parou de receber dados."
                playbackDownload.peers == 0 && playbackDownload.downloadSpeed == 0L -> "Aguardando peers para carregar o vídeo…"
                bufferedDuration > 0 -> "Preparando vídeo: ${bufferedDuration / 1_000}s prontos • ${formatBytes(playbackDownload.downloadSpeed)}/s"
                else -> "Preparando vídeo: ${formatBytes(playbackDownload.streamableBytes)} disponíveis • ${formatBytes(playbackDownload.downloadSpeed)}/s"
            }
            AsyncImage(
                model = playbackDownload?.animeCoverPath?.let(::File)?.takeIf(File::exists)
                    ?: playbackDownload?.animeCoverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)))
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                playbackDownload?.let {
                    it.animeTitle?.let { title -> Text(title, color = Color.White, fontWeight = FontWeight.Bold) }
                    it.episode?.let { number -> Text("Episódio $number", color = Color.White) }
                    Spacer(Modifier.height(8.dp))
                }
                Text(message, color = Color.White)
            }
        }
        if (
            segment != null ||
            (showEpisodeNavigation && (hasPreviousEpisode || hasNextEpisode))
        ) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
                    .background(Color.Black.copy(alpha = 0.72f), CircleShape).padding(horizontal = 8.dp)
            ) {
                segment?.let { activeSegment ->
                    TextButton(onClick = { player.seekTo(activeSegment.chapter.endMs) }) {
                        Text(activeSegment.kind.actionLabel, color = Color.White)
                    }
                }

                if (showEpisodeNavigation) {
                    previousDownloadedEpisode?.let {
                        TextButton(onClick = ::playPreviousEpisode) {
                            Text("Episódio anterior", color = Color.White)
                        }
                    }

                    if (hasNextEpisode) {
                        TextButton(onClick = ::playNextEpisode) {
                            Text("Próximo episódio", color = Color.White)
                        }
                    }
                }
            }
        }
        if (controlsVisible) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) { Text("Fechar", color = Color.White) }
                Row {
                    TextButton(onClick = {
                        subtitleTracksOpen = true
                    }) { Text("Legendas", color = Color.White) }
                    TextButton(onClick = { settingsOpen = true }) { Text("Ajustes", color = Color.White) }
                }
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
            onDismiss = {
                if (playerSettings.subtitleOffsetMs != appliedSubtitleOffsetMs) {
                    val position = player.currentPosition
                    val playWhenReady = player.playWhenReady
                    subtitleTiming.setOffsetMs(playerSettings.subtitleOffsetMs)
                    player.setMediaItem(
                        mediaItem(
                            uri = uri,
                            download = download,
                            directTitle = directTitle,
                            directArtworkUrl = directArtworkUrl,
                            directSubtitles = directSubtitles
                        ),
                        position
                    )
                    player.prepare()
                    player.playWhenReady = playWhenReady
                    appliedSubtitleOffsetMs = playerSettings.subtitleOffsetMs
                }
                settingsOpen = false
            }
        )
    }
    if (subtitleTracksOpen) {
        SubtitleTracksDialog(
            player = player,
            onSearchPortuguese = if (
                openSubtitlesSettings.enabled &&
                openSubtitlesSettings.apiKey.isNotBlank()
            ) {
                { subtitleSearchRevision++ }
            } else {
                null
            },
            searchMessage = subtitleSearchMessage,
            onDismiss = { subtitleTracksOpen = false }
        )
    }
}

internal fun safeStreamingResumePosition(saved: Long, duration: Long, contiguousBytes: Long, totalBytes: Long): Long {
    val playableUntil = bufferedVideoDurationMs(duration, contiguousBytes, totalBytes)
    return saved.takeIf { it + 10_000 <= playableUntil } ?: 0
}

internal fun bufferedVideoDurationMs(duration: Long, contiguousBytes: Long, videoBytes: Long): Long {
    if (duration <= 0 || contiguousBytes <= 0 || videoBytes <= 0) {
        return 0
    }

    return (duration.toDouble() * contiguousBytes / videoBytes).toLong().coerceIn(0, duration)
}

internal fun shouldPrefetchNextEpisode(position: Long, duration: Long): Boolean {
    if (duration <= 0) {
        return false
    }

    val prefetchStart = maxOf(
        duration / 4 * 3,
        duration - 5 * 60_000L
    )
    return position >= prefetchStart
}

internal fun shouldRestartCurrentEpisode(position: Long): Boolean {
    return position > PREVIOUS_EPISODE_RESTART_THRESHOLD_MS
}
