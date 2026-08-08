package com.kitsuneandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val MAX_TORRENT_BYTES = 5 * 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024 * 1024

class TorrentService : Service(), AlertListener {
    private data class Record(
        val releaseId: String,
        val title: String,
        val infoHash: String,
        val torrentFile: File,
        var metadata: TorrentDownload,
        var paused: Boolean = false,
        var error: String? = null,
        var lastPersistAt: Long = 0,
        var lastPeerSearchAt: Long = 0,
        var priorityFirstPiece: Int = -1,
        var priorityLastPiece: Int = -1
    )

    private val sessionHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SessionManager() }
    private val session: SessionManager get() = sessionHolder.value
    private val records = ConcurrentHashMap<String, Record>()
    private val streamPositions = ConcurrentHashMap<String, Long>()
    private val work = Executors.newSingleThreadScheduledExecutor()
    private lateinit var downloadDirectory: File

    override fun onCreate() {
        super.onCreate()
        downloadDirectory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "Kitsune").apply { mkdirs() }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparando downloads", 0))
        work.execute {
            runCatching {
                session.addListener(this)
                session.start()
                session.applySettings(SettingsPack().connectionsLimit(240).uploadRateLimit(512 * 1024).activeDownloads(2).apply { setEnableDht(true) })
                if (!work.isShutdown) work.scheduleWithFixedDelay(::poll, 1, 1, TimeUnit.SECONDS)
            }.onFailure { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> work.execute {
                val releaseId = intent.getStringExtra(EXTRA_ID).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val expectedHash = intent.getStringExtra(EXTRA_HASH).orEmpty()
                val selectedFiles = (intent.getIntArrayExtra(EXTRA_FILES) ?: intArrayOf()).toList()
                val videoFile = intent.getIntExtra(EXTRA_VIDEO_FILE, -1).takeIf { it >= 0 }
                runCatching {
                    add(releaseId, title, expectedHash, selectedFiles, videoFile)
                }.onFailure { failurePending(releaseId, title, expectedHash, it) }
            }
            ACTION_PAUSE -> work.execute { control(intent.getStringExtra(EXTRA_HASH), true) }
            ACTION_RESUME -> work.execute { control(intent.getStringExtra(EXTRA_HASH), false) }
            ACTION_REMOVE -> work.execute { remove(intent.getStringExtra(EXTRA_HASH).orEmpty()) }
            ACTION_STREAM -> work.execute { prioritizeStream(intent.getStringExtra(EXTRA_HASH).orEmpty(), intent.getLongExtra(EXTRA_POSITION, 0)) }
            else -> work.execute { restore() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun types(): IntArray? = null

    override fun alert(alert: Alert<*>) {
        runCatching { work.execute { processAlert(alert) } }
    }

    private fun processAlert(alert: Alert<*>) {
        runCatching {
            when (alert) {
                is AddTorrentAlert -> {
                    if (alert.error().isError) failure(alert.handle().infoHash().toHex(), IOException(alert.error().message))
                    else configureFiles(alert.handle())
                }
                is TorrentFinishedAlert -> {
                    alert.handle().pause()
                    update(alert.handle(), completed = true)
                    records.remove(alert.handle().infoHash().toHex())
                    stopIfIdle()
                }
                is TorrentErrorAlert -> failure(alert.handle().infoHash().toHex(), IOException(alert.error().message))
            }
        }.onFailure { error ->
            val hash = runCatching {
                when (alert) {
                    is AddTorrentAlert -> alert.handle().infoHash().toHex()
                    is TorrentFinishedAlert -> alert.handle().infoHash().toHex()
                    is TorrentErrorAlert -> alert.handle().infoHash().toHex()
                    else -> null
                }
            }.getOrNull()
            failure(hash, error)
        }
    }

    private fun add(releaseId: String, title: String, expectedHash: String, selectedFiles: List<Int>, videoFile: Int?) {
        require(releaseId.matches(Regex("\\d{1,12}"))) { "Identificador de release inválido." }
        require(expectedHash.matches(Regex("[a-fA-F0-9]{40}"))) { "Hash da release inválido." }
        records[expectedHash]?.let { record ->
            TorrentStore.get(expectedHash)?.let { record.metadata = it }
            record.error = null
            record.paused = false
            val existing = findValidTorrent(expectedHash)
            if (existing != null) {
                configureFiles(existing)
                existing.resume()
            } else session.download(TorrentInfo(record.torrentFile), downloadDirectory)
            persist(record, record.metadata)
            return
        }
        val cachedTorrent = File(torrentMetadataDirectory(this), "$expectedHash.torrent")
        val bytes = if (cachedTorrent.isFile) cachedTorrent.readBytes() else downloadTorrent(releaseId)
        val info = TorrentInfo(bytes)
        validateTorrent(info)
        val hash = info.infoHash().toHex()
        require(hash.equals(expectedHash, ignoreCase = true)) { "O arquivo recebido não corresponde à release selecionada." }
        if (records.containsKey(hash)) return
        val torrentFile = File(torrentMetadataDirectory(this), "$hash.torrent").apply { if (!isFile) writeBytes(bytes) }
        val pending = TorrentStore.get(hash) ?: TorrentDownload(
            releaseId, hash, title, "queued", 0f, 0, 0, info.totalSize(), 0, null, null
        )
        val requestedFiles = selectedFiles.ifEmpty { pending.selectedFileIndices }
        val requestedVideo = videoFile ?: pending.videoFileIndex
        validateSelection(info, requestedFiles, requestedVideo)
        val metadata = pending.copy(
            name = title.take(500).ifBlank { info.name() },
            sizeBytes = info.totalSize(),
            animeCoverPath = pending.animeCoverPath ?: cacheAnimeCover(pending.animeId, pending.animeCoverUrl),
            selectedFileIndices = requestedFiles,
            videoFileIndex = requestedVideo
        )
        records[hash] = Record(releaseId, metadata.name, hash, torrentFile, metadata)
        TorrentStore.upsert(metadata)
        persist(records.getValue(hash), metadata)
        val existing = findValidTorrent(hash)
        if (existing != null) {
            configureFiles(existing)
            existing.resume()
        } else session.download(info, downloadDirectory)
    }

    private fun restore() {
        torrentMetadataDirectory(this).listFiles { file -> file.extension == "json" }.orEmpty().forEach { jsonFile ->
            runCatching {
                val saved = downloadFromJson(JSONObject(jsonFile.readText()))
                TorrentStore.upsert(saved)
                if (saved.status in setOf("completed", "paused", "failed")) return@runCatching
                if (records.containsKey(saved.infoHash)) return@runCatching
                val torrentFile = File(torrentMetadataDirectory(this), "${saved.infoHash}.torrent")
                if (!torrentFile.isFile) return@runCatching
                val info = TorrentInfo(torrentFile)
                validateTorrent(info)
                records[saved.infoHash] = Record(
                    saved.releaseId, saved.name, saved.infoHash, torrentFile, saved,
                    paused = saved.status == "paused"
                )
                session.download(info, downloadDirectory)
            }
        }
        if (records.isEmpty()) stopSelf()
    }

    private fun control(hash: String?, pause: Boolean) {
        if (hash == null) return
        if (!records.containsKey(hash)) {
            val jsonFile = File(torrentMetadataDirectory(this), "$hash.json")
            if (!jsonFile.isFile) return
            val saved = downloadFromJson(JSONObject(jsonFile.readText()))
            val torrentFile = File(torrentMetadataDirectory(this), "$hash.torrent")
            if (!torrentFile.isFile || saved.status == "completed") return
            records[hash] = Record(saved.releaseId, saved.name, hash, torrentFile, saved, paused = pause)
            session.download(TorrentInfo(torrentFile), downloadDirectory)
            return
        }
        records[hash]?.let {
            it.paused = pause
            if (pause) it.lastPersistAt = 0
        }
        val handle = findValidTorrent(hash)
        if (handle != null) {
            if (pause) handle.pause() else handle.resume()
            update(handle)
        }
        if (pause) stopIfIdle()
    }

    private fun remove(hash: String) {
        val record = records.remove(hash)
        val handle = findValidTorrent(hash)
        val torrentFile = record?.torrentFile ?: File(torrentMetadataDirectory(this), "$hash.torrent")
        val info = when {
            handle != null -> handle.torrentFile()
            torrentFile.isFile -> runCatching { TorrentInfo(torrentFile) }.getOrNull()
            else -> null
        }
        if (handle != null) session.remove(handle)
        if (info != null) {
            repeat(info.files().numFiles()) { index -> safeDelete(File(downloadDirectory, info.files().filePath(index))) }
        }
        torrentFile.delete()
        File(torrentMetadataDirectory(this), "$hash.json").delete()
        TorrentStore.remove(hash)
        TorrentStreamStore.remove(hash)
        if (records.isEmpty()) stopSelf()
    }

    private fun prioritizeStream(hash: String, position: Long) {
        streamPositions[hash] = position
        if (!records.containsKey(hash)) {
            control(hash, false)
            return
        }
        val record = records.getValue(hash)
        val handle = findValidTorrent(hash) ?: return
        val info = handle.torrentFile()
        val index = videoFileIndex(info, record.metadata) ?: return
        handle.queuePositionTop()
        val files = info.files()
        val fileSize = files.fileSize(index)
        if (fileSize <= 0) return
        val first = ((files.fileOffset(index) + position.coerceIn(0, fileSize - 1)) / info.pieceLength()).toInt()
        val urgentLast = priorityWindowLast(first, files.lastPieceIndexAtFile(index), STREAM_START_BYTES, info.pieceLength())
        val last = priorityWindowLast(first, files.lastPieceIndexAtFile(index), STREAM_BUFFER_BYTES, info.pieceLength())
        prioritizePieces(handle, record, first, last, urgentLast)
        if (record.paused) {
            record.paused = false
            handle.resume()
        }
        streamPositions.remove(hash, position)
    }

    private fun prioritizePieces(handle: TorrentHandle, record: Record, first: Int, last: Int, urgentLast: Int) {
        if (record.priorityFirstPiece >= 0) for (piece in record.priorityFirstPiece..record.priorityLastPiece) {
            if (piece < first || piece > last) handle.piecePriority(piece, Priority.DEFAULT)
        }
        handle.clearPieceDeadlines()
        for (piece in first..last) {
            handle.piecePriority(piece, Priority.TOP_PRIORITY)
            if (piece <= urgentLast) handle.setPieceDeadline(piece, (piece - first) * 10)
        }
        record.priorityFirstPiece = first
        record.priorityLastPiece = last
    }

    private fun configureFiles(handle: TorrentHandle) {
        val info = handle.torrentFile()
        val metadata = records[handle.infoHash().toHex()]?.metadata
        val selected = metadata?.selectedFileIndices.orEmpty().toSet()
        val mainVideo = metadata?.let { videoFileIndex(info, it) } ?: largestVideoIndex(info)
        val priorities = Array(info.files().numFiles()) { index ->
            when {
                index == mainVideo -> Priority.TOP_PRIORITY
                selected.isNotEmpty() && index in selected -> Priority.DEFAULT
                selected.isNotEmpty() -> Priority.IGNORE
                File(info.files().filePath(index)).extension.lowercase() in torrentDownloadableExtensions -> Priority.DEFAULT
                else -> Priority.IGNORE
            }
        }
        handle.prioritizeFiles(priorities)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        metadata?.episode?.plus(1)?.let { nextEpisode ->
            selected.firstOrNull { index ->
                index != mainVideo && File(info.files().filePath(index)).let { file ->
                    file.extension.lowercase() in torrentVideoExtensions && parseReleaseTitle(file.name).episode == nextEpisode
                }
            }?.let { index ->
                val first = (info.files().fileOffset(index) / info.pieceLength()).toInt()
                val last = priorityWindowLast(first, info.files().lastPieceIndexAtFile(index), STARTUP_PRIORITY_BYTES, info.pieceLength())
                for (piece in first..last) handle.piecePriority(piece, Priority.TOP_PRIORITY)
            }
        }
        records[handle.infoHash().toHex()]?.let {
            mainVideo?.let { index ->
                val first = (info.files().fileOffset(index) / info.pieceLength()).toInt()
                val fileLast = info.files().lastPieceIndexAtFile(index)
                prioritizePieces(
                    handle, it, first,
                    priorityWindowLast(first, fileLast, STARTUP_PRIORITY_BYTES, info.pieceLength()),
                    priorityWindowLast(first, fileLast, STREAM_START_BYTES, info.pieceLength())
                )
            }
            handle.forceReannounce()
            handle.forceDHTAnnounce()
            runCatching { handle.scrapeTracker() }
            it.lastPeerSearchAt = System.currentTimeMillis()
            if (it.paused) {
                handle.pause()
                stopIfIdle()
            }
        }
        val hash = handle.infoHash().toHex()
        streamPositions[hash]?.let { prioritizeStream(hash, it) }
    }

    private fun poll() {
        records.values.forEach { record ->
            runCatching {
                val handle = findValidTorrent(record.infoHash) ?: return@runCatching
                update(handle)
            }.onFailure { failure(record.infoHash, it) }
        }
        val active = records.values.filter { !it.paused && it.error == null }
        val progressValues = active.mapNotNull { record ->
            runCatching { findValidTorrent(record.infoHash)?.status()?.progress() }.getOrNull()
        }
        val progress = if (progressValues.isEmpty()) 0 else (progressValues.average() * 100).toInt()
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, notification(if (active.isEmpty()) "Downloads concluídos" else "${active.size} download(s) ativo(s)", progress)) }
    }

    private fun findValidTorrent(hash: String): TorrentHandle? = runCatching {
        session.find(Sha1Hash.parseHex(hash))
    }.getOrNull()?.takeIf { it.isValid }

    private fun update(handle: TorrentHandle, completed: Boolean = false) {
        val status = handle.status(TorrentHandle.QUERY_PIECES)
        val hash = status.infoHashes.getBest().toHex()
        val record = records[hash] ?: return
        val now = System.currentTimeMillis()
        if (!record.paused && !status.isFinished && status.downloadRate() == 0 && now - record.lastPeerSearchAt >= 45_000) {
            handle.forceReannounce()
            handle.forceDHTAnnounce()
            record.lastPeerSearchAt = now
        }
        val info = handle.torrentFile()
        val videoIndex = videoFileIndex(info, record.metadata)
        val video = videoIndex?.let { File(downloadDirectory, info.files().filePath(it)) }
        val download = record.metadata.copy(
            status = when { record.error != null -> "failed"; completed || status.isFinished -> "completed"; record.paused -> "paused"; status.numPeers() == 0 -> "procurando peers"; else -> "downloading" },
            progress = status.progress().coerceIn(0f, 1f),
            downloadSpeed = status.downloadRate().toLong().coerceAtLeast(0),
            downloadedBytes = status.totalDone().coerceAtLeast(0),
            sizeBytes = status.totalWanted().coerceAtLeast(0),
            peers = status.numPeers().coerceAtLeast(0),
            videoPath = video?.absolutePath,
            error = record.error,
            streamableBytes = contiguousVideoBytes(hash, info, status.pieces(), videoIndex),
            connectedSeeders = status.numSeeds().coerceAtLeast(0),
            knownPeers = status.listPeers().coerceAtLeast(0),
            connectionCandidates = status.connectCandidates().coerceAtLeast(0),
            trackerSeeders = status.numComplete().takeIf { it >= 0 }
        )
        record.metadata = download
        TorrentStore.upsert(download)
        if (now - record.lastPersistAt >= 5_000 || download.status == "completed") {
            persist(record, download)
            record.lastPersistAt = now
        }
    }

    private fun failure(hash: String?, error: Throwable) {
        val record = hash?.let(records::get) ?: return
        record.error = error.message?.take(500) ?: "Falha no download torrent."
        val failed = record.metadata.copy(status = "failed", downloadSpeed = 0, peers = 0, error = record.error)
        TorrentStore.upsert(failed)
        runCatching { persist(record, failed) }
        stopIfIdle()
    }

    private fun failurePending(releaseId: String, title: String, hash: String, error: Throwable) {
        val record = records[hash]
        if (record != null) return failure(hash, error)
        val message = error.message?.take(500) ?: "Falha no download torrent."
        TorrentStore.upsert(
            (TorrentStore.get(hash) ?: TorrentDownload(releaseId, hash, title, "failed", 0f, 0, 0, 0, 0, null, message))
                .copy(status = "failed", error = message)
        )
    }

    private fun persist(record: Record, download: TorrentDownload) {
        File(torrentMetadataDirectory(this), "${record.infoHash}.json").writeText(downloadToJson(download).toString())
    }

    private fun cacheAnimeCover(animeId: Int?, url: String?): String? = runCatching {
        if (animeId == null || url.isNullOrBlank()) return@runCatching null
        val source = URL(url)
        require(source.protocol == "https") { "URL de capa inválida." }
        val directory = File(filesDir, "anime-covers").apply { mkdirs() }
        val target = File(directory, "$animeId.img")
        if (target.isFile && target.length() > 0) return@runCatching target.absolutePath
        val temporary = File(directory, "$animeId.tmp")
        val connection = source.openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "image/*")
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
        try {
            if (connection.responseCode !in 200..299 || connection.url.protocol != "https") throw IOException("Não foi possível salvar a capa.")
            if (!connection.contentType.orEmpty().startsWith("image/")) throw IOException("A capa recebida não é uma imagem.")
            if (connection.contentLengthLong > MAX_COVER_BYTES) throw IOException("Capa grande demais.")
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_COVER_BYTES) throw IOException("Capa grande demais.")
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(total > 0) { "Capa vazia." }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.absolutePath
        } finally {
            connection.disconnect()
            if (!target.isFile) temporary.delete()
        }
    }.getOrNull()

    private fun largestVideoIndex(info: TorrentInfo): Int? = (0 until info.files().numFiles())
        .filter { File(info.files().filePath(it)).extension.lowercase() in torrentVideoExtensions }
        .maxByOrNull { info.files().fileSize(it) }

    private fun videoFileIndex(info: TorrentInfo, download: TorrentDownload): Int? {
        val files = info.files()
        fun isVideo(index: Int) = index in 0 until files.numFiles() && File(files.filePath(index)).extension.lowercase() in torrentVideoExtensions
        return download.videoFileIndex?.takeIf(::isVideo)
            ?: download.selectedFileIndices.firstOrNull(::isVideo)
            ?: largestVideoIndex(info)
    }

    private fun contiguousVideoBytes(hash: String, info: TorrentInfo, pieces: org.libtorrent4j.PieceIndexBitfield, index: Int?): Long {
        val files = info.files()
        if (index == null) return 0
        val snapshot = TorrentStreamStore.update(
            hash, files.fileOffset(index), files.fileSize(index), info.pieceLength(),
            files.pieceIndexAtFile(index), files.lastPieceIndexAtFile(index), pieces
        )
        return snapshot.availableBytes(0, files.fileSize(index))
    }

    private fun safeDelete(file: File) {
        val root = downloadDirectory.canonicalFile
        val target = file.canonicalFile
        if (target.path.startsWith(root.path + File.separator)) target.delete()
    }

    private fun stopIfIdle() {
        if (records.values.none { !it.paused && it.error == null }) stopSelf()
    }

    private fun notification(text: String, progress: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Kitsune")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(records.isNotEmpty())
        .setProgress(100, progress, records.isNotEmpty() && progress == 0)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        runCatching {
            work.execute {
                if (sessionHolder.isInitialized()) {
                    session.removeListener(this)
                    session.stop()
                }
            }
        }
        work.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "kitsune_downloads"
        private const val NOTIFICATION_ID = 47
        private const val ACTION_ADD = "com.kitsuneandroid.ADD_TORRENT"
        private const val ACTION_PAUSE = "com.kitsuneandroid.PAUSE_TORRENT"
        private const val ACTION_RESUME = "com.kitsuneandroid.RESUME_TORRENT"
        private const val ACTION_REMOVE = "com.kitsuneandroid.REMOVE_TORRENT"
        private const val ACTION_STREAM = "com.kitsuneandroid.STREAM_TORRENT"
        private const val EXTRA_ID = "release_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HASH = "info_hash"
        private const val EXTRA_FILES = "file_indices"
        private const val EXTRA_VIDEO_FILE = "video_file_index"
        private const val EXTRA_POSITION = "position"
        private const val MAX_COVER_BYTES = 5L * 1024 * 1024
        private const val STREAM_START_BYTES = 1024 * 1024
        private const val STREAM_BUFFER_BYTES = 12 * 1024 * 1024
        private const val STARTUP_PRIORITY_BYTES = 32 * 1024 * 1024

        fun inspect(release: ReleaseCandidate): List<TorrentFileChoice> {
            val info = TorrentInfo(downloadTorrent(release.id))
            validateTorrent(info)
            require(info.infoHash().toHex().equals(release.infoHash, ignoreCase = true)) { "O arquivo recebido não corresponde à release selecionada." }
            val files = info.files()
            return (0 until files.numFiles()).mapNotNull { index ->
                val path = files.filePath(index)
                val extension = File(path).extension.lowercase()
                if (extension !in torrentDownloadableExtensions) null
                else TorrentFileChoice(index, path, files.fileSize(index), extension in torrentVideoExtensions)
            }
        }

        fun enqueue(context: Context, anime: Anime, episode: Int?, release: ReleaseCandidate, selectedFiles: List<Int>, videoFile: Int) {
            val previous = TorrentStore.get(release.infoHash)
            val files = (previous?.selectedFileIndices.orEmpty() + selectedFiles).distinct()
            TorrentStore.upsert(
                (previous ?: TorrentDownload(
                    releaseId = release.id,
                    infoHash = release.infoHash,
                    name = release.title,
                    status = "queued",
                    progress = 0f,
                    downloadSpeed = 0,
                    downloadedBytes = 0,
                    sizeBytes = release.sizeBytes,
                    peers = 0,
                    videoPath = null,
                    error = null,
                    animeId = anime.id,
                    animeTitle = anime.title,
                    animeCoverUrl = anime.cover,
                    episode = episode
                )).copy(
                    name = release.title,
                    status = "queued",
                    error = null,
                    animeId = anime.id,
                    animeTitle = anime.title,
                    animeCoverUrl = anime.cover,
                    episode = episode,
                    selectedFileIndices = files,
                    videoFileIndex = videoFile
                )
            )
            start(context, Intent(context, TorrentService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_ID, release.id)
                putExtra(EXTRA_TITLE, release.title)
                putExtra(EXTRA_HASH, release.infoHash)
                putExtra(EXTRA_FILES, files.toIntArray())
                putExtra(EXTRA_VIDEO_FILE, videoFile)
            })
        }

        fun pause(context: Context, hash: String) = control(context, ACTION_PAUSE, hash)
        fun resume(context: Context, download: TorrentDownload) {
            if (download.status == "failed") {
                start(context, Intent(context, TorrentService::class.java).apply {
                    action = ACTION_ADD
                    putExtra(EXTRA_ID, download.releaseId)
                    putExtra(EXTRA_TITLE, download.name)
                    putExtra(EXTRA_HASH, download.infoHash)
                    putExtra(EXTRA_FILES, download.selectedFileIndices.toIntArray())
                    download.videoFileIndex?.let { putExtra(EXTRA_VIDEO_FILE, it) }
                })
            } else control(context, ACTION_RESUME, download.infoHash)
        }
        fun remove(context: Context, hash: String) = control(context, ACTION_REMOVE, hash)
        fun prefetchEpisode(context: Context, download: TorrentDownload, target: TorrentEpisodeTarget) {
            if (target.videoFileIndex in download.selectedFileIndices) return
            val files = (download.selectedFileIndices + target.selectedFileIndices).distinct()
            val updated = download.copy(
                status = if (download.status in setOf("completed", "failed", "paused")) "queued" else download.status,
                error = null,
                selectedFileIndices = files
            )
            TorrentStore.upsert(updated)
            start(context, Intent(context, TorrentService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_ID, updated.releaseId)
                putExtra(EXTRA_TITLE, updated.name)
                putExtra(EXTRA_HASH, updated.infoHash)
                putExtra(EXTRA_FILES, files.toIntArray())
                updated.videoFileIndex?.let { putExtra(EXTRA_VIDEO_FILE, it) }
            })
        }
        fun switchEpisode(context: Context, download: TorrentDownload, target: TorrentEpisodeTarget): TorrentDownload {
            val files = (download.selectedFileIndices + target.selectedFileIndices).distinct()
            val alreadyDownloaded = download.status == "completed" && target.videoFileIndex in download.selectedFileIndices
            val updated = download.copy(
                status = if (alreadyDownloaded) "completed" else "queued",
                error = null,
                episode = target.episode,
                videoPath = target.videoPath,
                streamableBytes = 0,
                selectedFileIndices = files,
                videoFileIndex = target.videoFileIndex
            )
            TorrentStore.upsert(updated)
            if (!alreadyDownloaded) {
                start(context, Intent(context, TorrentService::class.java).apply {
                    action = ACTION_ADD
                    putExtra(EXTRA_ID, updated.releaseId)
                    putExtra(EXTRA_TITLE, updated.name)
                    putExtra(EXTRA_HASH, updated.infoHash)
                    putExtra(EXTRA_FILES, files.toIntArray())
                    putExtra(EXTRA_VIDEO_FILE, target.videoFileIndex)
                })
            }
            return updated
        }
        fun prioritizeStream(context: Context, hash: String, position: Long) = start(context, Intent(context, TorrentService::class.java).apply {
            action = ACTION_STREAM
            putExtra(EXTRA_HASH, hash)
            putExtra(EXTRA_POSITION, position)
        })
        fun restore(context: Context) = start(context, Intent(context, TorrentService::class.java))

        private fun control(context: Context, actionName: String, hash: String) = start(context, Intent(context, TorrentService::class.java).apply {
            action = actionName
            putExtra(EXTRA_HASH, hash)
        })

        private fun start(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)
    }
}

