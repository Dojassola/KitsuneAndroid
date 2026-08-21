package com.kitsuneandroid

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

private const val EPISODE_ARTWORK_WIDTH = 480
private const val EPISODE_ARTWORK_HEIGHT = 270
private const val FALLBACK_FRAME_TIME_US = 60_000_000L
private val episodeArtworkLock = Any()

internal fun offlineEpisodeArtworkFile(
    context: Context,
    download: TorrentDownload
): File? {
    val video = download.videoPath?.let(::File)?.takeIf(File::isFile) ?: return null
    val fileId = download.videoFileIndex ?: video.name.hashCode()
    val directory = File(context.filesDir, "episode-thumbnails")
    return File(directory, "${download.infoHash}-$fileId.jpg")
}

internal fun cacheOfflineEpisodeArtwork(
    context: Context,
    download: TorrentDownload
): File? {
    val video = download.videoPath?.let(::File)?.takeIf(File::isFile) ?: return null
    val target = offlineEpisodeArtworkFile(context, download) ?: return null

    if (target.isFile && target.length() > 0) {
        return target
    }

    // ponytail: one decoder at a time avoids vendor codec crashes; use per-file locks if profiling shows contention.
    return synchronized(episodeArtworkLock) {
        if (target.isFile && target.length() > 0) {
            return@synchronized target
        }

        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            val frameTimeUs = durationMs
                ?.takeIf { duration -> duration > 0 }
                ?.let(::episodeArtworkTimeUs)
                ?: FALLBACK_FRAME_TIME_US
            val frame = episodeFrame(retriever, frameTimeUs) ?: return@synchronized null

            try {
                temporary.outputStream().use { output ->
                    check(frame.compress(Bitmap.CompressFormat.JPEG, 84, output)) {
                        "Não foi possível salvar a miniatura do episódio."
                    }
                }
            } finally {
                frame.recycle()
            }

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            target.takeIf { file -> file.isFile && file.length() > 0 }
        } catch (_: Exception) {
            temporary.delete()
            null
        } finally {
            try {
                retriever.release()
            } catch (_: RuntimeException) {
                Unit
            }
        }
    }
}

internal fun episodeArtworkTimeUs(durationMs: Long): Long {
    return durationMs.coerceAtLeast(0) * 200L
}

private fun episodeFrame(
    retriever: MediaMetadataRetriever,
    frameTimeUs: Long
): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        return retriever.getScaledFrameAtTime(
            frameTimeUs,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            EPISODE_ARTWORK_WIDTH,
            EPISODE_ARTWORK_HEIGHT
        )
    }

    val original = retriever.getFrameAtTime(
        frameTimeUs,
        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    ) ?: return null
    val scaled = Bitmap.createScaledBitmap(
        original,
        EPISODE_ARTWORK_WIDTH,
        EPISODE_ARTWORK_HEIGHT,
        true
    )

    if (scaled !== original) {
        original.recycle()
    }

    return scaled
}
