package com.kitsuneandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DownloadsScreen(
    onPlay: (TorrentDownload) -> Unit,
    onPause: (String) -> Unit,
    onResume: (TorrentDownload) -> Unit,
    onRemove: (String) -> Unit
) {
    val downloads = TorrentStore.downloads.filter { it.status != TorrentStatus.COMPLETED }
    var pendingRemoval by remember { mutableStateOf<TorrentDownload?>(null) }
    pendingRemoval?.let { download ->
        ConfirmRemovalDialog(
            message = stringResource(R.string.confirm_delete_download, download.name),
            onConfirm = {
                onRemove(download.infoHash)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null }
        )
    }
    if (downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_active_downloads)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(stringResource(R.string.downloads), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        lazyItems(downloads, key = { it.infoHash }) { download ->
            Card(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(download.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    if (download.status == TorrentStatus.SEARCHING_PEERS) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { download.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        downloadStatusText(download),
                        style = MaterialTheme.typography.labelMedium
                    )
                    torrentConnectionDiagnostic(
                        download,
                        stringResource(
                            R.string.tracker_seeders_diagnostic,
                            download.trackerSeeders ?: 0,
                            download.connectedSeeders
                        )
                    )?.let { diagnostic ->
                        Text(
                            diagnostic,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        if (download.videoPath != null && File(download.videoPath).isFile &&
                            File(download.videoPath).extension.equals("mkv", ignoreCase = true)
                        ) {
                            Button(onClick = { onPlay(download) }) { Text(stringResource(R.string.watch_while_downloading)) }
                        }
                        DownloadStatusAction(download, onPause, onResume)
                        TextButton(onClick = { pendingRemoval = download }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusAction(
    download: TorrentDownload,
    onPause: (String) -> Unit,
    onResume: (TorrentDownload) -> Unit
) {
    when (download.status) {
        TorrentStatus.DOWNLOADING,
        TorrentStatus.QUEUED,
        TorrentStatus.SEARCHING_PEERS -> TextButton(
            onClick = { onPause(download.infoHash) }
        ) { Text(stringResource(R.string.pause)) }

        TorrentStatus.STALLED -> TextButton(
            onClick = { onResume(download) }
        ) { Text(stringResource(R.string.reconnect)) }

        TorrentStatus.PAUSED,
        TorrentStatus.FAILED -> TextButton(
            onClick = { onResume(download) }
        ) {
            Text(stringResource(if (download.status == TorrentStatus.FAILED) R.string.try_again else R.string.continue_action))
        }

        TorrentStatus.COMPLETED -> Unit
    }
}

@Composable
internal fun downloadStatusText(download: TorrentDownload): String {
    if (download.status == TorrentStatus.SEARCHING_PEERS) {
        return stringResource(R.string.searching_peers)
    }

    if (download.status == TorrentStatus.STALLED) {
        return stringResource(R.string.download_stalled)
    }

    val swarm = buildList {
        add(pluralStringResource(R.plurals.connected_peers, download.peers, download.peers))

        if (download.connectedSeeders > 0) {
            add(
                pluralStringResource(
                    R.plurals.active_seeders,
                    download.connectedSeeders,
                    download.connectedSeeders
                )
            )
        }

        download.trackerSeeders?.let { trackerSeeders ->
            add(pluralStringResource(R.plurals.tracker_seeders, trackerSeeders, trackerSeeders))
        }

        if (download.knownPeers > download.peers) {
            add(pluralStringResource(R.plurals.known_peers, download.knownPeers, download.knownPeers))
        }

        if (download.connectionCandidates > 0) {
            add(
                pluralStringResource(
                    R.plurals.connection_candidates,
                    download.connectionCandidates,
                    download.connectionCandidates
                )
            )
        }
    }.joinToString(" • ")

    return "${(download.progress * 100).toInt()}% • " +
        "${formatBytes(download.downloadSpeed)}/s • " +
        "$swarm • ${torrentStatusLabel(download.status)}"
}

internal fun torrentConnectionDiagnostic(download: TorrentDownload, localizedMessage: String? = null): String? {
    val trackerSeeders = download.trackerSeeders ?: return null

    if (trackerSeeders < 10 || download.connectedSeeders * 4 >= trackerSeeders) {
        return null
    }

    return localizedMessage
        ?: "The tracker announces $trackerSeeders seeders, but only ${download.connectedSeeders} connected so far."
}

@Composable
private fun torrentStatusLabel(status: TorrentStatus): String = stringResource(
    when (status) {
        TorrentStatus.QUEUED -> R.string.status_queued
        TorrentStatus.SEARCHING_PEERS -> R.string.status_searching_peers
        TorrentStatus.DOWNLOADING -> R.string.status_downloading
        TorrentStatus.STALLED -> R.string.status_stalled
        TorrentStatus.PAUSED -> R.string.status_paused
        TorrentStatus.COMPLETED -> R.string.status_completed
        TorrentStatus.FAILED -> R.string.status_failed
    }
)

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun LibraryScreen(
    episodes: List<TorrentDownload>,
    onPlay: (TorrentDownload) -> Unit,
    onOpenVideo: () -> Unit,
    onRemove: (TorrentDownload) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedAnimeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<TorrentDownload?>(null) }
    var pendingVideoExport by remember { mutableStateOf<TorrentDownload?>(null) }
    var exportingVideoPath by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    fun saveVideo(download: TorrentDownload) {
        val video = download.videoPath?.let(::File)
        if (video == null || !video.isFile || exportingVideoPath != null) {
            return
        }

        exportingVideoPath = video.absolutePath
        exportMessage = context.getString(R.string.saving_to_downloads)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    saveFileToDownloads(context, video, videoMimeType(video))
                }
                exportMessage = context.getString(R.string.saved_to_downloads, video.name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                exportMessage = failure.message ?: context.getString(R.string.error_save_downloads)
            } finally {
                exportingVideoPath = null
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingVideoExport
        pendingVideoExport = null
        if (granted && download != null) {
            saveVideo(download)
        } else if (!granted) {
            exportMessage = context.getString(R.string.storage_permission_required)
        }
    }

    fun exportVideo(download: TorrentDownload) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingVideoExport = download
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveVideo(download)
        }
    }
    pendingRemoval?.let { download ->
        ConfirmRemovalDialog(
            message = stringResource(
                R.string.confirm_delete_offline_episode,
                offlineEpisodeName(download)
            ),
            onConfirm = {
                onRemove(download)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null }
        )
    }
    val animeGroups = episodes
        .groupBy { download -> download.animeId?.toString() ?: "legacy:${download.infoHash}" }
        .values
        .filter { group ->
            group.any { download ->
                matchesLibraryQuery(
                    query,
                    download.animeTitle,
                    download.name,
                    download.episode?.toString()
                )
            }
        }
        .sortedBy { group -> group.first().animeTitle ?: group.first().name }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(stringResource(R.string.offline_library), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        pluralStringResource(
                            R.plurals.downloaded_episode_count,
                            episodes.size,
                            episodes.size
                        ) + " • " + formatBytes(episodes.sumOf(TorrentDownload::sizeBytes)),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = onOpenVideo) { Text(stringResource(R.string.open_video)) }
            }
        }
        if (episodes.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { value -> query = value.take(80) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text(stringResource(R.string.search_offline_library)) },
                    singleLine = true
                )
            }
        }
        exportMessage?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
        if (episodes.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.completed_anime_appear_here))
                }
            }
        } else if (animeGroups.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_library_results))
                }
            }
        }
        animeGroups.forEach { group ->
            val first = group.first()
            val groupKey = first.animeId?.toString() ?: first.infoHash
            val sortedEpisodes = group.sortedWith(
                compareBy<TorrentDownload> { download -> download.episode ?: Int.MAX_VALUE }
                    .thenBy(TorrentDownload::name)
            )
            val continueEpisode = mostRecentOfflineEpisode(sortedEpisodes, VideoHistory.items)
            val continueHistory = continueEpisode?.let { download ->
                historyForOfflineDownload(VideoHistory.items, download)
            }
            val expanded = expandedAnimeKey == groupKey
            val cover = group.firstNotNullOfOrNull { download ->
                download.animeCoverPath?.takeIf { File(it).isFile }?.let(::File)
            } ?: first.animeCoverUrl
            item(groupKey) {
                Card(
                    Modifier
                        .animateItem()
                        .animateContentSize(animationSpec = spring())
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(Modifier.padding(12.dp)) {
                        AsyncImage(
                            model = cover,
                            contentDescription = stringResource(R.string.anime_cover, first.animeTitle ?: first.name),
                            modifier = Modifier.width(88.dp).height(126.dp),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                first.animeTitle ?: first.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(pluralStringResource(R.plurals.episode_count, group.size, group.size), style = MaterialTheme.typography.labelMedium)
                            continueEpisode?.let { download ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    offlineContinueLabel(download, continueHistory),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Row {
                                    TextButton(onClick = { onPlay(download) }) {
                                        Text(
                                            if (continueHistory?.completed == false) {
                                                stringResource(R.string.continue_action)
                                            } else {
                                                stringResource(R.string.watch)
                                            }
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            expandedAnimeKey = if (expanded) null else groupKey
                                        }
                                    ) {
                                        Text(stringResource(if (expanded) R.string.hide_episodes else R.string.view_episodes))
                                    }
                                }
                            }
                        }
                    }
                    if (expanded) {
                        HorizontalDivider()
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            sortedEpisodes.forEach { download ->
                                val uri = playbackUri(download).toString()
                                val watched = historyForOfflineDownload(VideoHistory.items, download)
                                val completed = VideoHistory.isEpisodeCompleted(
                                    history = watched,
                                    animeId = download.animeId,
                                    episode = download.episode,
                                    uri = uri
                                )
                                Column(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(offlineEpisodeName(download), maxLines = 1)
                                        if (completed) {
                                            Text(
                                                stringResource(R.string.watched),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        } else {
                                            watched?.let { history ->
                                                Text(
                                                    stringResource(
                                                        R.string.continue_at,
                                                        formatDuration(history.positionMs)
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { onPlay(download) }) {
                                            Text(stringResource(R.string.watch))
                                        }
                                        TextButton(
                                            enabled = exportingVideoPath == null,
                                            onClick = { exportVideo(download) }
                                        ) {
                                            if (exportingVideoPath == download.videoPath) {
                                                LinearProgressIndicator(Modifier.width(42.dp))
                                            } else {
                                                Text(stringResource(R.string.save_video))
                                            }
                                        }
                                        TextButton(onClick = { pendingRemoval = download }) {
                                            Text(stringResource(R.string.delete))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmRemovalDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_deletion)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

internal fun mostRecentOfflineEpisode(
    episodes: List<TorrentDownload>,
    history: List<WatchedVideo>
): TorrentDownload? {
    return episodes.maxByOrNull { download ->
        historyForOfflineDownload(history, download)?.watchedAt ?: Long.MIN_VALUE
    }
}

internal fun historyForOfflineDownload(
    history: List<WatchedVideo>,
    download: TorrentDownload
): WatchedVideo? {
    val uri = playbackUri(download).toString()
    val animeId = download.animeId
    val episode = download.episode
    if (animeId != null && episode != null) {
        return historyForEpisode(history, animeId, episode, uri)
    }
    return history.firstOrNull { item -> item.uri == uri }
}

@Composable
private fun offlineContinueLabel(download: TorrentDownload, history: WatchedVideo?): String {
    val episode = offlineEpisodeName(download)
    if (history == null) {
        return stringResource(R.string.start_with, episode)
    }
    if (history.completed) {
        return stringResource(R.string.last_watched, episode)
    }
    return stringResource(R.string.continue_episode_at, episode, formatDuration(history.positionMs))
}

private fun offlineEpisodeName(download: TorrentDownload): String {
    return download.episode
        ?.let { episode -> "EP ${episode.toString().padStart(2, '0')}" }
        ?: download.name
}

@Composable
internal fun HistoryScreen(
    onBack: (() -> Unit)? = null,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    val history = VideoHistory.items
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val filteredHistory = history.filter { video ->
        matchesLibraryQuery(query, video.animeTitle, video.title, video.episode?.toString())
    }
    if (confirmClear) {
        ConfirmRemovalDialog(
            message = stringResource(R.string.confirm_clear_history),
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false }
        )
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                onBack?.let { navigateBack ->
                    TextButton(onClick = navigateBack) {
                        Text(stringResource(R.string.back))
                    }
                }
                Text(
                    stringResource(R.string.history),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(stringResource(R.string.clear_history))
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { value -> query = value.take(80) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text(stringResource(R.string.search_history)) },
                singleLine = true
            )
        }
        if (filteredHistory.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (history.isEmpty()) R.string.no_watched_videos else R.string.no_library_results
                        )
                    )
                }
            }
        }
        lazyItems(filteredHistory, key = { it.uri }) { video ->
            val storedDownload = TorrentStore.downloads.firstOrNull { download ->
                playbackUri(download).toString() == video.uri ||
                    download.videoPath == Uri.parse(video.uri).path
            }
            val animeTitle = video.animeTitle ?: storedDownload?.animeTitle
            val episode = video.episode ?: storedDownload?.episode
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { onPlay(video.uri) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val coverPath = video.animeCoverPath ?: storedDownload?.animeCoverPath
                    val cover = coverPath?.takeIf { File(it).isFile }?.let(::File)
                        ?: video.animeCoverUrl
                        ?: storedDownload?.animeCoverUrl
                    AsyncImage(
                        model = cover,
                        contentDescription = animeTitle,
                        modifier = Modifier.width(78.dp).height(108.dp),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(animeTitle ?: video.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        episode?.let { number ->
                            Text(stringResource(R.string.episode_number, number), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            if (video.completed) {
                                stringResource(R.string.watched)
                            } else {
                                video.durationMs.takeIf { it > 0 }?.let {
                                    stringResource(
                                        R.string.stopped_at_of,
                                        formatDuration(video.positionMs),
                                        formatDuration(it)
                                    )
                                } ?: stringResource(R.string.stopped_at, formatDuration(video.positionMs))
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(onClick = { onRemove(video.uri) }) { Text(stringResource(R.string.remove)) }
                }
            }
        }
    }
}

internal fun matchesLibraryQuery(query: String, vararg values: String?): Boolean {
    val term = query.trim()
    if (term.isEmpty()) {
        return true
    }
    return values.any { value -> value?.contains(term, ignoreCase = true) == true }
}