private fun downloadTorrent(releaseId: String): ByteArray {
    require(releaseId.matches(Regex("\\d{1,12}"))) { "Identificador de release inválido." }
    val connection = URL("https://nyaa.si/download/$releaseId.torrent").openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/x-bittorrent")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
    return try {
        if (connection.responseCode !in 200..299) throw IOException("Nyaa HTTP ${connection.responseCode}")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_TORRENT_BYTES) throw IOException("Arquivo .torrent grande demais.")
            }
        }
        output.toByteArray()
    } finally {
        connection.disconnect()
    }
}

private fun validateTorrent(info: TorrentInfo) {
    val files = info.files()
    require(files.numFiles() in 1..500 && info.totalSize() in 1..MAX_DOWNLOAD_BYTES) { "Estrutura de torrent inválida." }
    var video = false
    repeat(files.numFiles()) { index ->
        val path = files.filePath(index)
        require(path.isNotBlank() && '\u0000' !in path && !File(path).isAbsolute && path.replace('\\', '/').split('/').none { it == ".." }) {
            "O torrent contém um caminho inseguro."
        }
        if (File(path).extension.lowercase() in torrentVideoExtensions) video = true
    }
    require(video) { "A release não contém vídeo reconhecido." }
}

private fun validateSelection(info: TorrentInfo, selectedFiles: List<Int>, videoFile: Int?) {
    if (selectedFiles.isEmpty()) return
    val files = info.files()
    require(selectedFiles.distinct().size == selectedFiles.size && selectedFiles.all { index ->
        index in 0 until files.numFiles() && File(files.filePath(index)).extension.lowercase() in torrentDownloadableExtensions
    }) { "A seleção contém um arquivo inválido." }
    require(videoFile != null && videoFile in selectedFiles && File(files.filePath(videoFile)).extension.lowercase() in torrentVideoExtensions) {
        "Selecione ao menos um vídeo."
    }
}

