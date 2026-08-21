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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
    val favoriteIds: Set<Int>,
    val mediaLists: List<MediaList>
)

private data class CatalogPagingState(
    val nextPage: Int = 2,
    val hasNextPage: Boolean = false,
    val loading: Boolean = false,
    val requestQuery: String = "",
    val requestRefresh: Int = -1,
    val initialized: Boolean = false
)

private val MAIN_TABS = listOf(
    MainTab(R.string.nav_home, R.drawable.nav_home),
    MainTab(R.string.nav_favorites, R.drawable.nav_favorite),
    MainTab(R.string.nav_downloads, R.drawable.nav_download),
    MainTab(R.string.nav_library, R.drawable.nav_library),
    MainTab(R.string.nav_profile, R.drawable.nav_profile)
)

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun KitsuneApp(
    notificationAnimeId: Int? = null,
    onNotificationAnimeConsumed: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var requestedQuery by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var animeCatalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var seriesCatalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var moviesCatalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var catalogSection by rememberSaveable { mutableStateOf(CatalogSection.ANIME) }
    var catalogFilters by remember { mutableStateOf(CatalogFilters()) }
    var homeDiscovery by remember {
        mutableStateOf<Map<HomeSection, List<Anime>>>(emptyMap())
    }
    var homeLoadingSections by remember {
        mutableStateOf(HomeSection.entries.toSet())
    }
    var catalogPaging by remember {
        mutableStateOf(CatalogSection.entries.associateWith { CatalogPagingState() })
    }
    var favoriteCatalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var mediaLists by remember { mutableStateOf<List<MediaList>>(emptyList()) }
    var homeBrowse by remember { mutableStateOf(BrowseState()) }
    var libraryBrowse by remember { mutableStateOf(BrowseState()) }
    var playbackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestorePassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingAutomaticBackupPassword by remember { mutableStateOf<CharArray?>(null) }
    var automaticBackupEnabled by remember { mutableStateOf(AutomaticBackup.enabled(context)) }
    var dataRefresh by remember { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var requestedTab by remember { mutableStateOf<Int?>(null) }
    val animeCatalogState = rememberLazyGridState()
    val seriesCatalogState = rememberLazyGridState()
    val moviesCatalogState = rememberLazyGridState()
    val favoriteCatalogState = rememberLazyGridState()
    val mainPagerState = rememberPagerState(initialPage = tab) {
        MAIN_TABS.size
    }
    val catalog = when (catalogSection) {
        CatalogSection.ANIME -> animeCatalog
        CatalogSection.SERIES -> seriesCatalog
        CatalogSection.MOVIES -> moviesCatalog
    }
    val displayedCatalog = remember(catalog, catalogFilters) {
        // ponytail: filters stay local to preserve battery; move them provider-side only with a shared protocol.
        catalog.filter(catalogFilters::matches)
    }
    val homeCatalogState = when (catalogSection) {
        CatalogSection.ANIME -> animeCatalogState
        CatalogSection.SERIES -> seriesCatalogState
        CatalogSection.MOVIES -> moviesCatalogState
    }
    val currentCatalogPaging = catalogPaging[catalogSection] ?: CatalogPagingState()
    val knownHomeAnime = remember(
        animeCatalog,
        seriesCatalog,
        moviesCatalog,
        favoriteCatalog,
        mediaLists,
        homeDiscovery
    ) {
        mergeCatalogs(
            listOf(
                animeCatalog,
                seriesCatalog,
                moviesCatalog,
                favoriteCatalog,
                mediaLists.flatMap(MediaList::items),
                homeDiscovery.values.flatten()
            )
        )
    }
    val continueWatching = continueWatchingItems(
        history = VideoHistory.items,
        knownAnime = knownHomeAnime
    )

    val offlineLibraryRevision by remember {
        derivedStateOf {
            TorrentStore.downloads.map { download ->
                Triple(
                    download.infoHash,
                    download.status,
                    download.completedFileIndices
                )
            }
        }
    }
    var offlineEpisodes by remember {
        mutableStateOf<List<TorrentDownload>>(emptyList())
    }
    val downloadedAnimeIds = remember(offlineEpisodes) {
        offlineAnimeIds(offlineEpisodes)
    }

    fun openTab(index: Int) {
        requestedTab = index
        tab = index
        settingsOpen = false
        historyOpen = false
    }

    LaunchedEffect(notificationAnimeId) {
        val animeId = notificationAnimeId ?: return@LaunchedEffect
        val selectedAnime = withContext(Dispatchers.IO) {
            MediaListRepository.trackedItems(context)
                .firstOrNull { anime -> anime.id == animeId }
                ?: try {
                    AnimeApi.favorites(setOf(animeId)).firstOrNull()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
        }

        if (selectedAnime != null) {
            playbackRequest = null
            homeBrowse = BrowseState(anime = selectedAnime)
            openTab(0)
        }

        onNotificationAnimeConsumed(animeId)
    }

    LaunchedEffect(requestedTab) {
        val targetPage = requestedTab

        if (targetPage != null) {
            mainPagerState.animateScrollToPage(targetPage)

            if (requestedTab == targetPage) {
                requestedTab = null
            }
        }
    }

    LaunchedEffect(mainPagerState.settledPage, requestedTab) {
        if (requestedTab != null) {
            return@LaunchedEffect
        }

        val settledPage = mainPagerState.settledPage

        if (tab != settledPage) {
            tab = settledPage
        }
    }

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
                    AppErrors.record("backup.export", failure)
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
    val automaticBackupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = pendingAutomaticBackupPassword
        pendingAutomaticBackupPassword = null
        if (uri != null && password != null) {
            try {
                AutomaticBackup.configure(context, uri, password)
                automaticBackupEnabled = true
                backupMessage = context.getString(R.string.automatic_backup_enabled)
            } catch (failure: Exception) {
                backupMessage = failure.message ?: context.getString(R.string.error_export_backup)
            } finally {
                password.fill('\u0000')
            }
        } else {
            password?.fill('\u0000')
        }
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupBusy = true
            backupMessage = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        UserDataBackup.restore(context, uri, pendingRestorePassword)
                    }
                    favoriteIds = FavoriteRepository.ids(context)
                    favoriteCatalog = FavoriteRepository.items(context)
                    mediaLists = MediaListRepository.lists(context)
                    VideoHistory.load(context)
                    refresh++
                    dataRefresh++
                    backupMessage = context.getString(R.string.backup_restored)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    AppErrors.record("backup.restore", failure)
                    val failureMessage = failure.message

                    if (failureMessage.isNullOrBlank()) {
                        backupMessage = context.getString(R.string.error_restore_backup)
                    } else {
                        backupMessage = failureMessage
                    }
                } finally {
                    pendingRestorePassword?.fill('\u0000')
                    pendingRestorePassword = null
                    backupBusy = false
                }
            }
        } else {
            pendingRestorePassword?.fill('\u0000')
            pendingRestorePassword = null
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
        openTab(2)
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
            openTab(2)
        }
    }

    LaunchedEffect(Unit) {
        val restoredState = withContext(Dispatchers.IO) {
            val activeDownloads = TorrentStore.load(context)
            VideoHistory.load(context)
            RestoredAppData(
                hasActiveDownloads = activeDownloads,
                favoriteCatalog = FavoriteRepository.items(context),
                favoriteIds = FavoriteRepository.ids(context),
                mediaLists = MediaListRepository.lists(context)
            )
        }

        favoriteCatalog = restoredState.favoriteCatalog
        favoriteIds = restoredState.favoriteIds
        mediaLists = restoredState.mediaLists

        if (restoredState.hasActiveDownloads) {
            TorrentService.restore(context)
        }

        homeDiscovery = withContext(Dispatchers.IO) {
            CatalogCache.loadHome(context)
        }
        homeLoadingSections = HomeSection.entries.toSet()
        val recommendationSeedId = restoredState.favoriteCatalog
            .firstOrNull()
            ?.id
            ?: withContext(Dispatchers.IO) {
                CatalogCache.load(context).firstOrNull()?.id
            }

        try {
            withContext(Dispatchers.IO) {
                AnimeApi.discovery(recommendationSeedId) { section, items ->
                    if (items.isNotEmpty()) {
                        CatalogCache.saveHome(context, section, items)
                    }
                    withContext(Dispatchers.Main) {
                        if (items.isNotEmpty()) {
                            homeDiscovery = homeDiscovery + (section to items)
                        }
                        homeLoadingSections = homeLoadingSections - section
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AppErrors.record("home.discovery", failure)
        } finally {
            homeLoadingSections = emptySet()
        }
    }

    LaunchedEffect(offlineLibraryRevision) {
        val storedDownloads = TorrentStore.downloads.toList()
        offlineEpisodes = withContext(Dispatchers.IO) {
            offlineLibraryEpisodes(context, storedDownloads)
        }
    }

    UpdateDialog()

    fun displayCatalog(section: CatalogSection, items: List<Anime>) {
        when (section) {
            CatalogSection.ANIME -> animeCatalog = items
            CatalogSection.SERIES -> seriesCatalog = items
            CatalogSection.MOVIES -> moviesCatalog = items
        }
    }

    fun catalogItems(section: CatalogSection): List<Anime> {
        return when (section) {
            CatalogSection.ANIME -> animeCatalog
            CatalogSection.SERIES -> seriesCatalog
            CatalogSection.MOVIES -> moviesCatalog
        }
    }

    fun updateCatalogPaging(
        section: CatalogSection,
        transform: (CatalogPagingState) -> CatalogPagingState
    ) {
        val current = catalogPaging[section] ?: CatalogPagingState()
        catalogPaging = catalogPaging + (section to transform(current))
    }

    LaunchedEffect(requestedQuery, refresh, catalogSection) {
        val requestedSection = catalogSection
        val requestedSearch = requestedQuery
        val existingPaging = catalogPaging[requestedSection] ?: CatalogPagingState()
        if (
            existingPaging.initialized &&
            catalogItems(requestedSection).isNotEmpty() &&
            existingPaging.requestQuery == requestedSearch &&
            existingPaging.requestRefresh == refresh
        ) {
            loading = false
            error = null
            return@LaunchedEffect
        }
        updateCatalogPaging(requestedSection) {
            CatalogPagingState(
                requestQuery = requestedSearch,
                requestRefresh = refresh
            )
        }
        val cachedItems = withContext(Dispatchers.IO) {
            if (requestedSearch.isBlank()) {
                CatalogCache.load(context, requestedSection)
            } else {
                emptyList()
            }
        }
        displayCatalog(requestedSection, cachedItems)

        loading = cachedItems.isEmpty()
        error = null
        val requestStartedAt = AppPerformance.start()

        try {
            val result = withContext(Dispatchers.IO) {
                AnimeApi.catalog(
                    search = requestedSearch.ifBlank { null },
                    page = 1,
                    section = requestedSection,
                    providers = loadCatalogProviders(context),
                    remoteProviders = loadRemoteProviderConfigs(context),
                    onUpdate = { partialCatalog ->
                        if (requestedSearch.isBlank()) {
                            CatalogCache.save(context, partialCatalog.items, requestedSection)
                        }
                        withContext(Dispatchers.Main) {
                            displayCatalog(requestedSection, partialCatalog.items)
                            if (
                                requestedSection == catalogSection &&
                                requestedSearch == requestedQuery
                            ) {
                                loading = false
                            }
                        }
                    }
                )
            }
            val refreshedItems = result.items.ifEmpty { cachedItems }
            displayCatalog(requestedSection, refreshedItems)
            updateCatalogPaging(requestedSection) { paging ->
                paging.copy(
                    hasNextPage = result.hasNextPage,
                    initialized = result.items.isNotEmpty()
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AppErrors.record("catalog.load", failure)
            val failureMessage = failure.message

            if (failureMessage.isNullOrBlank()) {
                error = context.getString(R.string.error_load_anime)
            } else {
                error = failureMessage
            }
        }

        AppPerformance.record(context.getString(R.string.metric_catalog_load), requestStartedAt)
        loading = false
    }

    fun loadNextCatalogPage() {
        val requestedSection = catalogSection
        val paging = catalogPaging[requestedSection] ?: CatalogPagingState()

        if (loading || paging.loading || !paging.hasNextPage) {
            return
        }

        val search = requestedQuery
        val requestedRefresh = refresh
        val requestedPage = paging.nextPage
        val existingItems = catalogItems(requestedSection)
        updateCatalogPaging(requestedSection) { current ->
            current.copy(loading = true)
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AnimeApi.catalog(
                        search = search.ifBlank { null },
                        page = requestedPage,
                        section = requestedSection,
                        providers = loadCatalogProviders(context),
                        remoteProviders = loadRemoteProviderConfigs(context),
                        onUpdate = { partialCatalog ->
                            withContext(Dispatchers.Main) {
                                if (search == requestedQuery && requestedRefresh == refresh) {
                                    displayCatalog(
                                        requestedSection,
                                        mergeCatalogs(listOf(existingItems, partialCatalog.items))
                                    )
                                }
                            }
                        }
                    )
                }

                if (search != requestedQuery || requestedRefresh != refresh) {
                    return@launch
                }

                val mergedItems = mergeCatalogs(listOf(existingItems, result.items))
                displayCatalog(requestedSection, mergedItems)
                updateCatalogPaging(requestedSection) { current ->
                    current.copy(
                        nextPage = requestedPage + 1,
                        hasNextPage = result.hasNextPage,
                        loading = false
                    )
                }

                if (search.isBlank()) {
                    withContext(Dispatchers.IO) {
                        CatalogCache.save(context, mergedItems, requestedSection)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (search == requestedQuery && requestedRefresh == refresh) {
                    updateCatalogPaging(requestedSection) { current ->
                        current.copy(loading = false, hasNextPage = false)
                    }
                }
            } finally {
                if (search == requestedQuery && requestedRefresh == refresh) {
                    updateCatalogPaging(requestedSection) { current ->
                        current.copy(loading = false)
                    }
                }
            }
        }
    }

    fun playHistoryItem(stored: String) {
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
        1,
        3 -> libraryBrowse
        else -> null
    }

    fun updateBrowse(value: BrowseState) {
        if (tab == 0) {
            homeBrowse = value
        } else {
            libraryBrowse = value
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
            NavigationBar(
                modifier = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        var selectedIndex = -1

                        fun selectAt(positionX: Float) {
                            val index = (positionX / size.width * MAIN_TABS.size)
                                .toInt()
                                .coerceIn(MAIN_TABS.indices)

                            if (index != selectedIndex) {
                                selectedIndex = index
                                openTab(index)
                            }
                        }

                        selectAt(down.position.x)

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }

                            if (change != null && change.pressed) {
                                selectAt(change.position.x)
                            }
                        } while (change?.pressed == true)
                    }
                }
            ) {
                MAIN_TABS.forEachIndexed { index, destination ->
                    val destinationLabel = stringResource(destination.labelResource)
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { openTab(index) },
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
                historyOpen -> HistoryScreen(
                    onBack = { historyOpen = false },
                    onPlay = ::playHistoryItem,
                    onRemove = { VideoHistory.remove(context, it) },
                    onClear = { VideoHistory.clear(context) }
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
                    mediaLists = mediaLists,
                    offlineEpisodes = offlineEpisodes,
                    onBack = { updateBrowse(BrowseState()) },
                    onFavorite = {
                        FavoriteRepository.set(context, anime, anime.id !in favoriteIds)
                        favoriteIds = FavoriteRepository.ids(context)
                        favoriteCatalog = FavoriteRepository.items(context)
                    },
                    onListsChanged = {
                        mediaLists = MediaListRepository.lists(context)
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
                else -> HorizontalPager(
                    state = mainPagerState,
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = mainPagerState,
                        pagerSnapDistance = PagerSnapDistance.atMost(1)
                    ),
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> Column(Modifier.fillMaxSize()) {
                            SearchBox(
                                value = query,
                                onValueChange = { query = it },
                                onSearch = {
                                    requestedQuery = query.trim()
                                    refresh++
                                    animeCatalog = emptyList()
                                    seriesCatalog = emptyList()
                                    moviesCatalog = emptyList()
                                    scope.launch {
                                        animeCatalogState.scrollToItem(0)
                                        seriesCatalogState.scrollToItem(0)
                                        moviesCatalogState.scrollToItem(0)
                                    }
                                },
                                items = catalog,
                                filters = catalogFilters,
                                onFiltersChanged = { selected ->
                                    catalogFilters = selected
                                    scope.launch {
                                        homeCatalogState.scrollToItem(0)
                                    }
                                }
                            )
                            CatalogSectionPicker(catalogSection) { selected ->
                                if (catalogSection != selected) {
                                    catalogFilters = CatalogFilters()
                                }
                                catalogSection = selected
                            }
                            Catalog(
                                items = displayedCatalog,
                                state = homeCatalogState,
                                loading = loading,
                                error = error,
                                emptyMessage = stringResource(
                                    if (catalogSection == CatalogSection.MOVIES) {
                                        R.string.no_movies_found
                                    } else {
                                        R.string.no_anime_found
                                    }
                                ),
                                offlineAnimeIds = downloadedAnimeIds,
                                onRetry = { refresh++ },
                                onSelect = { selectedAnime ->
                                    homeBrowse = BrowseState(selectedAnime)
                                },
                                searchQuery = requestedQuery,
                                canLoadMore = catalogFilters.isEmpty &&
                                    !loading &&
                                    currentCatalogPaging.hasNextPage,
                                loadingMore = currentCatalogPaging.loading,
                                onLoadMore = ::loadNextCatalogPage,
                                header = if (
                                    requestedQuery.isBlank() &&
                                    catalogFilters.isEmpty &&
                                    catalogSection == CatalogSection.ANIME
                                ) {
                                    {
                                        HomeDiscoveryHeader(
                                            discovery = homeDiscovery,
                                            loadingSections = homeLoadingSections,
                                            continueWatching = continueWatching,
                                            onSelect = { selectedAnime ->
                                                homeBrowse = BrowseState(selectedAnime)
                                            },
                                            onContinue = { history ->
                                                playHistoryItem(history.uri)
                                            }
                                        )
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                        1 -> Catalog(
                            items = favoriteCatalog,
                            state = favoriteCatalogState,
                            loading = false,
                            error = null,
                            emptyMessage = stringResource(R.string.no_favorites_yet),
                            offlineAnimeIds = downloadedAnimeIds,
                            onRetry = {
                                favoriteCatalog = FavoriteRepository.items(context)
                            },
                            onSelect = { selectedAnime ->
                                libraryBrowse = BrowseState(selectedAnime)
                            }
                        )
                        2 -> DownloadsScreen(
                            onPlay = { download ->
                                playbackRequest = PlaybackRequest(
                                    uri = playbackUri(download),
                                    download = download
                                )
                            },
                            onPause = { TorrentService.pause(context, it) },
                            onResume = { TorrentService.resume(context, it) },
                            onQueueUp = { TorrentService.queueUp(context, it) },
                            onQueueDown = { TorrentService.queueDown(context, it) },
                            onRemove = { TorrentService.remove(context, it) }
                        )
                        3 -> LibraryHubScreen(
                            episodes = offlineEpisodes,
                            mediaLists = mediaLists,
                            offlineAnimeIds = downloadedAnimeIds,
                            onSelect = { selectedAnime ->
                                libraryBrowse = BrowseState(selectedAnime)
                            },
                            onPlay = { download ->
                                playbackRequest = PlaybackRequest(
                                    uri = playbackUri(download),
                                    download = download
                                )
                            },
                            onOpenVideo = { videoPicker.launch(arrayOf("video/*")) },
                            onRemove = { download ->
                                TorrentService.removeEpisode(context, download)
                            },
                            onDataChanged = {
                                favoriteIds = FavoriteRepository.ids(context)
                                favoriteCatalog = FavoriteRepository.items(context)
                                mediaLists = MediaListRepository.lists(context)
                            }
                        )
                        else -> ProfileScreen(
                            refresh = dataRefresh,
                            backupBusy = backupBusy,
                            backupMessage = backupMessage,
                            onExport = { backupExporter.launch("Kitsune-backup.kitsune-backup") },
                            onRestore = { password ->
                                pendingRestorePassword = password.toCharArray()
                                backupImporter.launch(arrayOf("*/*"))
                            },
                            automaticBackupEnabled = automaticBackupEnabled,
                            onConfigureAutomaticBackup = { password ->
                                pendingAutomaticBackupPassword = password.toCharArray()
                                automaticBackupExporter.launch("Kitsune-automatic-backup.kitsune-backup")
                            },
                            onDisableAutomaticBackup = {
                                AutomaticBackup.disable(context)
                                automaticBackupEnabled = false
                                backupMessage = context.getString(R.string.automatic_backup_disabled)
                            },
                            onDataChanged = {
                                favoriteIds = FavoriteRepository.ids(context)
                                favoriteCatalog = FavoriteRepository.items(context)
                                mediaLists = MediaListRepository.lists(context)
                                dataRefresh++
                            },
                            onOpenHistory = { historyOpen = true },
                            onOpenSettings = { settingsOpen = true }
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = settingsOpen) {
        settingsOpen = false
    }
}
