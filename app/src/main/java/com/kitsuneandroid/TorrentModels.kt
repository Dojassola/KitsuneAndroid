package com.kitsuneandroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.io.File
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

enum class TorrentStatus(val persistedValue: String, val displayName: String) {
    QUEUED("queued", "Na fila"),
    SEARCHING_PEERS("procurando peers", "Procurando peers"),
    DOWNLOADING("downloading", "Baixando"),
    STALLED("stalled", "Sem receber dados"),
    PAUSED("paused", "Pausado"),
    COMPLETED("completed", "Concluído"),
    FAILED("failed", "Falhou");

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
    val isActive: Boolean get() = !isTerminal && this != PAUSED

    companion object {
        fun fromPersisted(value: String): TorrentStatus = entries.firstOrNull { it.persistedValue == value } ?: QUEUED
    }
}

data class TorrentDownload(
    val releaseId: String,
    val infoHash: String,
    val name: String,
    val status: TorrentStatus,
    val progress: Float,
    val downloadSpeed: Long,
    val downloadedBytes: Long,
    val sizeBytes: Long,
    val peers: Int,
    val videoPath: String?,
    val error: String?,
    val animeId: Int? = null,
    val animeTitle: String? = null,
    val animeCoverUrl: String? = null,
    val animeCoverPath: String? = null,
    val episode: Int? = null,
    val streamableBytes: Long = 0,
    val videoSizeBytes: Long = 0,
    val selectedFileIndices: List<Int> = emptyList(),
    val completedFileIndices: List<Int> = emptyList(),
    val videoFileIndex: Int? = null,
    val connectedSeeders: Int = 0,
    val knownPeers: Int = 0,
    val connectionCandidates: Int = 0,
    val trackerSeeders: Int? = null
)

data class TorrentFileChoice(val index: Int, val path: String, val sizeBytes: Long, val isVideo: Boolean)

object TorrentStore {
    val downloads = mutableStateListOf<TorrentDownload>()
    private val main = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, TorrentDownload>()

    fun load(context: Context): Boolean {
        val loaded = torrentMetadataDirectory(context).listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull(::readDownload)
        states.clear()
        loaded.forEach { download ->
            states[download.infoHash] = download
        }
        main.post {
            downloads.clear()
            downloads.addAll(loaded.sortedByDescending { it.status == TorrentStatus.DOWNLOADING })
        }
        return loaded.any { it.status.isActive }
    }

    private fun readDownload(file: File): TorrentDownload? {
        return try {
            downloadFromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    fun upsert(download: TorrentDownload) {
        states[download.infoHash] = download
        main.post {
            val index = downloads.indexOfFirst { it.infoHash == download.infoHash }

            if (index < 0) {
                downloads.add(download)
            } else {
                downloads[index] = download
            }
        }
    }

    fun get(infoHash: String): TorrentDownload? = states[infoHash]

    fun remove(infoHash: String) {
        states.remove(infoHash)
        main.post {
            downloads.removeAll { download -> download.infoHash == infoHash }
        }
    }
}

internal fun torrentStatus(
    hasError: Boolean,
    completed: Boolean,
    paused: Boolean,
    peers: Int,
    downloadRate: Int,
    now: Long,
    lastPayloadAt: Long
): TorrentStatus = when {
    hasError -> TorrentStatus.FAILED
    completed -> TorrentStatus.COMPLETED
    paused -> TorrentStatus.PAUSED
    peers == 0 -> TorrentStatus.SEARCHING_PEERS
    downloadRate == 0 && now - lastPayloadAt >= 20_000 -> TorrentStatus.STALLED
    else -> TorrentStatus.DOWNLOADING
}

internal data class TorrentStreamSnapshot(
    val fileStart: Long,
    val fileSize: Long,
    val pieceLength: Int,
    val completed: BitSet
) {
    fun availableBytes(position: Long, maximum: Long): Long {
        if (position !in 0 until fileSize || maximum <= 0 || pieceLength <= 0) {
            return 0
        }

        val start = fileStart + position
        val end = minOf(fileStart + fileSize, start + maximum)
        var cursor = start

        while (cursor < end) {
            val piece = (cursor / pieceLength).toInt()

            if (!completed[piece]) {
                break
            }

            cursor = minOf(end, (piece + 1L) * pieceLength)
        }

        return cursor - start
    }

    fun downloadedFractions(bucketCount: Int): FloatArray {
        if (bucketCount <= 0 || fileSize <= 0 || pieceLength <= 0) {
            return FloatArray(0)
        }

        return FloatArray(bucketCount) { bucket ->
            val start = fileStart + fileSize * bucket / bucketCount
            val end = fileStart + fileSize * (bucket + 1L) / bucketCount
            var cursor = start
            var downloaded = 0L

            while (cursor < end) {
                val piece = (cursor / pieceLength).toInt()
                val pieceEnd = minOf(end, (piece + 1L) * pieceLength)

                if (completed[piece]) {
                    downloaded += pieceEnd - cursor
                }

                cursor = pieceEnd
            }

            val bucketSize = end - start
            if (bucketSize > 0) downloaded.toFloat() / bucketSize else 0f
        }
    }
}

internal object TorrentStreamStore {
    private val snapshots = ConcurrentHashMap<String, TorrentStreamSnapshot>()

    fun update(hash: String, fileStart: Long, fileSize: Long, pieceLength: Int, firstPiece: Int, lastPiece: Int, pieces: org.libtorrent4j.PieceIndexBitfield): TorrentStreamSnapshot {
        val completed = BitSet(lastPiece + 1)

        if (!pieces.isEmpty) {
            for (piece in firstPiece..lastPiece) {
                if (piece < pieces.size() && pieces.getBit(piece)) {
                    completed.set(piece)
                }
            }
        }

        return TorrentStreamSnapshot(fileStart, fileSize, pieceLength, completed).also { snapshots[hash] = it }
    }

    fun availableBytes(hash: String, position: Long, maximum: Long): Long =
        snapshots[hash]?.availableBytes(position, maximum) ?: 0

    fun downloadedFractions(hash: String, bucketCount: Int): FloatArray =
        snapshots[hash]?.downloadedFractions(bucketCount) ?: FloatArray(0)

    fun remove(hash: String) {
        snapshots.remove(hash)
    }
}