internal fun defaultTorrentSelection(files: List<TorrentFileChoice>, wantedEpisode: Int?): Pair<List<Int>, Int>? {
    val videos = files.filter(TorrentFileChoice::isVideo)
    if (videos.isEmpty()) return null
    val primary = videos.firstOrNull { wantedEpisode != null && parseReleaseTitle(File(it.path).name).episode == wantedEpisode }
        ?: videos.maxBy(TorrentFileChoice::sizeBytes)
    val selected = if (videos.size == 1) files else files.filter {
        wantedEpisode != null && parseReleaseTitle(File(it.path).name).episode == wantedEpisode
    }.let { (it + primary).distinctBy(TorrentFileChoice::index) }
    return selected.map(TorrentFileChoice::index) to primary.index
}

internal fun priorityWindowLast(firstPiece: Int, fileLastPiece: Int, bytes: Int, pieceLength: Int): Int =
    minOf(fileLastPiece, firstPiece + maxOf(1, (bytes + pieceLength - 1) / pieceLength) - 1)

internal fun primaryTorrentVideo(files: List<TorrentFileChoice>, selectedFiles: Set<Int>, wantedEpisode: Int?): Int? = files
    .filter { it.isVideo && it.index in selectedFiles }
    .maxWithOrNull(compareBy<TorrentFileChoice> { parseReleaseTitle(File(it.path).name).episode == wantedEpisode }.thenBy(TorrentFileChoice::sizeBytes))
    ?.index

