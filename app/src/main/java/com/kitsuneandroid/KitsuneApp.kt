@file:androidx.media3.common.util.UnstableApi
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kitsuneandroid

import android.Manifest
import android.app.PictureInPictureParams
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

private const val PREFS = "kitsune"
private const val FAVORITES = "favorites"
private const val PROFILE_NAME = "profile_name"
private const val PROFILE_AVATAR = "profile_avatar"
private const val RELEASE_LANGUAGE = "release_language"
private const val RELEASE_RESOLUTION = "release_resolution"
private const val IGNORED_UPDATE = "ignored_update_version"
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
    var favoriteCatalog by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf(loadFavoriteIds(context)) }
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
                        favoriteIds = loadFavoriteIds(context)
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
        withContext(Dispatchers.IO) {
            TorrentStore.load(context)
            VideoHistory.load(context)
        }
        TorrentService.restore(context)
    }

    UpdateDialog()

    LaunchedEffect(tab, requestedQuery, if (tab == 1) favoriteIds else emptySet<Int>(), refresh) {
        if (tab > 1) return@LaunchedEffect
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                if (tab == 0) AnimeApi.catalog(requestedQuery.ifBlank { null }) else AnimeApi.favorites(favoriteIds)
            }
        }.onSuccess {
            if (tab == 0) catalog = it else favoriteCatalog = it
        }.onFailure {
            error = it.message ?: "Não foi possível carregar os animes."
        }
        loading = false
    }

    videoUri?.let { uri ->
        PlayerScreen(uri = uri, onBack = { videoUri = null })
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
                        favoriteIds = if (anime.id in favoriteIds) favoriteIds - anime.id else favoriteIds + anime.id
                        saveFavoriteIds(context, favoriteIds)
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
                    onPlay = { videoUri = Uri.parse(it) },
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

@Composable
private fun ProfileScreen(
    refresh: Int,
    backupBusy: Boolean,
    backupMessage: String?,
    onExport: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var name by remember(refresh) { mutableStateOf(preferences.getString(PROFILE_NAME, "Usuário Kitsune").orEmpty()) }
    var avatar by remember(refresh) { mutableStateOf(preferences.getString(PROFILE_AVATAR, null)) }
    var avatarMessage by remember { mutableStateOf<String?>(null) }
    var releasePreferences by remember(refresh) { mutableStateOf(loadReleasePreferences(context)) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            avatarMessage = "Preparando imagem…"
            runCatching { withContext(Dispatchers.IO) { encodeProfileAvatar(context, uri) } }
                .onSuccess {
                    avatar = it
                    preferences.edit().putString(PROFILE_AVATAR, it).apply()
                    avatarMessage = "Foto atualizada."
                }
                .onFailure { avatarMessage = it.message ?: "Não foi possível usar essa imagem." }
        }
    }
    fun saveReleasePreferences(value: ReleasePreferences) {
        releasePreferences = value
        preferences.edit()
            .putString(RELEASE_LANGUAGE, value.language.name)
            .putInt(RELEASE_RESOLUTION, value.resolution ?: 0)
            .apply()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Perfil", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val image = remember(avatar) { avatar?.let(::decodeProfileAvatar) }
                    if (image != null) {
                        Image(image, "Foto do perfil", Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Text(name.trim().firstOrNull()?.uppercase() ?: "K", style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) { Text("Escolher foto") }
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Nome") }, singleLine = true)
                    Button(onClick = {
                        val savedName = name.trim().ifBlank { "Usuário Kitsune" }
                        name = savedName
                        preferences.edit().putString(PROFILE_NAME, savedName).apply()
                    }) { Text("Salvar perfil") }
                    avatarMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Vídeo preferido", fontWeight = FontWeight.Bold)
                    Text("O app procura primeiro opções compatíveis e então escolhe a mais compartilhada.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Idioma", fontWeight = FontWeight.SemiBold)
                    listOf(
                        ReleaseLanguage.ANY to "Qualquer",
                        ReleaseLanguage.PORTUGUESE to "Português",
                        ReleaseLanguage.ENGLISH to "Inglês",
                        ReleaseLanguage.JAPANESE to "Japonês/original",
                        ReleaseLanguage.DUBBED to "Dublado"
                    ).forEach { (value, label) ->
                        PreferenceOption(releasePreferences.language == value, label) {
                            saveReleasePreferences(releasePreferences.copy(language = value))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Qualidade", fontWeight = FontWeight.SemiBold)
                    listOf(null to "Automática", 720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (value, label) ->
                        PreferenceOption(releasePreferences.resolution == value, label) {
                            saveReleasePreferences(releasePreferences.copy(resolution = value))
                        }
                    }
                }
            }
        }
        item { BackupCard(backupBusy, backupMessage, onExport, onRestore) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PreferenceOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

private fun loadReleasePreferences(context: Context): ReleasePreferences {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val language = runCatching { ReleaseLanguage.valueOf(preferences.getString(RELEASE_LANGUAGE, null).orEmpty()) }
        .getOrDefault(ReleaseLanguage.ANY)
    return ReleasePreferences(language, preferences.getInt(RELEASE_RESOLUTION, 1080).takeIf { it > 0 })
}

private fun encodeProfileAvatar(context: Context, uri: Uri): String {
    val bitmap = if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Imagem inválida.")
    }
    val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val bytes = ByteArrayOutputStream().use { output ->
        check(resized.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "Não foi possível preparar a imagem." }
        output.toByteArray()
    }
    require(bytes.size <= 512 * 1024) { "A imagem de perfil ficou grande demais." }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun decodeProfileAvatar(value: String) = runCatching {
    val bytes = Base64.decode(value, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun BackupCard(busy: Boolean, message: String?, onExport: () -> Unit, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dados e backup", fontWeight = FontWeight.Bold)
            Text("Guarde favoritos, histórico, progresso e preferências fora do aplicativo.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onExport, enabled = !busy) { Text("Exportar") }
                TextButton(onClick = onRestore, enabled = !busy) { Text("Restaurar") }
                if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun UpdateDialog() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var visible by remember { mutableStateOf(false) }
    var ignoreVersion by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var downloadId by rememberSaveable { mutableStateOf(preferences.getLong("update_download_id", -1)) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { AppUpdater.latest(context) } }
            .onSuccess { available ->
                if (available != null && preferences.getString(IGNORED_UPDATE, null) != available.version) {
                    release = available
                    message = "Versão ${available.version} disponível."
                    visible = true
                }
            }
    }

    LaunchedEffect(downloadId) {
        if (downloadId >= 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())) {
            if (AppUpdater.install(context, downloadId)) {
                preferences.edit().remove("update_download_id").apply()
                downloadId = -1
            }
        }
    }

    DisposableEffect(downloadId) {
        val receiver = if (downloadId >= 0) object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    message = "Download concluído. Permita instalar apps desta fonte para continuar."
                } else if (AppUpdater.install(context, downloadId)) {
                    preferences.edit().remove("update_download_id").apply()
                    downloadId = -1
                }
            }
        } else null
        receiver?.let {
            ContextCompat.registerReceiver(context, it, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        }
        onDispose { receiver?.let { runCatching { context.unregisterReceiver(it) } } }
    }

    if (visible && release != null) AlertDialog(
        onDismissRequest = {
            if (ignoreVersion) preferences.edit().putString(IGNORED_UPDATE, release?.version).apply()
            visible = false
        },
        title = { Text("Atualização disponível") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message)
                Row(
                    Modifier.fillMaxWidth().clickable { ignoreVersion = !ignoreVersion },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(ignoreVersion, { ignoreVersion = it })
                    Text("Não mostrar novamente esta versão")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    message = "Permita instalar apps desta fonte e toque em baixar novamente."
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    downloadId = AppUpdater.download(context, requireNotNull(release))
                    preferences.edit().putLong("update_download_id", downloadId).apply()
                    message = "Baixando atualização pelo GitHub…"
                }
            }) { Text("Baixar") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (ignoreVersion) preferences.edit().putString(IGNORED_UPDATE, release?.version).apply()
                visible = false
            }) { Text("Agora não") }
        }
    )
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Buscar anime") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSearch) { Text("Buscar") }
    }
}

