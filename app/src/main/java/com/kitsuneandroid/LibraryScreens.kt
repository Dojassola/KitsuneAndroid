package com.kitsuneandroid

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@Composable
internal fun DownloadsScreen(
    onPlay: (TorrentDownload) -> Unit,
    onPause: (String) -> Unit,
    onResume: (TorrentDownload) -> Unit,
    onRemove: (String) -> Unit
) {
    val downloads = TorrentStore.downloads.filter { it.status != TorrentStatus.COMPLETED }
    if (downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_active_downloads)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(stringResource(R.string.downloads), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        lazyItems(downloads, key = { it.infoHash }) { download ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (download.videoPath != null && File(download.videoPath).isFile &&
                            File(download.videoPath).extension.equals("mkv", ignoreCase = true)
                        ) {
                            Button(onClick = { onPlay(download) }) { Text(stringResource(R.string.watch_while_downloading)) }
                        }
                        DownloadStatusAction(download, onPause, onResume)
                        TextButton(onClick = { onRemove(download.infoHash) }) { Text(stringResource(R.string.delete)) }
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
        add(stringResource(R.string.connected_peers, download.peers))

        if (download.connectedSeeders > 0) {
            add(stringResource(R.string.active_seeders, download.connectedSeeders))
        }

        download.trackerSeeders?.let { trackerSeeders ->
            add(stringResource(R.string.tracker_seeders, trackerSeeders))
        }

        if (download.knownPeers > download.peers) {
            add(stringResource(R.string.known_peers, download.knownPeers))
        }

        if (download.connectionCandidates > 0) {
            add(stringResource(R.string.connection_candidates, download.connectionCandidates))
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
internal fun LibraryScreen(
    episodes: List<TorrentDownload>,
    onPlay: (TorrentDownload) -> Unit,
    onOpenVideo: () -> Unit,
    onRemove: (TorrentDownload) -> Unit
) {
    var expandedAnimeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val animeGroups = episodes
        .groupBy { download -> download.animeId?.toString() ?: "legacy:${download.infoHash}" }
        .values
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
                    Text(pluralStringResource(R.plurals.downloaded_episode_count, episodes.size, episodes.size), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onOpenVideo) { Text(stringResource(R.string.open_video)) }
            }
        }
        if (animeGroups.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.completed_anime_appear_here))
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
                VideoHistory.items.firstOrNull { history ->
                    history.uri == playbackUri(download).toString()
                }
            }
            val expanded = expandedAnimeKey == groupKey
            val cover = group.firstNotNullOfOrNull { download ->
                download.animeCoverPath?.takeIf { File(it).isFile }?.let(::File)
            } ?: first.animeCoverUrl
            item(groupKey) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
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
                                val watched = VideoHistory.items.firstOrNull { history ->
                                    history.uri == playbackUri(download).toString()
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(offlineEpisodeName(download), maxLines = 1)
                                        watched?.let { history ->
                                            Text(
                                                if (history.completed) {
                                                    stringResource(R.string.watched)
                                                } else {
                                                    stringResource(R.string.continue_at, formatDuration(history.positionMs))
                                                },
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    TextButton(onClick = { onPlay(download) }) { Text(stringResource(R.string.watch)) }
                                    TextButton(onClick = { onRemove(download) }) { Text(stringResource(R.string.delete)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun mostRecentOfflineEpisode(
    episodes: List<TorrentDownload>,
    history: List<WatchedVideo>
): TorrentDownload? {
    val watchedAtByUri = history.associate { item -> item.uri to item.watchedAt }
    return episodes.maxByOrNull { download ->
        watchedAtByUri[playbackUri(download).toString()] ?: Long.MIN_VALUE
    }
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

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "%.1f %s".format(value, units[unit])
}


@Composable
internal fun HistoryScreen(onPlay: (String) -> Unit, onRemove: (String) -> Unit) {
    val history = VideoHistory.items
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_watched_videos)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(stringResource(R.string.history), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        lazyItems(history, key = { it.uri }) { video ->
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

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%02d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
}
