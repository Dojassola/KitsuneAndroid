package com.kitsuneandroid

import android.content.Context
import android.os.Environment
import org.libtorrent4j.TorrentInfo
import java.io.File

private const val MINIMUM_STREAM_PRIORITY_BYTES = 12 * 1024 * 1024
private const val MAXIMUM_STREAM_PRIORITY_BYTES = 64 * 1024 * 1024
private const val STREAM_PRIORITY_SECONDS = 20

internal val torrentVideoExtensions = setOf("mkv", "mp4", "webm", "avi", "m4v", "mov", "ts", "m2ts")
internal val torrentDownloadableExtensions = torrentVideoExtensions + setOf("srt", "ass", "ssa", "vtt")

data class TorrentEpisodeTarget(
    val episode: Int,
    val selectedFileIndices: List<Int>,
    val videoFileIndex: Int,
    val videoPath: String
)

internal fun nextTorrentEpisode(
    context: Context,
    download: TorrentDownload
): TorrentEpisodeTarget? {
    val currentEpisode = download.episode

    if (currentEpisode == null) {
        return null
    }

    val torrentInfo = readTorrentInfo(context, download.infoHash)

    if (torrentInfo == null) {
        return null
    }

    val files = torrentFileChoices(torrentInfo)
    val selection = torrentEpisodeSelection(files, currentEpisode + 1)

    if (selection == null) {
        return null
    }

    val video = files.first { file -> file.index == selection.second }
    val videoPath = File(torrentDownloadDirectory(context), video.path).absolutePath

    return TorrentEpisodeTarget(
        episode = currentEpisode + 1,
        selectedFileIndices = selection.first,
        videoFileIndex = video.index,
        videoPath = videoPath
    )
}

internal fun readTorrentInfo(context: Context, infoHash: String): TorrentInfo? {
    val torrentFile = File(torrentMetadataDirectory(context), "$infoHash.torrent")

    if (!torrentFile.isFile) {
        return null
    }

    return try {
        TorrentInfo(torrentFile)
    } catch (_: Exception) {
        null
    }
}

internal fun torrentFileChoices(torrentInfo: TorrentInfo): List<TorrentFileChoice> {
    val files = torrentInfo.files()
    val choices = mutableListOf<TorrentFileChoice>()

    for (index in 0 until files.numFiles()) {
        val path = files.filePath(index)
        val extension = File(path).extension.lowercase()

        if (extension !in torrentDownloadableExtensions) {
            continue
        }

        choices.add(
            TorrentFileChoice(
                index = index,
                path = path,
                sizeBytes = files.fileSize(index),
                isVideo = extension in torrentVideoExtensions
            )
        )
    }

    return choices
}

internal fun torrentDownloadDirectory(context: Context): File {
    val moviesDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?: context.filesDir

    return File(moviesDirectory, "Kitsune")
}

internal fun torrentEpisodeSelection(
    files: List<TorrentFileChoice>,
    episode: Int
): Pair<List<Int>, Int>? {
    val video = files.firstOrNull { file ->
        file.isVideo && parseReleaseTitle(File(file.path).name).episode == episode
    }

    if (video == null) {
        return null
    }

    val relatedFiles = files
        .filter { file -> parseReleaseTitle(File(file.path).name).episode == episode }
        .plus(video)
        .distinctBy { file -> file.index }
    val selectedIndices = relatedFiles.map { file -> file.index }

    return selectedIndices to video.index
}

internal fun explicitTorrentSelection(
    files: List<TorrentFileChoice>,
    videoFileIndex: Int
): Pair<List<Int>, Int>? {
    val video = files.firstOrNull { file ->
        file.index == videoFileIndex && file.isVideo
    }

    if (video == null) {
        return null
    }

    val videoPath = File(video.path)
    val episode = parseReleaseTitle(videoPath.name).episode
    val relatedFiles = files.filter { file ->
        val path = File(file.path)
        val sameEpisode = episode != null &&
            parseReleaseTitle(path.name).episode == episode
        val sameBaseName = path.nameWithoutExtension == videoPath.nameWithoutExtension
        file.index == video.index || sameEpisode || sameBaseName
    }

    return relatedFiles.map(TorrentFileChoice::index) to video.index
}

