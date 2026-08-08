@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kitsuneandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

@Composable
fun KitsuneApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var requestedQuery by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var favoriteCatalog by remember { mutableStateOf(FavoriteRepository.items(context)) }
    var favoriteIds by remember { mutableStateOf(FavoriteRepository.ids(context)) }
    var homeBrowse by remember { mutableStateOf(BrowseState()) }
    var favoriteBrowse by remember { mutableStateOf(BrowseState()) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var dataRefresh by remember { mutableIntStateOf(0) }

    val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            backupBusy = true
            backupMessage = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { UserDataBackup.export(context, uri) } }
                    .onSuccess { backupMessage = "Backup exportado com sucesso." }
                    .onFailure { backupMessage = it.message ?: "Não foi possível exportar o backup." }
                backupBusy = false
            }
        }
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupBusy = true
            backupMessage = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { UserDataBackup.restore(context, uri) } }
                    .onSuccess {
                        favoriteIds = FavoriteRepository.ids(context)
                        favoriteCatalog = FavoriteRepository.items(context)
                        VideoHistory.load(context)
                        refresh++
                        dataRefresh++
                        backupMessage = "Backup restaurado com sucesso."
                    }
                    .onFailure { backupMessage = it.message ?: "Não foi possível restaurar o backup." }
                backupBusy = false
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let { TorrentService.enqueue(context, it.anime, it.episode, it.release, it.selectedFiles, it.videoFile) }
        pendingDownload = null
        tab = 2
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            videoUri = uri
        }
    }

    fun download(anime: Anime, episode: Int?, release: ReleaseCandidate, selectedFiles: List<Int>, videoFile: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = PendingDownload(anime, episode, release, selectedFiles, videoFile)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TorrentService.enqueue(context, anime, episode, release, selectedFiles, videoFile)
            tab = 2
        }
    }

    LaunchedEffect(Unit) {
        val restoreTorrents = withContext(Dispatchers.IO) {
            val activeDownloads = TorrentStore.load(context)
            VideoHistory.load(context)
            activeDownloads
        }
        if (restoreTorrents) TorrentService.restore(context)
    }

    UpdateDialog()

    LaunchedEffect(tab, requestedQuery, if (tab == 1) favoriteIds else emptySet<Int>(), refresh) {
        if (tab > 1) return@LaunchedEffect
        if (tab == 1) favoriteCatalog = FavoriteRepository.items(context)
        loading = tab == 0 || (favoriteCatalog.isEmpty() && favoriteIds.isNotEmpty())
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                if (tab == 0) AnimeApi.catalog(requestedQuery.ifBlank { null }) else AnimeApi.favorites(favoriteIds)
            }
        }.onSuccess {
            if (tab == 0) catalog = it else {
                FavoriteRepository.refresh(context, it)
                favoriteCatalog = FavoriteRepository.items(context)
            }
        }.onFailure {
            if (tab == 0 || favoriteCatalog.isEmpty()) error = it.message ?: "Não foi possível carregar os animes."
        }
        loading = false
    }

    videoUri?.let { uri ->
        PlayerScreen(uri = uri, onBack = { videoUri = null }, onNext = { videoUri = playbackUri(it) })
        return
    }

    val browse = when (tab) { 0 -> homeBrowse; 1 -> favoriteBrowse; else -> null }
    fun updateBrowse(value: BrowseState) { if (tab == 0) homeBrowse = value else favoriteBrowse = value }

    Scaffold(
        topBar = {
            if (browse?.anime == null) {
                TopAppBar(title = {
                    Column {
                        Text("Kitsune", fontWeight = FontWeight.Bold)
                        Text("Anime no seu Android", style = MaterialTheme.typography.labelSmall)
                    }
                })
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(painterResource(R.drawable.nav_home), "Início") }, label = { Text("Início") }, alwaysShowLabel = false)
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(painterResource(R.drawable.nav_favorite), "Favoritos") }, label = { Text("Favoritos") }, alwaysShowLabel = false)
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(painterResource(R.drawable.nav_download), "Downloads") }, label = { Text("Downloads") }, alwaysShowLabel = false)
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(painterResource(R.drawable.nav_library), "Biblioteca") }, label = { Text("Biblioteca") }, alwaysShowLabel = false)
                NavigationBarItem(tab == 4, { tab = 4 }, { Icon(painterResource(R.drawable.nav_history), "Histórico") }, label = { Text("Histórico") }, alwaysShowLabel = false)
                NavigationBarItem(tab == 5, { tab = 5 }, { Icon(painterResource(R.drawable.nav_profile), "Perfil") }, label = { Text("Perfil") }, alwaysShowLabel = false)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val anime = browse?.anime
            when {
                anime != null && browse.showReleases -> ReleaseScreen(
                    anime, browse.releaseEpisode, browse.releaseCandidates, browse.autoReleaseId,
                    onBack = { updateBrowse(browse.copy(showReleases = false)) },
                    onDownload = { release, files, video ->
                        updateBrowse(browse.copy(showReleases = false, autoReleaseId = null))
                        download(anime, browse.releaseEpisode, release, files, video)
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
                    onBack = { updateBrowse(BrowseState()) },
                    onFavorite = {
                        FavoriteRepository.set(context, anime, anime.id !in favoriteIds)
                        favoriteIds = FavoriteRepository.ids(context)
                        favoriteCatalog = FavoriteRepository.items(context)
                    },
                    onWatch = { videoPicker.launch(arrayOf("video/*")) },
                    onEpisode = { updateBrowse(browse.copy(episode = it, releaseCandidates = null, autoReleaseId = null)) },
                    onReleases = { updateBrowse(browse.copy(showReleases = true, releaseEpisode = it, releaseCandidates = null, autoReleaseId = null)) },
                    onSeason = { updateBrowse(BrowseState(anime = it)) }
                )
                tab == 0 -> {
                    SearchBox(query, { query = it }) { requestedQuery = query.trim() }
                    Catalog(catalog, loading, error, "Nenhum anime encontrado.", { refresh++ }) { homeBrowse = BrowseState(it) }
                }
                tab == 1 -> {
                    Text("Meus favoritos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                    Catalog(favoriteCatalog, loading, error, "Você ainda não adicionou favoritos.", { refresh++ }) { favoriteBrowse = BrowseState(it) }
                }
                tab == 2 -> DownloadsScreen(
                    onPlay = { videoUri = playbackUri(it) },
                    onPause = { TorrentService.pause(context, it) },
                    onResume = { TorrentService.resume(context, it) },
                    onRemove = { TorrentService.remove(context, it) }
                )
                tab == 3 -> LibraryScreen(
                    onPlay = { videoUri = playbackUri(it) },
                    onOpenVideo = { videoPicker.launch(arrayOf("video/*")) },
                    onRemove = { TorrentService.remove(context, it) }
                )
                tab == 4 -> HistoryScreen(
                    onPlay = { stored ->
                        val uri = Uri.parse(stored)
                        videoUri = TorrentStore.downloads.firstOrNull { it.videoPath == uri.path }?.let(::playbackUri) ?: uri
                    },
                    onRemove = { VideoHistory.remove(context, it) }
                )
                else -> ProfileScreen(
                    refresh = dataRefresh,
                    backupBusy = backupBusy,
                    backupMessage = backupMessage,
                    onExport = { backupExporter.launch("Kitsune-backup.kitsune-backup") },
                    onRestore = { backupImporter.launch(arrayOf("*/*")) }
                )
            }
        }
    }
}