internal fun torrentMetadataDirectory(context: Context) = File(context.filesDir, "torrents").apply { mkdirs() }

private fun downloadToJson(download: TorrentDownload) = JSONObject()
    .put("releaseId", download.releaseId).put("infoHash", download.infoHash).put("name", download.name)
    .put("status", download.status).put("progress", download.progress.toDouble()).put("downloadSpeed", download.downloadSpeed)
    .put("downloadedBytes", download.downloadedBytes).put("sizeBytes", download.sizeBytes).put("peers", download.peers)
    .put("videoPath", download.videoPath ?: JSONObject.NULL).put("error", download.error ?: JSONObject.NULL)
    .put("animeId", download.animeId ?: JSONObject.NULL).put("animeTitle", download.animeTitle ?: JSONObject.NULL)
    .put("animeCoverUrl", download.animeCoverUrl ?: JSONObject.NULL).put("animeCoverPath", download.animeCoverPath ?: JSONObject.NULL)
    .put("episode", download.episode ?: JSONObject.NULL).put("streamableBytes", download.streamableBytes)
    .put("selectedFileIndices", JSONArray().apply { download.selectedFileIndices.forEach { put(it) } })
    .put("videoFileIndex", download.videoFileIndex ?: JSONObject.NULL)
    .put("connectedSeeders", download.connectedSeeders).put("knownPeers", download.knownPeers)
    .put("connectionCandidates", download.connectionCandidates).put("trackerSeeders", download.trackerSeeders ?: JSONObject.NULL)

