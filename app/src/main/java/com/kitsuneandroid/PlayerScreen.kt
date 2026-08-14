@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaSession
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val PLAYER_PREFERENCES = "kitsune"
private const val PREVIOUS_EPISODE_RESTART_THRESHOLD_MS = 5_000L
private const val SUBTITLE_TRANSLATION_ACTIVE = "subtitle_translation_active"
private const val SUBTITLE_RENDERER_TAG = "kitsune-subtitle-renderer"
private const val TORRENT_PIECE_BUCKETS = 120

private data class SubtitleTranslationRequest(
    val trackKey: String,
    val sourceLanguage: String,
    val targetLanguage: String
)

@Composable
@SuppressLint("LocalContextGetResourceValueCall", "ClickableViewAccessibility")
internal fun PlayerScreen(
    uri: Uri,
    download: TorrentDownload?,
    directTitle: String? = null,
    directArtworkUrl: String? = null,
    directSubtitles: List<RemoteSubtitle> = emptyList(),
    directAnimeId: Int? = null,
    directAnimeTitle: String? = null,
    directEpisode: Int? = null,
    offlineEpisodes: List<TorrentDownload>,
    onBack: () -> Unit,
    onEpisodeChange: (TorrentDownload) -> Unit
) {
    val context = LocalContext.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val activity = context as? MainActivity
    val progressKey = "progress:$uri"
    val preferences = remember { context.getSharedPreferences(PLAYER_PREFERENCES, Context.MODE_PRIVATE) }
    val initialPosition = remember(uri) { preferences.getLong(progressKey, 0L) }
    val playbackStartedAt = remember(uri) { AppPerformance.start() }
    val initialPlayerSettings = remember { loadPlayerSettings(preferences) }
    val subtitleTiming = remember(uri) {
        SubtitleTiming(initialPlayerSettings.subtitleOffsetMs)
    }
    val subtitleTimeline = remember(uri) { SubtitleCueTimeline() }
    val preferredSubtitleLanguage = remember {
        loadSubtitleLanguage(preferences) ?: when (loadReleasePreferences(context).language) {
            ReleaseLanguage.PORTUGUESE -> "pt-BR"
            ReleaseLanguage.ENGLISH -> "en"
            ReleaseLanguage.JAPANESE -> "ja"
            else -> null
        }
    }
    val preferredAudioLanguage = remember {
        loadAudioLanguage(preferences) ?: when (loadReleasePreferences(context).language) {
            ReleaseLanguage.PORTUGUESE,
            ReleaseLanguage.DUBBED -> "pt"
            ReleaseLanguage.ENGLISH -> "en"
            ReleaseLanguage.JAPANESE -> "ja"
            else -> null
        }
    }
    var activeSubtitles by remember(uri) {
        mutableStateOf(directSubtitles)
    }
    val subtitleProviderSettings = remember {
        loadSubtitleProviderSettings(context)
    }
    val player = remember(uri) {
        createPlayer(
            context = context,
            uri = uri,
            download = download,
            initialPosition = initialPosition,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            directTitle = directTitle,
            directArtworkUrl = directArtworkUrl,
            directSubtitles = activeSubtitles,
            subtitleTiming = subtitleTiming,
            subtitleTimeline = subtitleTimeline,
            playbackSpeed = initialPlayerSettings.playbackSpeed,
            seekSeconds = initialPlayerSettings.seekSeconds
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
    var sourceCues by remember(player) { mutableStateOf(player.currentCues.cues) }
    var cues by remember(player) { mutableStateOf<List<Cue>>(sourceCues) }
    var settingsOpen by remember { mutableStateOf(false) }
    var audioTracksOpen by remember { mutableStateOf(false) }
    var subtitleTracksOpen by remember { mutableStateOf(false) }
    var subtitleSearchRevision by remember(uri) { mutableIntStateOf(0) }
    var subtitleSearchMessage by remember(uri) { mutableStateOf<String?>(null) }
    var subtitleSearchBusy by remember(uri) { mutableStateOf(false) }
    var waitingForImportedSubtitle by remember(uri) { mutableStateOf(false) }
    var suggestedSubtitleKey by remember(uri) { mutableStateOf<String?>(null) }
    var subtitleTracksRevision by remember(player) { mutableIntStateOf(0) }
    var downloadedTranslationActive by remember(player) { mutableStateOf(false) }
    var liveTranslator by remember(player) { mutableStateOf<LiveSubtitleTranslator?>(null) }
    var translatedTrackKey by remember(player) { mutableStateOf<String?>(null) }
    var translationRequest by remember(player) { mutableStateOf<SubtitleTranslationRequest?>(null) }
    var translationBusy by remember(player) { mutableStateOf(false) }
    var translationPersistent by remember {
        mutableStateOf(preferences.getBoolean(SUBTITLE_TRANSLATION_ACTIVE, false))
    }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var playerError by remember(player) { mutableStateOf<String?>(null) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var hasRenderedFirstFrame by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var speedBeforeBoost by remember(player) { mutableStateOf<Float?>(null) }
    val subtitleRenderState = remember(player) { SubtitleRenderState() }
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
    val downloadedPieces = remember(
        uri.scheme,
        playbackDownload?.infoHash,
        playbackDownload?.downloadedBytes,
        playbackDownload?.status,
        playbackDownload?.videoFileIndex
    ) {
        val activeDownload = playbackDownload?.takeIf { candidate ->
            shouldShowTorrentPieces(uri.scheme, candidate.status)
        }

        if (activeDownload != null) {
            TorrentStreamStore.downloadedFractions(
                activeDownload.infoHash,
                TORRENT_PIECE_BUCKETS
            )
        } else {
            FloatArray(0)
        }
    }
    var previousDownloadedEpisode by remember(uri) { mutableStateOf<TorrentDownload?>(null) }
    var nextDownloadedEpisode by remember(uri) { mutableStateOf<TorrentDownload?>(null) }
    var nextEpisodeTarget by remember(uri) { mutableStateOf<TorrentEpisodeTarget?>(null) }
    var chapters by remember(player) { mutableStateOf<List<MediaChapter>>(emptyList()) }
    var currentPosition by remember(player) { mutableLongStateOf(0L) }
    var nextEpisodePrefetched by remember(uri) { mutableStateOf(false) }
    var feedbackId by remember { mutableIntStateOf(0) }
    DisposableEffect(liveTranslator) {
        val translator = liveTranslator
        onDispose {
            translator?.close()
        }
    }
    LaunchedEffect(translationRequest) {
        val request = translationRequest ?: return@LaunchedEffect
        translationBusy = true
        subtitleSearchMessage = context.getString(
            R.string.preparing_translation_for,
            openSubtitlesLanguageLabel(
                subtitleProviderSettings.language,
                displayLocale
            )
        )
        val translator = LiveSubtitleTranslator(request.sourceLanguage, request.targetLanguage)
        var adopted = false
        try {
            val upcoming = subtitleTimeline.upcoming(
                sourceLanguage = request.sourceLanguage,
                positionUs = currentPlayer.currentPosition * 1_000
            )
            val cuesToPrefetch = listOf(sourceCues) + upcoming
            withContext(Dispatchers.IO) {
                translator.prepare()
                translator.prefetch(cuesToPrefetch)
            }
            liveTranslator = translator
            translatedTrackKey = request.trackKey
            adopted = true
            subtitleSearchMessage = context.getString(R.string.translating_selected_track)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            subtitleSearchMessage = failure.message ?: context.getString(R.string.error_prepare_translation)
        } finally {
            if (!adopted) {
                translator.close()
            }
            translationBusy = false
            translationRequest = null
        }
    }
    LaunchedEffect(sourceCues, liveTranslator) {
        val translator = liveTranslator
        if (translator == null || sourceCues.isEmpty()) {
            cues = subtitleCuesForDisplay(sourceCues, translationPending = false)
            return@LaunchedEffect
        }

        translator.cached(sourceCues)?.let { translatedCues ->
            cues = translatedCues
            return@LaunchedEffect
        }
        cues = subtitleCuesForDisplay(sourceCues, translationPending = true)
        try {
            val upcoming = subtitleTimeline.upcoming(
                sourceLanguage = translator.sourceLanguage,
                positionUs = currentPlayer.currentPosition * 1_000
            )
            val translatedCues = withContext(Dispatchers.IO) {
                translator.translate(sourceCues, upcoming)
            }
            cues = translatedCues
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            cues = subtitleCuesForDisplay(sourceCues, translationPending = true)
            subtitleSearchMessage = failure.message ?: context.getString(R.string.error_translate_line)
        }
    }
    LaunchedEffect(liveTranslator) {
        val translator = liveTranslator ?: return@LaunchedEffect
        while (true) {
            val upcoming = subtitleTimeline.upcoming(
                sourceLanguage = translator.sourceLanguage,
                positionUs = currentPlayer.currentPosition * 1_000
            )
            val cuesToPrefetch = listOf(sourceCues) + upcoming
            withContext(Dispatchers.IO) {
                translator.prefetch(cuesToPrefetch)
            }
            delay(1_000)
        }
    }
    LaunchedEffect(subtitleSearchRevision) {
        if (subtitleSearchRevision == 0) {
            return@LaunchedEffect
        }

        val title = download?.animeTitle
        val episode = download?.episode

        if (title.isNullOrBlank() || episode == null) {
            subtitleSearchMessage = context.getString(R.string.video_missing_anime_episode)
            subtitleSearchBusy = false
            return@LaunchedEffect
        }

        val languageLabel = openSubtitlesLanguageLabel(
            subtitleProviderSettings.language,
            displayLocale
        )
        subtitleSearchMessage = context.getString(R.string.searching_subtitles_in_providers, languageLabel)
        try {
            val videoFile = download
                ?.takeIf { item -> item.status == TorrentStatus.COMPLETED }
                ?.videoPath
                ?.let(::File)
                ?: uri.takeIf { value -> value.scheme == "file" }?.path?.let(::File)
            val videoName = download?.videoPath?.let { path -> File(path).name }
                ?: download?.name
                ?: videoFile?.name
            val videoFps = player.videoFormat?.frameRate?.takeIf { fps -> fps > 0 }
            val subtitle = withContext(Dispatchers.IO) {
                OnlineSubtitles.download(
                    context = context,
                    request = SubtitleSearchRequest(
                        title = title,
                        episode = episode,
                        language = subtitleProviderSettings.language,
                        videoFile = videoFile,
                        videoName = videoName,
                        videoFps = videoFps
                    ),
                    settings = subtitleProviderSettings
                )
            }
            val existingSubtitle = activeSubtitles.firstOrNull { existing ->
                existing.url == subtitle.url
            }
            if (existingSubtitle != null) {
                suggestedSubtitleKey = subtitleTrackOptions(player.currentTracks)
                    .lastOrNull { option ->
                        onlineSubtitleProvider(option.label) != null
                    }
                    ?.key
                subtitleSearchMessage = context.getString(R.string.subtitle_already_available)
                return@LaunchedEffect
            }

            val updatedSubtitles = activeSubtitles.filterNot { existing ->
                isOnlineSubtitle(existing) && existing.language == subtitle.language
            } + subtitle
            val position = player.currentPosition
            val playWhenReady = player.playWhenReady
            activeSubtitles = updatedSubtitles
            waitingForImportedSubtitle = true
            suggestedSubtitleKey = null
            applySubtitleTracks(player, emptySet())
            player.setMediaItem(
                mediaItem(
                    context = context,
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
            subtitleSearchMessage = context.getString(R.string.subtitle_added, languageLabel)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            subtitleSearchMessage = failure.message
                ?: context.getString(R.string.error_search_subtitle)
        } finally {
            subtitleSearchBusy = false
        }
    }
    DisposableEffect(player) {
        var resumeChecked = uri.scheme != "kitsune-stream" || initialPosition <= 0
        var firstFrameRecorded = false
        var playbackWasReady = false
        var bufferingStartedAt: Long? = null
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                sourceCues = cueGroup.cues
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = playbackErrorMessage(context, error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_BUFFERING && playbackWasReady && bufferingStartedAt == null) {
                    bufferingStartedAt = AppPerformance.start()
                }
                if (state == Player.STATE_READY) {
                    bufferingStartedAt?.let { startedAt ->
                        AppPerformance.record(context.getString(R.string.metric_buffer_recovery), startedAt)
                        bufferingStartedAt = null
                    }
                    playbackWasReady = true
                }
                if (state == Player.STATE_ENDED) {
                    preferences.edit().putLong(progressKey, player.duration.coerceAtLeast(0)).apply()
                    VideoHistory.record(
                        context = context,
                        uri = uri,
                        positionMs = player.duration,
                        durationMs = player.duration,
                        ended = true,
                        download = playbackDownload,
                        directTitle = directTitle,
                        directArtworkUrl = directArtworkUrl,
                        directAnimeId = directAnimeId,
                        directAnimeTitle = directAnimeTitle,
                        directEpisode = directEpisode
                    )
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
                    AppPerformance.record(context.getString(R.string.metric_first_frame), playbackStartedAt)
                    firstFrameRecorded = true
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                chapters = readMediaChapters(tracks)
                subtitleTracksRevision++
                val subtitleOptions = subtitleTrackOptions(tracks)
                downloadedTranslationActive = subtitleOptions.any { option ->
                    option.selected && isAutomaticTranslationSubtitle(option.label)
                }
                val targetLanguage = subtitleTranslationLanguage(
                    subtitleProviderSettings.language,
                    subtitleProviderSettings.language
                )
                val selectedOptions = subtitleOptions.filter(SubtitleTrackOption::selected)
                val selectedOption = preferredSubtitleSource(subtitleOptions, targetLanguage)
                if (
                    translationPersistent &&
                    !downloadedTranslationActive &&
                    selectedOptions.size > 1 &&
                    selectedOption != null
                ) {
                    applySubtitleTracks(player, setOf(selectedOption.key))
                    return
                }
                val selectedTrackKey = selectedOption?.key
                if (
                    liveTranslator != null &&
                    selectedTrackKey != translatedTrackKey
                ) {
                    liveTranslator = null
                    translatedTrackKey = null
                }
                val sourceLanguage = selectedOption?.let { option ->
                    subtitleTranslationLanguage(option.language, option.label)
                }
                if (
                    translationPersistent &&
                    !downloadedTranslationActive &&
                    liveTranslator == null &&
                    translationRequest == null &&
                    selectedOption != null &&
                    sourceLanguage != null &&
                    targetLanguage != null &&
                    sourceLanguage != targetLanguage
                ) {
                    translatedTrackKey = selectedOption.key
                    translationRequest = SubtitleTranslationRequest(
                        trackKey = selectedOption.key,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                }
                if (waitingForImportedSubtitle) {
                    val imported = subtitleOptions.lastOrNull { option ->
                        onlineSubtitleProvider(option.label) != null
                    }
                    if (imported != null) {
                        waitingForImportedSubtitle = false
                        suggestedSubtitleKey = imported.key
                    }
                }
                tracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_TEXT) null else (0 until group.length).firstNotNullOfOrNull { index ->
                        group.mediaTrackGroup.getFormat(index).language?.takeIf { group.isTrackSelected(index) }
                    }
                }?.let { saveSubtitleLanguage(preferences, it) }
            }
        }
        player.addListener(listener)
        activity?.videoPlaying = true
        activity?.pauseVideoPlayback = player::pause
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val source = Rect().also { activity.window.decorView.getGlobalVisibleRect(it) }
            val params = PictureInPictureParams.Builder().setSourceRectHint(source)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setAutoEnterEnabled(true)
            activity.setPictureInPictureParams(params.build())
        }
        onDispose {
            player.removeListener(listener)
            activity?.videoPlaying = false
            activity?.pauseVideoPlayback = null
            activity?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
            preferences.edit().putLong(progressKey, player.currentPosition).apply()
            VideoHistory.record(
                context,
                uri,
                player.currentPosition,
                player.duration,
                download = playbackDownload,
                directTitle = directTitle,
                directArtworkUrl = directArtworkUrl,
                directAnimeId = directAnimeId,
                directAnimeTitle = directAnimeTitle,
                directEpisode = directEpisode
            )
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
    LaunchedEffect(
        playbackDownload?.videoPath,
        offlineEpisodes,
        directAnimeId,
        directEpisode
    ) {
        val currentDownload = playbackDownload

        if (currentDownload == null) {
            previousDownloadedEpisode = directAnimeId?.let { animeId ->
                directEpisode?.minus(1)?.takeIf { episode -> episode > 0 }?.let { episode ->
                    offlineEpisode(offlineEpisodes, animeId, episode)
                }
            }
            nextDownloadedEpisode = directAnimeId?.let { animeId ->
                directEpisode?.plus(1)?.let { episode ->
                    offlineEpisode(offlineEpisodes, animeId, episode)
                }
            }
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
                VideoHistory.record(
                    context,
                    uri,
                    currentPosition,
                    player.duration,
                    download = playbackDownload,
                    directTitle = directTitle,
                    directArtworkUrl = directArtworkUrl,
                    directAnimeId = directAnimeId,
                    directAnimeTitle = directAnimeTitle,
                    directEpisode = directEpisode
                )
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
            delay(1_000)
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
    val hasMultipleAudioTracks = audioTrackCount(player.currentTracks) > 1

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
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    installSubtitleRenderer()
                    val subtitleRenderer = SubtitleView(it).apply {
                        tag = SUBTITLE_RENDERER_TAG
                        isClickable = false
                        isFocusable = false
                        installSubtitleRenderer()
                    }
                    findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_content_frame).addView(
                        subtitleRenderer,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
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

                            override fun onLongPress(event: MotionEvent) {
                                if (speedBeforeBoost != null) {
                                    return
                                }

                                val activePlayer = currentPlayer
                                speedBeforeBoost = activePlayer.playbackParameters.speed
                                activePlayer.setPlaybackSpeed(2f)
                            }
                        }
                    )
                    setOnTouchListener { _, event ->
                        detector.onTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            speedBeforeBoost?.let { speed -> currentPlayer.setPlaybackSpeed(speed) }
                            speedBeforeBoost = null
                        }
                        false
                    }
                }
            },
            update = { view ->
                if (view.player !== player) {
                    view.player = player
                }

                view.setFullscreenButtonState(immersive)
                view.resizeMode = playerSettings.videoScale.resizeMode
                view.findViewWithTag<SubtitleView>(SUBTITLE_RENDERER_TAG)
                    ?.renderSubtitles(cues, playerSettings, subtitleRenderState)
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
        if (controlsVisible && downloadedPieces.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp)
                    .padding(bottom = 48.dp)
                    .height(5.dp)
            ) {
                drawRect(Color.Black.copy(alpha = 0.65f))
                val bucketWidth = size.width / downloadedPieces.size

                downloadedPieces.forEachIndexed { index, fraction ->
                    if (fraction <= 0f) {
                        return@forEachIndexed
                    }

                    drawRect(
                        color = Color(0xFF1687D9).copy(alpha = 0.25f + fraction * 0.75f),
                        topLeft = Offset(index * bucketWidth, 0f),
                        size = Size((bucketWidth - 0.5f).coerceAtLeast(1f), size.height)
                    )
                }
            }
        }
        if (downloadedTranslationActive || liveTranslator != null) {
            Text(
                stringResource(R.string.powered_by_google_translate),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = if (controlsVisible) 84.dp else 12.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        if (speedBeforeBoost != null) {
            Text(
                "2×",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
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
        playerError?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.error_playback, error), color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            playerError = null
                            player.prepare()
                            player.play()
                        }
                    ) {
                        Text(stringResource(R.string.try_again), color = Color.White)
                    }
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.close), color = Color.White)
                    }
                }
            }
        }
        if (!hasRenderedFirstFrame && playerError == null && uri.scheme == "kitsune-stream" && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING)) {
            val bufferedDuration = playbackDownload?.let {
                bufferedVideoDurationMs(player.duration, it.streamableBytes, it.videoSizeBytes)
            } ?: 0
            val message = when {
                playbackDownload == null -> stringResource(R.string.restoring_download)
                playbackDownload.status == TorrentStatus.STALLED -> stringResource(R.string.torrent_stopped_receiving)
                playbackDownload.peers == 0 && playbackDownload.downloadSpeed == 0L -> stringResource(R.string.waiting_peers_video)
                bufferedDuration > 0 -> stringResource(
                    R.string.preparing_video_seconds,
                    bufferedDuration / 1_000,
                    formatBytes(playbackDownload.downloadSpeed)
                )
                else -> stringResource(
                    R.string.preparing_video_bytes,
                    formatBytes(playbackDownload.streamableBytes),
                    formatBytes(playbackDownload.downloadSpeed)
                )
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
                    it.episode?.let { number -> Text(stringResource(R.string.episode_number, number), color = Color.White) }
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
                        Text(
                            stringResource(
                                when (activeSegment.kind) {
                                    MediaSegmentKind.INTRO -> R.string.skip_intro
                                    MediaSegmentKind.RECAP -> R.string.skip_recap
                                    MediaSegmentKind.ENDING -> R.string.skip_ending
                                    MediaSegmentKind.CREDITS -> R.string.skip_credits
                                    MediaSegmentKind.PREVIEW -> R.string.skip_preview
                                }
                            ),
                            color = Color.White
                        )
                    }
                }

                if (showEpisodeNavigation) {
                    previousDownloadedEpisode?.let {
                        TextButton(onClick = ::playPreviousEpisode) {
                            Text(stringResource(R.string.previous_episode), color = Color.White)
                        }
                    }

                    if (hasNextEpisode) {
                        TextButton(onClick = ::playNextEpisode) {
                            Text(stringResource(R.string.next_episode), color = Color.White)
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
                TextButton(onClick = onBack) { Text(stringResource(R.string.close), color = Color.White) }
                Row {
                    if (hasMultipleAudioTracks) {
                        TextButton(onClick = { audioTracksOpen = true }) {
                            Text(stringResource(R.string.audio), color = Color.White)
                        }
                    }
                    TextButton(onClick = {
                        subtitleTracksOpen = true
                    }) { Text(stringResource(R.string.subtitles), color = Color.White) }
                    TextButton(onClick = { settingsOpen = true }) { Text(stringResource(R.string.settings_short), color = Color.White) }
                }
            }
        }
    }
    if (settingsOpen) {
        PlayerSettingsDialog(
            settings = playerSettings,
            onChange = {
                playerSettings = it
                player.setPlaybackSpeed(it.playbackSpeed)
                val seekIncrementMs = it.seekSeconds * 1_000L
                player.setSeekBackIncrementMs(seekIncrementMs)
                player.setSeekForwardIncrementMs(seekIncrementMs)
                savePlayerSettings(preferences, it)
            },
            onDismiss = {
                if (playerSettings.subtitleOffsetMs != appliedSubtitleOffsetMs) {
                    val position = player.currentPosition
                    val playWhenReady = player.playWhenReady
                    subtitleTiming.setOffsetMs(playerSettings.subtitleOffsetMs)
                    player.setMediaItem(
                        mediaItem(
                            context = context,
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
    if (audioTracksOpen) {
        AudioTracksDialog(
            player = player,
            tracksRevision = subtitleTracksRevision,
            onSelected = { option ->
                option.language?.let { language -> saveAudioLanguage(preferences, language) }
            },
            onDismiss = { audioTracksOpen = false }
        )
    }
    if (subtitleTracksOpen) {
        SubtitleTracksDialog(
            player = player,
            tracksRevision = subtitleTracksRevision,
            suggestedTrackKey = suggestedSubtitleKey,
            onSearchOnlineSubtitles = if (
                subtitleProviderSettings.hasConfiguredProvider()
            ) {
                {
                    if (!subtitleSearchBusy) {
                        subtitleSearchBusy = true
                        subtitleSearchRevision++
                    }
                }
            } else {
                null
            },
            searchLanguage = openSubtitlesLanguageLabel(
                subtitleProviderSettings.language,
                displayLocale
            ),
            searchMessage = subtitleSearchMessage,
            translationLanguage = openSubtitlesLanguageLabel(
                subtitleProviderSettings.language,
                displayLocale
            ),
            translationBusy = translationBusy,
            translationActive = translationPersistent,
            onTranslationEnabled = { option, sourceLanguage ->
                val targetLanguage = requireNotNull(
                    subtitleTranslationLanguage(
                        subtitleProviderSettings.language,
                        subtitleProviderSettings.language
                    )
                )
                suggestedSubtitleKey = null
                translatedTrackKey = option.key
                translationPersistent = true
                preferences.edit().putBoolean(SUBTITLE_TRANSLATION_ACTIVE, true).apply()
                translationRequest = SubtitleTranslationRequest(
                    trackKey = option.key,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
            },
            onTranslationDisabled = {
                translationPersistent = false
                preferences.edit().putBoolean(SUBTITLE_TRANSLATION_ACTIVE, false).apply()
                liveTranslator = null
                translatedTrackKey = null
                translationRequest = null
                cues = sourceCues
                subtitleSearchMessage = context.getString(R.string.automatic_translation_disabled)
            },
            onSelectionApplied = { selectedTrackKey ->
                suggestedSubtitleKey = null
                if (selectedTrackKey != translatedTrackKey) {
                    liveTranslator = null
                    translatedTrackKey = null
                    translationRequest = null
                }
            },
            onDismiss = { subtitleTracksOpen = false }
        )
    }
}

internal fun safeStreamingResumePosition(saved: Long, duration: Long, contiguousBytes: Long, totalBytes: Long): Long {
    val playableUntil = bufferedVideoDurationMs(duration, contiguousBytes, totalBytes)
    return saved.takeIf { it + 10_000 <= playableUntil } ?: 0
}

private fun isOnlineSubtitle(subtitle: RemoteSubtitle): Boolean {
    return onlineSubtitleProvider(subtitle.label) != null
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
