package com.kitsuneandroid

import android.content.Context
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_TORRENT_BYTES = 5 * 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024 * 1024

internal fun validateTorrentMetadataSize(bytes: ByteArray) {
    require(bytes.size <= MAX_TORRENT_BYTES) {
        "Arquivo .torrent grande demais."
    }
}

internal fun fetchMagnetMetadata(
    context: Context,
    magnetUri: String
): ByteArray {
    require(magnetUri.startsWith("magnet:?xt=urn:btih:")) {
        "Magnet inválido."
    }
    val manager = SessionManager()
    val temporaryDirectory = File(context.cacheDir, "magnet-metadata").apply {
        mkdirs()
    }

    try {
        manager.start()
        manager.applySettings(
            SettingsPack().apply {
                setEnableDht(true)
            }
        )
        val bytes = manager.fetchMagnet(
            magnetUri,
            20,
            temporaryDirectory
        )

        if (bytes.isEmpty()) {
            throw IOException("Não foi possível obter os metadados do magnet.")
        }

        return bytes
    } finally {
        if (manager.isRunning) {
            manager.stop()
        }
    }
}

internal fun downloadTorrent(releaseId: String): ByteArray {
    require(releaseId.matches(Regex("\\d{1,12}"))) {
        "Identificador de release inválido."
    }
    val connection = URL("https://nyaa.si/download/$releaseId.torrent")
        .openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/x-bittorrent")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")

    return try {
        if (connection.responseCode !in 200..299) {
            throw IOException("Nyaa HTTP ${connection.responseCode}")
        }

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)

        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)

                if (read < 0) {
                    break
                }

                output.write(buffer, 0, read)

                if (output.size() > MAX_TORRENT_BYTES) {
                    throw IOException("Arquivo .torrent grande demais.")
                }
            }
        }

        output.toByteArray()
    } finally {
        connection.disconnect()
    }
}

internal fun validateTorrent(info: TorrentInfo) {
    val files = info.files()
    val validFileCount = files.numFiles() in 1..500
    val validTotalSize = info.totalSize() in 1..MAX_DOWNLOAD_BYTES

    require(validFileCount && validTotalSize) {
        "Estrutura de torrent inválida."
    }

    var containsVideo = false

    repeat(files.numFiles()) { index ->
        val path = files.filePath(index)
        val normalizedParts = path.replace('\\', '/').split('/')
        val safePath = path.isNotBlank() &&
            '\u0000' !in path &&
            !File(path).isAbsolute &&
            normalizedParts.none { part -> part == ".." }

        require(safePath) {
            "O torrent contém um caminho inseguro."
        }

        if (File(path).extension.lowercase() in torrentVideoExtensions) {
            containsVideo = true
        }
    }

    require(containsVideo) {
        "A release não contém vídeo reconhecido."
    }
}

internal fun validateSelection(
    info: TorrentInfo,
    selectedFiles: List<Int>,
    videoFile: Int?
) {
    if (selectedFiles.isEmpty()) {
        return
    }

    val files = info.files()
    val uniqueFiles = selectedFiles.distinct().size == selectedFiles.size
    val validFiles = selectedFiles.all { index ->
        index in 0 until files.numFiles() &&
            File(files.filePath(index)).extension.lowercase() in torrentDownloadableExtensions
    }

    require(uniqueFiles && validFiles) {
        "A seleção contém um arquivo inválido."
    }

    val validVideo = videoFile != null &&
        videoFile in selectedFiles &&
        File(files.filePath(videoFile)).extension.lowercase() in torrentVideoExtensions

    require(validVideo) {
        "Selecione ao menos um vídeo."
    }
}