@Composable
private fun Catalog(
    items: List<Anime>,
    loading: Boolean,
    error: String?,
    emptyMessage: String,
    onRetry: () -> Unit,
    onSelect: (Anime) -> Unit
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Tentar novamente") }
        }
        items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyMessage) }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { anime -> AnimeCard(anime, onSelect) }
        }
    }
}

@Composable
private fun AnimeCard(anime: Anime, onSelect: (Anime) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(anime) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AsyncImage(
            model = anime.cover,
            contentDescription = "Capa de ${anime.title}",
            modifier = Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(10.dp)) {
            Text(anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                listOfNotNull(anime.year?.toString(), anime.score?.let { "★ $it%" }).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AnimeDetails(
    anime: Anime,
    favorite: Boolean,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onWatch: () -> Unit,
    onEpisode: (Episode) -> Unit,
    onReleases: (Int?) -> Unit,
    onSeason: (Anime) -> Unit
) {
    var episodes by remember(anime.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var episodeLoading by remember(anime.id) { mutableStateOf(anime.format != "MOVIE") }
    var episodeError by remember(anime.id) { mutableStateOf<String?>(null) }
    var seasons by remember(anime.id) { mutableStateOf(listOf(anime)) }
    var seasonLoading by remember(anime.id) { mutableStateOf(true) }
    var seasonError by remember(anime.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(anime.id) {
        if (anime.format == "MOVIE") return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { EpisodeApi.list(anime) } }
            .onSuccess { episodes = it }
            .onFailure { episodeError = it.message ?: "Não foi possível carregar os episódios." }
        episodeLoading = false
    }
    LaunchedEffect(anime.id) {
        runCatching { withContext(Dispatchers.IO) { AnimeApi.seasons(anime) } }
            .onSuccess { seasons = it }
            .onFailure { seasonError = "Não foi possível carregar as outras temporadas." }
        seasonLoading = false
    }

    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(anime.title, maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AsyncImage(
                    model = anime.banner ?: anime.cover,
                    contentDescription = "Imagem de ${anime.title}",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(16.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            anime.year?.toString(), anime.format?.replace('_', ' '),
                            anime.episodes?.let { "$it episódios" }, anime.score?.let { "★ $it%" }
                        ).joinToString("  •  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (anime.genres.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(anime.genres.joinToString(" • "), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onWatch) { Text("Assistir arquivo") }
                        Button(onClick = onFavorite) { Text(if (favorite) "Remover favorito" else "Favoritar") }
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    Text("Sinopse", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(anime.description.ifBlank { "Sinopse indisponível." })
                    anime.status?.let {
                        Spacer(Modifier.height(20.dp))
                        Text("Status: ${it.replace('_', ' ')}", style = MaterialTheme.typography.labelLarge)
                    }
                    if (anime.format == "MOVIE") {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { onReleases(null) }) { Text("Encontrar vídeo do filme") }
                    }
                }
            }
            if (seasonLoading || seasons.size > 1 || seasonError != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Temporadas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (seasonLoading) CircularProgressIndicator(Modifier.padding(vertical = 12.dp))
                        seasons.forEachIndexed { index, seasonAnime ->
                            Card(
                                Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = seasonAnime.id != anime.id) { onSeason(seasonAnime) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (seasonAnime.id == anime.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Temporada ${index + 1}", style = MaterialTheme.typography.labelLarge)
                                        Text(seasonAnime.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOfNotNull(seasonAnime.year?.toString(), seasonAnime.episodes?.let { "$it episódios" }).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(if (seasonAnime.id == anime.id) "Atual" else "Ver ›")
                                }
                            }
                        }
                        seasonError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            if (anime.format != "MOVIE") {
                item {
                    Text("Episódios", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    if (episodeLoading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    episodeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                lazyItems(episodes, key = { it.number }) { episode ->
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { onEpisode(episode) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("EP\n${episode.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Episódio ${episode.number}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOfNotNull(
                                        episode.airedAt?.substringBefore('T'),
                                        episode.durationSeconds?.let { "${it / 60} min" },
                                        "Filler".takeIf { episode.filler },
                                        "Recap".takeIf { episode.recap }
                                    ).joinToString(" • ").ifBlank { "Ver informações do episódio" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("›", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeScreen(
    anime: Anime,
    initialEpisode: Episode,
    initialReleases: List<ReleaseCandidate>?,
    onBack: () -> Unit,
    onReleases: (List<ReleaseCandidate>, ReleaseCandidate?) -> Unit
) {
    val context = LocalContext.current
    val releasePreferences = remember { loadReleasePreferences(context) }
    var episode by remember(anime.id, initialEpisode.number) { mutableStateOf(initialEpisode) }
    var animeSynopsis by remember(anime.id) { mutableStateOf(anime.description) }
    var loading by remember(anime.id, initialEpisode.number) { mutableStateOf(true) }
    var error by remember(anime.id, initialEpisode.number) { mutableStateOf<String?>(null) }
    var releases by remember(anime.id, initialEpisode.number) { mutableStateOf(initialReleases.orEmpty()) }
    var releaseLoading by remember(anime.id, initialEpisode.number) { mutableStateOf(initialReleases == null) }
    var releaseError by remember(anime.id, initialEpisode.number) { mutableStateOf<String?>(null) }

    LaunchedEffect(anime.id, initialEpisode.number) {
        runCatching {
            withContext(Dispatchers.IO) {
                EpisodeApi.details(anime, initialEpisode.number) to EpisodeApi.portuguese(anime.description)
            }
        }.onSuccess { (details, translatedAnimeSynopsis) ->
                animeSynopsis = translatedAnimeSynopsis
                val it = details
                episode = it.copy(
                    title = it.title ?: initialEpisode.title,
                    japaneseTitle = it.japaneseTitle ?: initialEpisode.japaneseTitle,
                    romanjiTitle = it.romanjiTitle ?: initialEpisode.romanjiTitle,
                    airedAt = it.airedAt ?: initialEpisode.airedAt,
                    durationSeconds = it.durationSeconds ?: initialEpisode.durationSeconds,
                    synopsis = it.synopsis ?: initialEpisode.synopsis
                )
            }
            .onFailure { error = it.message ?: "Não foi possível carregar todos os detalhes." }
        loading = false
    }
    LaunchedEffect(anime.id, initialEpisode.number) {
        if (initialReleases != null) {
            releases = initialReleases
            releaseLoading = false
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { ReleaseSearch.search(anime, initialEpisode.number, releasePreferences) } }
            .onSuccess { releases = it }
            .onFailure { releaseError = it.message ?: "Não foi possível procurar vídeos agora." }
        releaseLoading = false
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("${anime.title} • EP ${episode.number}", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AsyncImage(
                    model = episode.thumbnail ?: anime.banner ?: anime.cover,
                    contentDescription = "Imagem do episódio ${episode.number}",
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(18.dp)) {
                    Text("EPISÓDIO ${episode.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(episode.title ?: "Episódio ${episode.number}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    episode.japaneseTitle?.takeIf { it != episode.title }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    episode.romanjiTitle?.takeIf { it != episode.title && it != episode.japaneseTitle }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        listOfNotNull(
                            episode.airedAt?.substringBefore('T'),
                            episode.durationSeconds?.let { "${it / 60} min" },
                            "Filler".takeIf { episode.filler },
                            "Recap".takeIf { episode.recap }
                        ).joinToString("  •  ").ifBlank { "Informações de exibição indisponíveis" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Melhor opção para assistir", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val recommended = recommendedRelease(releases, releasePreferences)
                    when {
                        releaseLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp))
                            Text("Procurando qualidade e seeders…")
                        }
                        recommended != null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("RECOMENDADO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(recommended.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(
                                    listOfNotNull(
                                        recommended.parsed.resolution?.let { "${it}p" },
                                        recommended.parsed.codec,
                                        "${recommended.seeders} seeders informados",
                                        "PT-BR".takeIf { recommended.parsed.ptBr },
                                        "Dublado".takeIf { recommended.parsed.dubbed },
                                        "Pacote; só este episódio".takeIf { recommended.parsed.batch }
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { onReleases(releases, recommended) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                ) { Text("Baixar e assistir") }
                                TextButton(onClick = { onReleases(releases, null) }, modifier = Modifier.fillMaxWidth()) { Text("Ver todas as opções") }
                            }
                        }
                        else -> {
                            Text(releaseError ?: "Nenhum vídeo compatível encontrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onReleases(releases, null) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Tentar busca completa") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    val hasEpisodeSynopsis = !episode.synopsis.isNullOrBlank()
                    Text(if (hasEpisodeSynopsis) "Sinopse do episódio" else "Sobre o anime", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (hasEpisodeSynopsis) episode.synopsis.orEmpty() else animeSynopsis.ifBlank { "Sinopse indisponível." })
                    if (!hasEpisodeSynopsis) {
                        Spacer(Modifier.height(8.dp))
                        Text("Este episódio ainda não possui uma sinopse cadastrada nos provedores.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (loading) {
                        Spacer(Modifier.height(18.dp))
                        CircularProgressIndicator()
                    }
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseScreen(
    anime: Anime,
    episode: Int?,
    initialReleases: List<ReleaseCandidate>?,
    autoReleaseId: String?,
    onBack: () -> Unit,
    onDownload: (ReleaseCandidate, List<Int>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val releasePreferences = remember { loadReleasePreferences(context) }
    var releases by remember(anime.id, episode) { mutableStateOf(initialReleases.orEmpty()) }
    var loading by remember(anime.id, episode) { mutableStateOf(initialReleases == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inspectingId by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var selectedRelease by remember { mutableStateOf<ReleaseCandidate?>(null) }
    var choices by remember { mutableStateOf<List<TorrentFileChoice>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var automaticHandled by remember(anime.id, episode, autoReleaseId) { mutableStateOf(false) }

    fun inspect(release: ReleaseCandidate) {
        inspectingId = release.id
        fileError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { TorrentService.inspect(release) } }
                .onSuccess { files ->
                    val selection = defaultTorrentSelection(files, episode)
                    when {
                        selection == null -> fileError = "A release não contém vídeo reconhecido."
                        files.count(TorrentFileChoice::isVideo) == 1 -> onDownload(release, selection.first, selection.second)
                        else -> {
                            selectedRelease = release
                            choices = files
                            selectedFiles = selection.first.toSet()
                        }
                    }
                }
                .onFailure { fileError = it.message ?: "Não foi possível ler os arquivos do torrent." }
            inspectingId = null
        }
    }

    LaunchedEffect(anime.id, episode) {
        if (initialReleases != null) {
            releases = initialReleases
            loading = false
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { ReleaseSearch.search(anime, episode, releasePreferences) } }
            .onSuccess { releases = it }
            .onFailure { error = it.message ?: "Não foi possível encontrar vídeos para este episódio." }
        loading = false
    }
    LaunchedEffect(releases, autoReleaseId) {
        if (!automaticHandled && autoReleaseId != null) {
            releases.firstOrNull { it.id == autoReleaseId }?.let {
                automaticHandled = true
                inspect(it)
            }
        }
    }
    selectedRelease?.let { release ->
        val videoFile = primaryTorrentVideo(choices, selectedFiles, episode)
        AlertDialog(
            onDismissRequest = { selectedRelease = null },
            title = { Text("Escolher arquivos") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    lazyItems(choices, key = TorrentFileChoice::index) { file ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedFiles = if (file.index in selectedFiles) selectedFiles - file.index else selectedFiles + file.index
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = file.index in selectedFiles,
                                onCheckedChange = { checked ->
                                    selectedFiles = if (checked) selectedFiles + file.index else selectedFiles - file.index
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(File(file.path).name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${if (file.isVideo) "Vídeo" else "Legenda"} • ${formatBytes(file.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = videoFile != null,
                    onClick = {
                        val files = choices.filter { it.index in selectedFiles }.map(TorrentFileChoice::index)
                        onDownload(release, files, requireNotNull(videoFile))
                        selectedRelease = null
                    }
                ) { Text("Baixar selecionados") }
            },
            dismissButton = { TextButton(onClick = { selectedRelease = null }) { Text("Cancelar") } }
        )
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(episode?.let { "Escolher vídeo • Episódio $it" } ?: "Escolher vídeo", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    "Seeders são informados para o torrent inteiro. Em pacotes, o app baixa e prioriza somente o episódio escolhido; peers conectados aparecem em Downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            fileError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
            if (!loading && error == null && releases.isEmpty()) item { Text("Nenhum vídeo compatível encontrado.", modifier = Modifier.padding(16.dp)) }
            val recommended = recommendedRelease(releases, releasePreferences)
            val orderedReleases = recommended?.let { listOf(it) + releases.filterNot { release -> release.id == it.id } } ?: releases
            lazyItems(orderedReleases, key = { it.id }) { release ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        if (release.id == recommended?.id) Text("RECOMENDADO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(release.title, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                release.parsed.resolution?.let { "${it}p" }, release.parsed.codec,
                                "10-bit".takeIf { release.parsed.tenBit },
                                formatBytes(release.sizeBytes), "${release.seeders} seeders informados",
                                "Pacote".takeIf { release.parsed.batch }, "score ${release.score}"
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (release.reasons.isNotEmpty()) Text(release.reasons.take(4).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                            Button(onClick = { inspect(release) }, enabled = inspectingId == null) {
                                if (inspectingId == release.id) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                                else Text("Baixar e assistir")
                            }
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nyaa.si/view/${release.id}")))
                            }) { Text("Ver origem") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(
    onPlay: (TorrentDownload) -> Unit,
    onPause: (String) -> Unit,
    onResume: (TorrentDownload) -> Unit,
    onRemove: (String) -> Unit
) {
    val downloads = TorrentStore.downloads.filter { it.status != "completed" }
    if (downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum download em andamento.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        lazyItems(downloads, key = { it.infoHash }) { download ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(download.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    if (download.status == "procurando peers") LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (download.status == "procurando peers") "Procurando peers via DHT e trackers…"
                        else "${(download.progress * 100).toInt()}% • ${formatBytes(download.downloadSpeed)}/s • ${download.peers} peers • ${download.status}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (download.videoPath != null && File(download.videoPath).isFile &&
                            File(download.videoPath).extension.equals("mkv", ignoreCase = true)
                        ) {
                            Button(onClick = { onPlay(download) }) { Text("Assistir enquanto baixa") }
                        }
                        when (download.status) {
                            "downloading", "queued", "procurando peers" -> TextButton(onClick = { onPause(download.infoHash) }) { Text("Pausar") }
                            "paused", "failed" -> TextButton(onClick = { onResume(download) }) { Text(if (download.status == "failed") "Tentar novamente" else "Continuar") }
                        }
                        TextButton(onClick = { onRemove(download.infoHash) }) { Text("Excluir") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    onPlay: (TorrentDownload) -> Unit,
    onOpenVideo: () -> Unit,
    onRemove: (String) -> Unit
) {
    val completed = TorrentStore.downloads.filter {
        it.status == "completed" && it.videoPath?.let(::File)?.isFile == true
    }
    val animeGroups = completed.groupBy { it.animeId?.toString() ?: "legacy:${it.infoHash}" }
        .values.sortedBy { it.first().animeTitle ?: it.first().name }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Biblioteca offline", style = MaterialTheme.typography.headlineSmall)
                    Text("${completed.size} episódio(s) baixado(s)", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onOpenVideo) { Text("Abrir vídeo") }
            }
        }
        if (animeGroups.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("Os animes concluídos aparecerão aqui.")
                }
            }
        }
        animeGroups.forEach { group ->
            val first = group.first()
            val cover = group.firstNotNullOfOrNull { download ->
                download.animeCoverPath?.takeIf { File(it).isFile }?.let(::File)
            } ?: first.animeCoverUrl
            item(first.animeId?.toString() ?: first.infoHash) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        AsyncImage(
                            model = cover,
                            contentDescription = "Capa de ${first.animeTitle ?: first.name}",
                            modifier = Modifier.width(96.dp).height(138.dp),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(first.animeTitle ?: first.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${group.size} episódio(s)", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(8.dp))
                            group.sortedWith(compareBy<TorrentDownload> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name }).forEach { download ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        download.episode?.let { "EP ${it.toString().padStart(2, '0')}" } ?: download.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    TextButton(onClick = { onPlay(download) }) { Text("Assistir") }
                                    TextButton(onClick = { onRemove(download.infoHash) }) { Text("Excluir") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "%.1f %s".format(value, units[unit])
}

@Composable
private fun PlayerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val progressKey = "progress:$uri"
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
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
                if (state == Player.STATE_READY && !resumeChecked) {
                    resumeChecked = true
                    val download = uri.getQueryParameter("hash")?.let(TorrentStore::get)
                    safeStreamingResumePosition(
                        initialPosition, player.duration,
                        download?.streamableBytes ?: 0, download?.sizeBytes ?: 0
                    ).takeIf { it > 0 }?.let(player::seekTo)
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
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
            VideoHistory.record(context, uri, player.currentPosition)
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

    Box(
        Modifier.fillMaxSize().background(Color.Black).then(
            if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars)
        )
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
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
            val message = when {
                streamingDownload == null -> "Restaurando o download…"
                streamingDownload.peers == 0 && streamingDownload.downloadSpeed == 0L -> "Aguardando peers para carregar o vídeo…"
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
    if (saved <= 0 || duration <= 0 || contiguousBytes <= 0 || totalBytes <= 0) return 0
    val playableUntil = (duration.toDouble() * contiguousBytes / totalBytes).toLong()
    return saved.takeIf { it + 10_000 <= playableUntil } ?: 0
}

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

@Composable
private fun HistoryScreen(onPlay: (String) -> Unit, onRemove: (String) -> Unit) {
    val history = VideoHistory.items
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum vídeo assistido ainda.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Histórico", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        lazyItems(history, key = { it.uri }) { video ->
            Row(Modifier.fillMaxWidth().clickable { onPlay(video.uri) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text("Continuar em ${formatDuration(video.positionMs)}", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onRemove(video.uri) }) { Text("Remover") }
            }
            HorizontalDivider()
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%02d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
}

private fun loadFavoriteIds(context: Context): Set<Int> = context
    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    .getStringSet(FAVORITES, emptySet()).orEmpty()
    .mapNotNull(String::toIntOrNull)
    .toSet()

private fun saveFavoriteIds(context: Context, ids: Set<Int>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(FAVORITES, ids.map(Int::toString).toSet()).apply()
}
