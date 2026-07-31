@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

private const val STREAM_SCHEME = "kitsune-stream"

internal fun playbackUri(download: TorrentDownload): Uri {
    val path = requireNotNull(download.videoPath)
    val file = File(path)
    return if (download.status != "completed" && file.extension.equals("mkv", ignoreCase = true)) {
        Uri.Builder()
            .scheme(STREAM_SCHEME)
            .path(file.absolutePath)
            .appendQueryParameter("hash", download.infoHash)
            .build()
    } else {
        Uri.fromFile(file)
    }
}

internal fun localVideoFile(uri: Uri): File? =
    uri.path?.let(::File)?.takeIf { uri.scheme == "file" || uri.scheme == STREAM_SCHEME }

internal class KitsuneDataSourceFactory(context: Context) : DataSource.Factory {
    private val applicationContext = context.applicationContext

    override fun createDataSource(): DataSource = RoutingDataSource(applicationContext)
}

private class RoutingDataSource(context: Context) : DataSource {
    private val standard = DefaultDataSource.Factory(context).createDataSource()
    private val growing = GrowingFileDataSource(context)
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        standard.addTransferListener(transferListener)
        growing.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.scheme == STREAM_SCHEME) growing else standard
        return requireNotNull(active).open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }
}

private class GrowingFileDataSource(context: Context) : BaseDataSource(false) {
    private val downloadRoot = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
        "Kitsune"
    ).canonicalFile
    private var source: RandomAccessFile? = null
    private var infoHash = ""
    private var remaining = C.LENGTH_UNSET.toLong()
    private var position = 0L
    private var currentUri: Uri? = null
    private var opened = false
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("Caminho do vídeo ausente.")
        currentUri = dataSpec.uri
        infoHash = dataSpec.uri.getQueryParameter("hash").orEmpty()
        if (!infoHash.matches(Regex("[a-fA-F0-9]{40}"))) throw IOException("Torrent inválido para streaming.")
        val file = File(path).canonicalFile
        if (!file.path.startsWith(downloadRoot.path + File.separator)) throw IOException("Caminho de streaming inválido.")
        source = RandomAccessFile(file, "r").apply { seek(dataSpec.position) }
        position = dataSpec.position
        remaining = dataSpec.length
        closed = false
        opened = true
        transferStarted(dataSpec)
        if (remaining != C.LENGTH_UNSET.toLong()) return remaining
        val status = TorrentStore.get(infoHash)?.status
        return if (status == null || status == "completed") (file.length() - dataSpec.position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        while (!closed) {
            val state = TorrentStore.get(infoHash)
            val complete = state == null || state.status == "completed"
            val available = if (complete) requested.toLong() else state.streamableBytes - position
            if (available <= 0) {
                if (state?.status == "failed") return C.RESULT_END_OF_INPUT
                waitForData()
                continue
            }
            val read = requireNotNull(source).read(buffer, offset, minOf(requested.toLong(), available).toInt())
            if (read > 0) {
                position += read
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
                bytesTransferred(read)
                return read
            }
            if (complete || state?.status == "failed") return C.RESULT_END_OF_INPUT
            waitForData()
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun waitForData() {
        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Streaming interrompido.")
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closed = true
        source?.close()
        source = null
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