internal fun torrentFilesAfterEpisodeRemoval(
    files: List<TorrentFileChoice>,
    selectedFileIndices: List<Int>,
    episode: Int?,
    videoFileIndex: Int?
): List<Int> {
    val selectedFiles = selectedFileIndices.toSet()
    val episodeSelection = episode?.let { number ->
        torrentEpisodeSelection(files, number)
    }
    val removedFiles = if (episodeSelection != null) {
        episodeSelection.first
            .filter { index -> index in selectedFiles }
            .toSet()
    } else {
        videoFileIndex
            ?.takeIf { index -> index in selectedFiles }
            ?.let(::setOf)
            .orEmpty()
    }

    return selectedFileIndices.filterNot { index -> index in removedFiles }
}

internal fun defaultTorrentSelection(
    files: List<TorrentFileChoice>,
    wantedEpisode: Int?
): Pair<List<Int>, Int>? {
    val videos = files.filter(TorrentFileChoice::isVideo)

    if (videos.isEmpty()) {
        return null
    }

    val requestedEpisode = videos.firstOrNull { file ->
        wantedEpisode != null &&
            parseReleaseTitle(File(file.path).name).episode == wantedEpisode
    }
    val primary = requestedEpisode ?: videos.maxBy(TorrentFileChoice::sizeBytes)
    val selectedFiles = if (videos.size == 1) {
        files
    } else {
        files
            .filter { file ->
                wantedEpisode != null &&
                    parseReleaseTitle(File(file.path).name).episode == wantedEpisode
            }
            .plus(primary)
            .distinctBy(TorrentFileChoice::index)
    }

    return selectedFiles.map(TorrentFileChoice::index) to primary.index
}

internal fun priorityWindowLast(
    firstPiece: Int,
    fileLastPiece: Int,
    bytes: Int,
    pieceLength: Int
): Int {
    val pieceCount = maxOf(1, (bytes + pieceLength - 1) / pieceLength)
    return minOf(fileLastPiece, firstPiece + pieceCount - 1)
}

internal fun streamPriorityBytes(downloadRateBytesPerSecond: Long): Int {
    return (downloadRateBytesPerSecond * STREAM_PRIORITY_SECONDS)
        .coerceIn(
            MINIMUM_STREAM_PRIORITY_BYTES.toLong(),
            MAXIMUM_STREAM_PRIORITY_BYTES.toLong()
        )
        .toInt()
}

internal fun primaryTorrentVideo(
    files: List<TorrentFileChoice>,
    selectedFiles: Set<Int>,
    wantedEpisode: Int?
): Int? {
    return files
        .filter { file -> file.isVideo && file.index in selectedFiles }
        .maxWithOrNull(
            compareBy<TorrentFileChoice> { file ->
                parseReleaseTitle(File(file.path).name).episode == wantedEpisode
            }.thenBy(TorrentFileChoice::sizeBytes)
        )
        ?.index
}

internal fun contiguousFileBytes(
    fileStart: Long,
    fileSize: Long,
    pieceLength: Int,
    firstPiece: Int,
    lastPiece: Int,
    hasPiece: (Int) -> Boolean,
    pieceSize: (Int) -> Int
): Long {
    val fileEnd = fileStart + fileSize
    var availableEnd = fileStart

    for (piece in firstPiece..lastPiece) {
        if (!hasPiece(piece)) {
            break
        }

        val pieceStart = piece.toLong() * pieceLength
        availableEnd = minOf(fileEnd, pieceStart + pieceSize(piece))
    }

    return (availableEnd - fileStart).coerceIn(0, fileSize)
}
