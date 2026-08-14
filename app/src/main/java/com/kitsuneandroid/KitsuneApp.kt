@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kitsuneandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrowseState(
    val anime: Anime? = null,
    val episode: Episode? = null,
    val showReleases: Boolean = false,
    val releaseEpisode: Int? = null,
    val releaseCandidates: List<ReleaseCandidate>? = null,
    val autoReleaseId: String? = null
)
private data class PendingDownload(
    val anime: Anime,
    val episode: Int?,
    val release: ReleaseCandidate,
    val selectedFiles: List<Int>,
    val videoFile: Int
)

private data class PlaybackRequest(
    val uri: Uri,
    val download: TorrentDownload? = null,
    val title: String? = null,
    val artworkUrl: String? = null,
    val remoteSubtitles: List<RemoteSubtitle> = emptyList(),
    val animeId: Int? = null,
    val animeTitle: String? = null,
    val episode: Int? = null
)

private data class MainTab(val labelResource: Int, val iconResource: Int)

private data class RestoredAppData(
    val hasActiveDownloads: Boolean,
    val favoriteCatalog: List<Anime>,
    val favoriteIds: Set<Int>
)

private val MAIN_TABS = listOf(
    MainTab(R.string.nav_home, R.drawable.nav_home),
    MainTab(R.string.nav_favorites, R.drawable.nav_favorite),
    MainTab(R.string.nav_downloads, R.drawable.nav_download),
    MainTab(R.string.nav_library, R.drawable.nav_library),
    MainTab(R.string.nav_history, R.drawable.nav_history),
    MainTab(R.string.nav_profile, R.drawable.nav_profile)
)

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun KitsuneApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var requestedQuery by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var favoriteCatalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var homeBrowse by remember { mutableStateOf(BrowseState()) }
    var favoriteBrowse by remember { mutableStateOf(BrowseState()) }
    var playbackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var dataRefresh by remember { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val storedDownloads = TorrentStore.downloads.toList()
    val offlineLibraryRevision = storedDownloads.map { download ->
        Triple(
            download.infoHash,
            download.status,
            download.completedFileIndices
        )
    }
    var offlineEpisodes by remember {
        mutableStateOf<List<TorrentDownload>>(emptyList())
    }
    val downloadedAnimeIds = offlineAnimeIds(offlineEpisodes)

    val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            backupBusy = true
            backupMessage = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        UserDataBackup.export(context, uri)
                    }
                    backupMessage = context.getString(R.string.backup_exported)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val failureMessage = failure.message

                    if (failureMessage.isNullOrBlank()) {
                        backupMessage = context.getString(R.string.error_export_backup)
                    } else {
                        backupMessage = failureMessage
                    }
                } finally {
                    backupBusy = false
                }
            }
        }
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupBusy = true
            backupMessage = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        UserDataBackup.restore(context, uri)
                    }
                    favoriteIds = FavoriteRepository.ids(context)
                    favoriteCatalog = FavoriteRepository.items(context)
                    VideoHistory.load(context)
                    refresh++
                    dataRefresh++
                    backupMessage = context.getString(R.string.backup_restored)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val failureMessage = failure.message

                    if (failureMessage.isNullOrBlank()) {
                        backupMessage = context.getString(R.string.error_restore_backup)
                    } else {
                        backupMessage = failureMessage
                    }
                } finally {
                    backupBusy = false
                }
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val download = pendingDownload

        if (download != null) {
            TorrentService.enqueue(
                context = context,
                anime = download.anime,
                episode = download.episode,
                release = download.release,
                selectedFiles = download.selectedFiles,
                videoFile = download.videoFile
            )
        }

        pendingDownload = null
        tab = 2
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers grant access without supporting persisted permissions.
            }
            playbackRequest = PlaybackRequest(uri = uri)
        }
    }

    fun download(anime: Anime, episode: Int?, release: ReleaseCandidate, selectedFiles: List<Int>, videoFile: Int) {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            pendingDownload = PendingDownload(anime, episode, release, selectedFiles, videoFile)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TorrentService.enqueue(context, anime, episode, release, selectedFiles, videoFile)
            tab = 2
        }
    }

    LaunchedEffect(Unit) {
        val restoredState = withContext(Dispatchers.IO) {
            val activeDownloads = TorrentStore.load(context)
            VideoHistory.load(context)
            RestoredAppData(
                hasActiveDownloads = activeDownloads,
                favoriteCatalog = FavoriteRepository.items(context),
                favoriteIds = FavoriteRepository.ids(context)
            )
        }

        favoriteCatalog = restoredState.favoriteCatalog
        favoriteIds = restoredState.favoriteIds

        if (restoredState.hasActiveDownloads) {
            TorrentService.restore(context)
        }
    }

    LaunchedEffect(offlineLibraryRevision) {
        offlineEpisodes = withContext(Dispatchers.IO) {
            offlineLibraryEpisodes(context, storedDownloads)
        }
    }

    UpdateDialog()

    val requestedFavorites = if (tab == 1) {
        favoriteIds
    } else {
        emptySet()
    }

    LaunchedEffect(tab, requestedQuery, requestedFavorites, refresh) {
        if (tab > 1) {
            return@LaunchedEffect
        }

        val cachedItems = withContext(Dispatchers.IO) {
            when {
                tab == 1 -> FavoriteRepository.items(context)
                requestedQuery.isBlank() -> CatalogCache.load(context)
                else -> emptyList()
            }
        }
        if (tab == 1) {
            favoriteCatalog = cachedItems
        } else {
            catalog = cachedItems
        }

        loading = (tab == 0 && catalog.isEmpty()) ||
            (tab == 1 && favoriteCatalog.isEmpty() && favoriteIds.isNotEmpty())
        error = null
        val requestStartedAt = AppPerformance.start()

        try {
            val result = withContext(Dispatchers.IO) {
                if (tab == 0) {
                    AnimeApi.catalog(
                        search = requestedQuery.ifBlank { null },
                        providers = loadCatalogProviders(context),
                        remoteProviders = loadRemoteProviderConfigs(context),
                        onUpdate = { partialCatalog ->
                            if (requestedQuery.isBlank()) {
                                CatalogCache.save(context, partialCatalog)
                            }
                            withContext(Dispatchers.Main) {
                                catalog = partialCatalog
                                loading = false
                            }
                        }
                    )
                } else {
                    AnimeApi.favorites(favoriteIds)
                }
            }

            if (tab == 0) {
                catalog = result
            } else {
                favoriteCatalog = withContext(Dispatchers.IO) {
                    FavoriteRepository.refresh(context, result)
                    FavoriteRepository.items(context)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if ((tab == 0 && catalog.isEmpty()) || (tab == 1 && favoriteCatalog.isEmpty())) {
                val failureMessage = failure.message

                if (failureMessage.isNullOrBlank()) {
                    error = context.getString(R.string.error_load_anime)
                } else {
                    error = failureMessage
                }
            }
        }

        val metricName = if (tab == 0) {
            context.getString(R.string.metric_catalog_load)
        } else {
            context.getString(R.string.metric_favorites_sync)
        }
        AppPerformance.record(metricName, requestStartedAt)
        loading = false
    }

    playbackRequest?.let { playback ->
        PlayerScreen(
            uri = playback.uri,
            download = playback.download,
            directTitle = playback.title,
            directArtworkUrl = playback.artworkUrl,
            directSubtitles = playback.remoteSubtitles,
            directAnimeId = playback.animeId,
            directAnimeTitle = playback.animeTitle,
            directEpisode = playback.episode,
            offlineEpisodes = offlineEpisodes,
            onBack = {
                playbackRequest = null
            },
            onEpisodeChange = { episode ->
                playbackRequest = PlaybackRequest(
                    uri = playbackUri(episode),
                    download = episode
                )
            }
        )
        return
    }

    val browse = when (tab) {
        0 -> homeBrowse
        1 -> favoriteBrowse
        else -> null
    }

    fun updateBrowse(value: BrowseState) {
        if (tab == 0) {
            homeBrowse = value
        } else {
            favoriteBrowse = value
        }
    }

    Scaffold(
        topBar = {
            if (browse?.anime == null) {
                TopAppBar(title = {
                    Column {
                        Text("Kitsune", fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelSmall)
                    }
                })
            }
        },
        bottomBar = {
            NavigationBar {
                MAIN_TABS.forEachIndexed { index, destination ->
                    val destinationLabel = stringResource(destination.labelResource)
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            settingsOpen = false
                        },
                        icon = {
                            Icon(painterResource(destination.iconResource), destinationLabel)
                        },
                        label = { Text(destinationLabel) },
                        alwaysShowLabel = false
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val anime = browse?.anime
            when {
                settingsOpen -> SettingsScreen(
                    refresh = dataRefresh,
                    onBack = { settingsOpen = false }
                )
                anime != null && browse.showReleases -> ReleaseScreen(
                    anime, browse.releaseEpisode, browse.releaseCandidates, browse.autoReleaseId,
                    onBack = { updateBrowse(browse.copy(showReleases = false)) },
                    onDownload = { release, files, video ->
                        updateBrowse(browse.copy(showReleases = false, autoReleaseId = null))
                        download(anime, browse.releaseEpisode, release, files, video)
                    },
                    onPlayDirect = { release ->
                        val directUrl = requireNotNull(release.directUrl)
                        playbackRequest = PlaybackRequest(
                            uri = Uri.parse(directUrl),
                            title = release.title,
                            artworkUrl = anime.cover,
                            remoteSubtitles = release.remoteSubtitles,
                            animeId = anime.id,
                            animeTitle = anime.title,
                            episode = browse.releaseEpisode
                        )
                    }
                )
                anime != null && browse.episode != null -> EpisodeScreen(
                    anime, browse.episode, browse.releaseCandidates,
                    onBack = { updateBrowse(browse.copy(episode = null, releaseCandidates = null, autoReleaseId = null)) },
                    onReleases = { releases, automatic ->
                        updateBrowse(browse.copy(
                            showReleases = true,
                            releaseEpisode = browse.episode.number,
                            releaseCandidates = releases,
                            autoReleaseId = automatic?.id
                        ))
                    }
                )
                anime != null -> AnimeDetails(
                    anime = anime,
                    favorite = anime.id in favoriteIds,
                    offlineEpisodes = offlineEpisodes,
                    onBack = { updateBrowse(BrowseState()) },
                    onFavorite = {
                        FavoriteRepository.set(context, anime, anime.id !in favoriteIds)
                        favoriteIds = FavoriteRepository.ids(context)
                        favoriteCatalog = FavoriteRepository.items(context)
                    },
                    onWatch = { videoPicker.launch(arrayOf("video/*")) },
                    onEpisode = { updateBrowse(browse.copy(episode = it, releaseCandidates = null, autoReleaseId = null)) },
                    onPlayOffline = { download ->
                        playbackRequest = PlaybackRequest(
                            uri = playbackUri(download),
                            download = download
                        )
                    },
                    onReleases = { updateBrowse(browse.copy(showReleases = true, releaseEpisode = it, releaseCandidates = null, autoReleaseId = null)) },
                    onSeason = { updateBrowse(BrowseState(anime = it)) }
                )
                tab == 0 -> {
                    SearchBox(query, { query = it }) { requestedQuery = query.trim() }
                    Catalog(
                        items = catalog,
                        loading = loading,
                        error = error,
                        emptyMessage = stringResource(R.string.no_anime_found),
                        offlineAnimeIds = downloadedAnimeIds,
                        onRetry = { refresh++ },
                        onSelect = { selectedAnime ->
                            homeBrowse = BrowseState(selectedAnime)
                        }
                    )
                }
                tab == 1 -> {
                    Text(stringResource(R.string.my_favorites), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                    Catalog(
                        items = favoriteCatalog,
                        loading = loading,
                        error = error,
                        emptyMessage = stringResource(R.string.no_favorites_yet),
                        offlineAnimeIds = downloadedAnimeIds,
                        onRetry = { refresh++ },
                        onSelect = { selectedAnime ->
                            favoriteBrowse = BrowseState(selectedAnime)
                        }
                    )
                }
                tab == 2 -> DownloadsScreen(
                    onPlay = { download ->
                        playbackRequest = PlaybackRequest(
                            uri = playbackUri(download),
                            download = download
                        )
                    },
                    onPause = { TorrentService.pause(context, it) },
                    onResume = { TorrentService.resume(context, it) },
                    onRemove = { TorrentService.remove(context, it) }
                )
                tab == 3 -> LibraryScreen(
                    episodes = offlineEpisodes,
                    onPlay = { download ->
                        playbackRequest = PlaybackRequest(
                            uri = playbackUri(download),
                            download = download
                        )
                    },
                    onOpenVideo = { videoPicker.launch(arrayOf("video/*")) },
                    onRemove = { download ->
                        TorrentService.removeEpisode(context, download)
                    }
                )
                tab == 4 -> HistoryScreen(
                    onPlay = { stored ->
                        val uri = Uri.parse(stored)
                        scope.launch {
                            val download = withContext(Dispatchers.IO) {
                                offlineLibraryEpisodes(context, TorrentStore.downloads.toList())
                                    .firstOrNull { episode -> episode.videoPath == uri.path }
                            }
                            playbackRequest = PlaybackRequest(
                                uri = download?.let(::playbackUri) ?: uri,
                                download = download
                            )
                        }
                    },
                    onRemove = { VideoHistory.remove(context, it) },
                    onClear = { VideoHistory.clear(context) }
                )
                else -> ProfileScreen(
                    refresh = dataRefresh,
                    backupBusy = backupBusy,
                    backupMessage = backupMessage,
                    onExport = { backupExporter.launch("Kitsune-backup.kitsune-backup") },
                    onRestore = { backupImporter.launch(arrayOf("*/*")) },
                    onOpenSettings = { settingsOpen = true }
                )
            }
        }
    }

    BackHandler(enabled = settingsOpen) {
        settingsOpen = false
    }
}