internal fun downloadFromJson(json: JSONObject) = TorrentDownload(
    json.getString("releaseId"), json.getString("infoHash"), json.getString("name"), json.getString("status"),
    json.optDouble("progress").toFloat(), json.optLong("downloadSpeed"), json.optLong("downloadedBytes"),
    json.optLong("sizeBytes"), json.optInt("peers"), json.optString("videoPath").takeIf { it.isNotBlank() && it != "null" },
    json.optString("error").takeIf { it.isNotBlank() && it != "null" },
    json.optInt("animeId").takeIf { it > 0 },
    json.optString("animeTitle").takeIf { it.isNotBlank() && it != "null" },
    json.optString("animeCoverUrl").takeIf { it.isNotBlank() && it != "null" },
    json.optString("animeCoverPath").takeIf { it.isNotBlank() && it != "null" },
    json.optInt("episode").takeIf { it > 0 },
    json.optLong("streamableBytes"),
    json.optJSONArray("selectedFileIndices")?.let { array -> List(array.length()) { array.optInt(it, -1) }.filter { it >= 0 } }.orEmpty(),
    json.optInt("videoFileIndex", -1).takeIf { it >= 0 }, json.optInt("connectedSeeders"), json.optInt("knownPeers"),
    json.optInt("connectionCandidates"), json.optInt("trackerSeeders", -1).takeIf { it >= 0 }
)

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
        if (!hasPiece(piece)) break
        val pieceStart = piece.toLong() * pieceLength
        availableEnd = minOf(fileEnd, pieceStart + pieceSize(piece))
    }
    return (availableEnd - fileStart).coerceIn(0, fileSize)
}
