package com.kitsuneandroid

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
internal fun LibraryScreen(
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
