package com.kitsuneandroid

import android.content.Context
import android.os.Environment
import org.libtorrent4j.TorrentInfo
import java.io.File

internal val torrentVideoExtensions = setOf("mkv", "mp4", "webm", "avi", "m4v", "mov", "ts", "m2ts")
internal val torrentDownloadableExtensions = torrentVideoExtensions + setOf("srt", "ass", "ssa", "vtt")

data class TorrentEpisodeTarget(
    val episode: Int,
    val selectedFileIndices: List<Int>,
    val videoFileIndex: Int,
    val videoPath: String
)

internal object TorrentContent {
    fun nextEpisode(context: Context, download: TorrentDownload): TorrentEpisodeTarget? {
        val currentEpisode = download.episode ?: return null
        val torrentFile = File(torrentMetadataDirectory(context), "${download.infoHash}.torrent")
        if (!torrentFile.isFile) return null
        val info = runCatching { TorrentInfo(torrentFile) }.getOrNull() ?: return null
        val files = info.files()
        val choices = (0 until files.numFiles()).mapNotNull { index ->
            val path = files.filePath(index)
            val extension = File(path).extension.lowercase()
            if (extension !in torrentDownloadableExtensions) null
            else TorrentFileChoice(index, path, files.fileSize(index), extension in torrentVideoExtensions)
        }
        val selection = torrentEpisodeSelection(choices, currentEpisode + 1) ?: return null
        val video = choices.first { it.index == selection.second }
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "Kitsune")
        return TorrentEpisodeTarget(currentEpisode + 1, selection.first, video.index, File(root, video.path).absolutePath)
    }
}

internal fun torrentEpisodeSelection(files: List<TorrentFileChoice>, episode: Int): Pair<List<Int>, Int>? {
    val video = files.firstOrNull { it.isVideo && parseReleaseTitle(File(it.path).name).episode == episode } ?: return null
    val related = files.filter { parseReleaseTitle(File(it.path).name).episode == episode }
        .plus(video)
        .distinctBy(TorrentFileChoice::index)
    return related.map(TorrentFileChoice::index) to video.index
}
