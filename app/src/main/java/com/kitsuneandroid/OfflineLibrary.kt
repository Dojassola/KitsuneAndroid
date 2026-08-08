package com.kitsuneandroid

import android.content.Context
import java.io.File

internal fun offlineLibraryEpisodes(
    context: Context,
    downloads: List<TorrentDownload>
): List<TorrentDownload> {
    val episodes = mutableListOf<TorrentDownload>()

    for (download in downloads) {
        val torrentEpisodes = offlineTorrentEpisodes(context, download)

        if (torrentEpisodes.isNotEmpty()) {
            episodes.addAll(torrentEpisodes)
            continue
        }

        val videoPath = download.videoPath

        if (
            download.status == TorrentStatus.COMPLETED &&
            videoPath != null &&
            File(videoPath).isFile
        ) {
            episodes.add(download)
        }
    }

    return episodes
        .distinctBy { download -> download.videoPath }
        .sortedWith(
            compareBy<TorrentDownload> { download -> download.animeTitle.orEmpty() }
                .thenBy { download -> download.episode ?: Int.MAX_VALUE }
                .thenBy { download -> download.name }
        )
}

internal fun offlineAnimeIds(episodes: List<TorrentDownload>): Set<Int> {
    return episodes
        .mapNotNull { episode -> episode.animeId }
        .toSet()
}

internal fun offlineEpisode(
    episodes: List<TorrentDownload>,
    animeId: Int,
    episodeNumber: Int
): TorrentDownload? {
    return episodes.firstOrNull { download ->
        download.animeId == animeId && download.episode == episodeNumber
    }
}

internal fun nextOfflineEpisode(
    episodes: List<TorrentDownload>,
    current: TorrentDownload
): TorrentDownload? {
    val currentEpisode = current.episode

    if (currentEpisode == null) {
        return null
    }

    return episodes
        .asSequence()
        .filter { candidate -> sameAnime(current, candidate) }
        .filter { candidate -> candidate.episode != null && candidate.episode > currentEpisode }
        .minByOrNull { candidate -> candidate.episode ?: Int.MAX_VALUE }
}

internal fun previousOfflineEpisode(
    episodes: List<TorrentDownload>,
    current: TorrentDownload
): TorrentDownload? {
    val currentEpisode = current.episode

    if (currentEpisode == null) {
        return null
    }

    return episodes
        .asSequence()
        .filter { candidate -> sameAnime(current, candidate) }
        .filter { candidate -> candidate.episode != null && candidate.episode < currentEpisode }
        .maxByOrNull { candidate -> candidate.episode ?: Int.MIN_VALUE }
}

private fun offlineTorrentEpisodes(
    context: Context,
    download: TorrentDownload
): List<TorrentDownload> {
    val torrentInfo = readTorrentInfo(context, download.infoHash)

    if (torrentInfo == null) {
        return emptyList()
    }

    val selectedFiles = if (download.completedFileIndices.isNotEmpty()) {
        download.completedFileIndices.toSet()
    } else if (download.status == TorrentStatus.COMPLETED) {
        download.selectedFileIndices.toSet()
    } else {
        emptySet()
    }

    if (selectedFiles.isEmpty()) {
        return emptyList()
    }

    return torrentFileChoices(torrentInfo)
        .asSequence()
        .filter { file -> file.isVideo && file.index in selectedFiles }
        .mapNotNull { file -> completedEpisodeDownload(context, download, file) }
        .toList()
}

private fun completedEpisodeDownload(
    context: Context,
    download: TorrentDownload,
    file: TorrentFileChoice
): TorrentDownload? {
    val video = File(torrentDownloadDirectory(context), file.path)

    if (!video.isFile || video.length() < file.sizeBytes) {
        return null
    }

    val parsedEpisode = parseReleaseTitle(video.name).episode
    val episode: Int?

    if (parsedEpisode != null) {
        episode = parsedEpisode
    } else if (file.index == download.videoFileIndex) {
        episode = download.episode
    } else {
        episode = null
    }

    if (episode == null) {
        return null
    }

    return download.copy(
        progress = 1f,
        downloadedBytes = file.sizeBytes,
        videoPath = video.absolutePath,
        episode = episode,
        streamableBytes = file.sizeBytes,
        videoSizeBytes = file.sizeBytes,
        videoFileIndex = file.index
    )
}

private fun sameAnime(first: TorrentDownload, second: TorrentDownload): Boolean {
    val firstAnimeId = first.animeId
    val secondAnimeId = second.animeId

    if (firstAnimeId != null && secondAnimeId != null) {
        return firstAnimeId == secondAnimeId
    }

    val firstTitle = first.animeTitle
    val secondTitle = second.animeTitle

    if (firstTitle.isNullOrBlank() || secondTitle.isNullOrBlank()) {
        return false
    }

    return firstTitle.equals(secondTitle, ignoreCase = true)
}
