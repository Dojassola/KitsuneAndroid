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
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.MetadataFailedAlert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.torrent_flags_t
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class TorrentService : Service(), AlertListener {
    private data class Record(
        val releaseId: String,
        val title: String,
        val infoHash: String,
        val torrentFile: File,
        var metadata: TorrentDownload,
        var paused: Boolean = false,
        var policyPauseReason: String? = null,
        var error: String? = null,
        var lastPersistAt: Long = 0,
        var lastResumeRequestAt: Long = 0,
        var lastPeerSearchAt: Long = 0,
        var lastDownloadedBytes: Long = metadata.downloadedBytes,
        var lastPayloadAt: Long = System.currentTimeMillis(),
        var priorityFirstPiece: Int = -1,
        var priorityLastPiece: Int = -1
    )

    private sealed interface NativeEvent {
        data class Added(val hash: String, val error: String?) : NativeEvent
        data class MetadataReady(val hash: String) : NativeEvent
        data class Finished(val hash: String) : NativeEvent
        data class Failed(val hash: String, val message: String) : NativeEvent
        data class ResumeData(
            val hash: String,
            val bytes: ByteArray,
            val torrentBytes: ByteArray?
        ) : NativeEvent
    }

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
        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.notification_preparing_downloads), 0)
        )
        enqueue("torrent.start") {
            try {
                session.addListener(this)
                session.start()
                session.applySettings(SettingsPack().connectionsLimit(240).uploadRateLimit(512 * 1024).activeDownloads(2).apply { setEnableDht(true) })
                if (!work.isShutdown) {
                    work.scheduleWithFixedDelay(
                        { safely("torrent.poll", ::poll) },
                        1,
                        1,
                        TimeUnit.SECONDS
                    )
                }
            } catch (failure: Exception) {
                AppErrors.record("torrent.start", failure)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> enqueue("torrent.add") {
                val releaseId = intent.getStringExtra(EXTRA_ID).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val expectedHash = intent.getStringExtra(EXTRA_HASH).orEmpty()
                val selectedFiles = (intent.getIntArrayExtra(EXTRA_FILES) ?: intArrayOf()).toList()
                val videoFile = intent.getIntExtra(EXTRA_VIDEO_FILE, -1).takeIf { it >= 0 }
                val magnetUri = intent.getStringExtra(EXTRA_MAGNET)
                val providerId = intent.getStringExtra(EXTRA_PROVIDER).orEmpty().ifBlank { "nyaa" }

                try {
                    add(
                        releaseId,
                        title,
                        expectedHash,
                        selectedFiles,
                        videoFile,
                        magnetUri,
                        providerId
                    )
                } catch (failure: Exception) {
                    failurePending(releaseId, title, expectedHash, failure)
                }
            }
            ACTION_PAUSE -> enqueue("torrent.pause") {
                val infoHash = intent.getStringExtra(EXTRA_HASH)
                control(infoHash, pause = true)
            }
            ACTION_RESUME -> enqueue("torrent.resume") {
                val infoHash = intent.getStringExtra(EXTRA_HASH)
                control(infoHash, pause = false)
            }
            ACTION_REMOVE -> enqueue("torrent.remove") {
                val infoHash = intent.getStringExtra(EXTRA_HASH).orEmpty()
                remove(infoHash)
            }
            ACTION_REMOVE_EPISODE -> enqueue("torrent.removeEpisode") {
                val infoHash = intent.getStringExtra(EXTRA_HASH).orEmpty()
                val episode = intent.getIntExtra(EXTRA_EPISODE, -1)
                    .takeIf { value -> value >= 0 }
                val videoFile = intent.getIntExtra(EXTRA_VIDEO_FILE, -1)
                    .takeIf { value -> value >= 0 }

                removeEpisode(
                    infoHash = infoHash,
                    episode = episode,
                    videoFileIndex = videoFile
                )
            }
            ACTION_STREAM -> enqueue("torrent.stream") {
                val infoHash = intent.getStringExtra(EXTRA_HASH).orEmpty()
                val position = intent.getLongExtra(EXTRA_POSITION, 0)
                prioritizeStream(infoHash, position)
            }
            else -> enqueue("torrent.restore") {
                restore()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        val enqueued = enqueue("torrent.timeout") {
            checkpoint()
            stopSelf(startId)
        }
        if (!enqueued) {
            stopSelf(startId)
        }
    }

    override fun types(): IntArray? = null

    override fun alert(alert: Alert<*>) {
        val event = try {
            nativeEvent(alert)
        } catch (failure: Exception) {
            AppErrors.record("torrent.alert", failure)
            return
        }

        if (event == null) {
            return
        }

        enqueue("torrent.event.${event.javaClass.simpleName}") {
            process(event)
        }
    }

    private fun enqueue(operation: String, action: () -> Unit): Boolean {
        return try {
            work.execute {
                safely(operation, action)
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun safely(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            AppErrors.record(operation, failure)
        }
    }

    private fun nativeEvent(alert: Alert<*>): NativeEvent? {
        return when (alert) {
            is AddTorrentAlert -> {
                val error = alert.error()
                val message = if (error.isError) error.message else null
                NativeEvent.Added(alert.handle().infoHash().toHex(), message)
            }
            is MetadataReceivedAlert -> {
                NativeEvent.MetadataReady(alert.handle().infoHash().toHex())
            }
            is MetadataFailedAlert -> {
                val message = alert.error.message.ifBlank {
                    "Não foi possível obter os metadados do magnet."
                }
                NativeEvent.Failed(alert.handle().infoHash().toHex(), message)
            }
            is TorrentFinishedAlert -> {
                NativeEvent.Finished(alert.handle().infoHash().toHex())
            }
            is TorrentErrorAlert -> {
                val message = alert.error().message.ifBlank {
                    "Falha nativa no torrent."
                }
                NativeEvent.Failed(alert.handle().infoHash().toHex(), message)
            }
            is SaveResumeDataAlert -> {
                val params = alert.params()
                val infoHash = params.infoHashes.best.toHex()
                val bytes = AddTorrentParams.writeResumeDataBuf(params)
                val torrentBytes = if (params.torrentInfo == null) {
                    null
                } else {
                    try {
                        val encoded = libtorrent.write_torrent_file_buf_ex(params.swig())
                        Vectors.byte_vector2bytes(encoded)
                    } catch (_: Exception) {
                        null
                    }
                }
                NativeEvent.ResumeData(infoHash, bytes, torrentBytes)
            }
            else -> null
        }
    }

    private fun process(event: NativeEvent) {
        when (event) {
            is NativeEvent.Added -> processAddedTorrent(event)
            is NativeEvent.MetadataReady -> processMetadata(event.hash)
            is NativeEvent.Finished -> {
                val handle = findValidTorrent(event.hash)

                if (handle != null) {
                    update(handle, completed = true)
                    handle.pause()
                } else {
                    val record = records[event.hash]

                    if (record != null) {
                        val completed = record.metadata.copy(
                            status = TorrentStatus.COMPLETED,
                            progress = 1f,
                            downloadSpeed = 0,
                            completedFileIndices = record.metadata.selectedFileIndices
                        )
                        record.metadata = completed
                        TorrentStore.upsert(completed)

                        try {
                            persist(record, completed)
                        } catch (failure: Exception) {
                            val persistenceError = completed.copy(
                                error = "Download concluído, mas o estado não pôde ser salvo."
                            )
                            record.metadata = persistenceError
                            TorrentStore.upsert(persistenceError)
                        }
                    }
                }

                val record = records[event.hash]
                if (record != null && record.metadata.animeCoverPath == null) {
                    val coverPath = cacheAnimeCover(
                        record.metadata.animeId,
                        record.metadata.animeCoverUrl
                    )
                    if (coverPath != null) {
                        val withCover = record.metadata.copy(animeCoverPath = coverPath)
                        record.metadata = withCover
                        TorrentStore.upsert(withCover)
                        persist(record, withCover)
                    }
                }

                records.remove(event.hash)
                stopIfIdle()
            }
            is NativeEvent.Failed -> failure(event.hash, IOException(event.message))
            is NativeEvent.ResumeData -> persistResumeData(
                event.hash,
                event.bytes,
                event.torrentBytes
            )
        }
    }

    private fun processAddedTorrent(event: NativeEvent.Added) {
        if (event.error != null) {
            failure(event.hash, IOException(event.error))
            return
        }

        val handle = findValidTorrent(event.hash)

        if (handle == null) {
            failure(
                event.hash,
                IOException("O torrent não ficou disponível após ser adicionado.")
            )
            return
        }

        processMetadata(event.hash)
    }

    private fun processMetadata(hash: String) {
        val record = records[hash] ?: return
        val handle = findValidTorrent(hash) ?: return
        val info = torrentInfoOrNull(handle) ?: return

        try {
            validateTorrent(info)
            require(info.infoHash().toHex().equals(hash, ignoreCase = true)) {
                "Os metadados recebidos não correspondem à release selecionada."
            }
            validateSelection(
                info,
                record.metadata.selectedFileIndices,
                record.metadata.videoFileIndex
            )
        } catch (failure: Exception) {
            failure(hash, failure)
            return
        }

        val metadata = record.metadata.copy(
            name = record.title.take(500).ifBlank { info.name() },
            sizeBytes = info.totalSize(),
            status = TorrentStatus.SEARCHING_PEERS,
            error = null
        )
        record.metadata = metadata
        TorrentStore.upsert(metadata)
        persist(record, metadata)
        configureFiles(handle)
        try {
            handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        } catch (_: Exception) {
            Unit
        }
    }

    private fun add(
        releaseId: String,
        title: String,
        expectedHash: String,
        selectedFiles: List<Int>,
        videoFile: Int?,
        magnetUri: String?,
        providerId: String
    ) {
        require(expectedHash.matches(Regex("[a-fA-F0-9]{40}"))) { "Hash da release inválido." }
        check(session.isRunning) {
            "O motor de torrents não pôde ser iniciado."
        }
        records[expectedHash]?.let { record ->
            val saved = TorrentStore.get(expectedHash) ?: record.metadata
            record.metadata = saved.copy(
                status = TorrentStatus.SEARCHING_PEERS,
                error = null
            )
            record.error = null
            record.paused = false
            TorrentStore.upsert(record.metadata)
            val existing = findValidTorrent(expectedHash)
            if (existing != null) {
                processMetadata(expectedHash)
                if (record.policyPauseReason == null) {
                    existing.resume()
                }
            } else if (record.torrentFile.isFile) {
                startDownload(TorrentInfo(record.torrentFile), record.infoHash)
            } else {
                val savedMagnet = record.metadata.magnetUri
                    ?: throw IOException("Os metadados do torrent não estão disponíveis.")
                session.download(savedMagnet, downloadDirectory, torrent_flags_t())
            }
            persist(record, record.metadata)
            return
        }
        val cachedTorrent = File(torrentMetadataDirectory(this), "$expectedHash.torrent")
        if (!cachedTorrent.isFile && magnetUri != null) {
            addMagnet(
                releaseId = releaseId,
                title = title,
                expectedHash = expectedHash,
                selectedFiles = selectedFiles,
                videoFile = videoFile,
                magnetUri = magnetUri,
                providerId = providerId
            )
            return
        }
        val bytes = if (cachedTorrent.isFile) {
            cachedTorrent.readBytes()
        } else {
            downloadTorrent(releaseId, providerId)
        }
        val info = TorrentInfo(bytes)
        validateTorrent(info)
        val hash = info.infoHash().toHex()
        require(hash.equals(expectedHash, ignoreCase = true)) { "O arquivo recebido não corresponde à release selecionada." }
        if (records.containsKey(hash)) return
        val torrentFile = File(torrentMetadataDirectory(this), "$hash.torrent").apply { if (!isFile) writeBytes(bytes) }
        val pending = TorrentStore.get(hash) ?: TorrentDownload(
            releaseId, hash, title, TorrentStatus.QUEUED, 0f, 0, 0, info.totalSize(), 0, null, null
        )
        val requestedFiles = selectedFiles.ifEmpty { pending.selectedFileIndices }
        val requestedVideo = videoFile ?: pending.videoFileIndex
        validateSelection(info, requestedFiles, requestedVideo)
        val metadata = pending.copy(
            name = title.take(500).ifBlank { info.name() },
            sizeBytes = info.totalSize(),
            animeCoverPath = pending.animeCoverPath ?: cacheAnimeCover(pending.animeId, pending.animeCoverUrl),
            selectedFileIndices = requestedFiles,
            videoFileIndex = requestedVideo,
            magnetUri = magnetUri ?: pending.magnetUri,
            providerId = providerId
        )
        records[hash] = Record(
            releaseId = releaseId,
            title = metadata.name,
            infoHash = hash,
            torrentFile = torrentFile,
            metadata = metadata,
            policyPauseReason = downloadBlockReason(this)
        )
        TorrentStore.upsert(metadata)
        persist(records.getValue(hash), metadata)
        val existing = findValidTorrent(hash)
        if (existing != null) {
            configureFiles(existing)

            val record = records.getValue(hash)

            if (record.paused || record.policyPauseReason != null) {
                existing.pause()
            } else {
                existing.resume()
            }
        } else {
            startDownload(info, hash)
        }
    }

    private fun addMagnet(
        releaseId: String,
        title: String,
        expectedHash: String,
        selectedFiles: List<Int>,
        videoFile: Int?,
        magnetUri: String,
        providerId: String
    ) {
        val hash = expectedHash.lowercase()
        val pending = TorrentStore.get(expectedHash) ?: TorrentDownload(
            releaseId,
            hash,
            title,
            TorrentStatus.SEARCHING_PEERS,
            0f,
            0,
            0,
            0,
            0,
            null,
            null
        )
        val metadata = pending.copy(
            infoHash = hash,
            name = title.take(500).ifBlank { pending.name },
            status = TorrentStatus.SEARCHING_PEERS,
            error = null,
            selectedFileIndices = selectedFiles.ifEmpty { pending.selectedFileIndices },
            videoFileIndex = videoFile ?: pending.videoFileIndex,
            magnetUri = magnetUri,
            providerId = providerId
        )
        val record = Record(
            releaseId = releaseId,
            title = metadata.name,
            infoHash = hash,
            torrentFile = File(torrentMetadataDirectory(this), "$hash.torrent"),
            metadata = metadata,
            policyPauseReason = downloadBlockReason(this)
        )

        records[hash] = record
        TorrentStore.upsert(metadata)
        persist(record, metadata)

        val existing = findValidTorrent(hash)
        if (existing != null) {
            processMetadata(hash)
            return
        }

        session.download(magnetUri, downloadDirectory, torrent_flags_t())
    }

    private fun restore() {
        val metadataFiles = torrentMetadataDirectory(this)
            .listFiles { file -> file.extension == "json" }
            .orEmpty()

        for (metadataFile in metadataFiles) {
            try {
                restore(metadataFile)
            } catch (_: Exception) {
                continue
            }
        }

        if (records.isEmpty()) {
            stopSelf()
        }
    }

    private fun restore(metadataFile: File) {
        val saved = downloadFromJson(JSONObject(metadataFile.readText()))
        TorrentStore.upsert(saved)

        if (!saved.status.isActive || records.containsKey(saved.infoHash)) {
            return
        }

        val torrentFile = File(torrentMetadataDirectory(this), "${saved.infoHash}.torrent")

        if (!torrentFile.isFile) {
            if (saved.magnetUri != null) {
                add(
                    releaseId = saved.releaseId,
                    title = saved.name,
                    expectedHash = saved.infoHash,
                    selectedFiles = saved.selectedFileIndices,
                    videoFile = saved.videoFileIndex,
                    magnetUri = saved.magnetUri,
                    providerId = saved.providerId
                )
            }
            return
        }

        val info = TorrentInfo(torrentFile)
        validateTorrent(info)
        records[saved.infoHash] = Record(
            releaseId = saved.releaseId,
            title = saved.name,
            infoHash = saved.infoHash,
            torrentFile = torrentFile,
            metadata = saved,
            paused = saved.status == TorrentStatus.PAUSED,
            policyPauseReason = downloadBlockReason(this)
        )
        startDownload(info, saved.infoHash)
    }

    private fun control(hash: String?, pause: Boolean) {
        if (hash == null) {
            return
        }

        if (!records.containsKey(hash)) {
            val jsonFile = File(torrentMetadataDirectory(this), "$hash.json")

            if (!jsonFile.isFile) {
                return
            }

            val saved = downloadFromJson(JSONObject(jsonFile.readText()))
            val torrentFile = File(torrentMetadataDirectory(this), "$hash.torrent")

            if (saved.status == TorrentStatus.COMPLETED) {
                return
            }

            if (!torrentFile.isFile) {
                val savedMagnet = saved.magnetUri
                if (!pause && savedMagnet != null) {
                    add(
                        releaseId = saved.releaseId,
                        title = saved.name,
                        expectedHash = saved.infoHash,
                        selectedFiles = saved.selectedFileIndices,
                        videoFile = saved.videoFileIndex,
                        magnetUri = savedMagnet,
                        providerId = saved.providerId
                    )
                }
                return
            }

            records[hash] = Record(
                releaseId = saved.releaseId,
                title = saved.name,
                infoHash = hash,
                torrentFile = torrentFile,
                metadata = saved,
                paused = pause,
                policyPauseReason = downloadBlockReason(this)
            )
            startDownload(TorrentInfo(torrentFile), hash)
            return
        }
        records[hash]?.let {
            it.paused = pause

            if (pause) {
                it.lastPersistAt = 0
            } else {
                it.lastPayloadAt = System.currentTimeMillis()
            }
        }
        val handle = findValidTorrent(hash)

        if (handle != null) {
            if (pause) {
                handle.pause()
            } else {
                handle.resume()
                handle.forceReannounce()
                handle.forceDHTAnnounce()
            }
            update(handle)
        }

        if (pause) {
            stopIfIdle()
        }
    }

    private fun remove(hash: String) {
        val record = records.remove(hash)
        val handle = findValidTorrent(hash)
        val torrentFile = record?.torrentFile ?: File(torrentMetadataDirectory(this), "$hash.torrent")
        val handleTorrentInfo = handle?.let(::torrentInfoOrNull)
        val info = handleTorrentInfo ?: readTorrentInfoFile(torrentFile)

        if (handle != null) {
            try {
                session.remove(handle)
            } catch (_: Exception) {
                // The session may already have discarded a failed torrent.
            }
        }

        if (info != null) {
            repeat(info.files().numFiles()) { index ->
                try {
                    safeDelete(File(downloadDirectory, info.files().filePath(index)))
                } catch (_: Exception) {
                    // Continue removing the remaining app-owned files and metadata.
                }
            }
        }

        torrentFile.delete()
        File(torrentMetadataDirectory(this), "$hash.json").delete()
        resumeDataFile(hash).delete()
        TorrentStore.remove(hash)
        TorrentStreamStore.remove(hash)

        if (records.isEmpty()) {
            stopSelf()
        }
    }

    private fun readTorrentInfoFile(torrentFile: File): TorrentInfo? {
        if (!torrentFile.isFile) {
            return null
        }

        return try {
            TorrentInfo(torrentFile)
        } catch (_: Exception) {
            null
        }
    }

    private fun removeEpisode(
        infoHash: String,
        episode: Int?,
        videoFileIndex: Int?
    ) {
        val download = TorrentStore.get(infoHash)

        if (download == null) {
            return
        }

        val torrentInfo = readTorrentInfo(this, infoHash)

        if (torrentInfo == null) {
            remove(infoHash)
            return
        }

        val files = torrentFileChoices(torrentInfo)
        val remainingIndices = torrentFilesAfterEpisodeRemoval(
            files = files,
            selectedFileIndices = download.selectedFileIndices,
            episode = episode,
            videoFileIndex = videoFileIndex
        )
        val removedIndices = download.selectedFileIndices
            .filterNot { index -> index in remainingIndices }
            .toSet()

        if (removedIndices.isEmpty()) {
            return
        }

        val remainingVideo = files
            .asSequence()
            .filter { file -> file.isVideo && file.index in remainingIndices }
            .minByOrNull { file ->
                parseReleaseTitle(File(file.path).name).episode ?: Int.MAX_VALUE
            }

        for (index in removedIndices) {
            val file = files.firstOrNull { candidate -> candidate.index == index }

            if (file != null) {
                safeDelete(File(downloadDirectory, file.path))
            }
        }

        val removedVideoPath = videoFileIndex?.let { index ->
            files.firstOrNull { file -> file.index == index }
        }?.let { file ->
            File(downloadDirectory, file.path).absolutePath
        }

        if (removedVideoPath != null) {
            val removedDownload = download.copy(videoPath = removedVideoPath)
            VideoHistory.remove(this, playbackUri(removedDownload).toString())
        }

        if (remainingVideo == null) {
            remove(infoHash)
            return
        }

        val remainingVideoPath = File(downloadDirectory, remainingVideo.path)
        val remainingEpisode = parseReleaseTitle(remainingVideoPath.name).episode
        val updated = download.copy(
            videoPath = remainingVideoPath.absolutePath,
            episode = remainingEpisode,
            streamableBytes = remainingVideo.sizeBytes,
            videoSizeBytes = remainingVideo.sizeBytes,
            selectedFileIndices = remainingIndices,
            completedFileIndices = download.completedFileIndices
                .filter { index -> index in remainingIndices },
            videoFileIndex = remainingVideo.index
        )

        records[infoHash]?.metadata = updated
        File(torrentMetadataDirectory(this), "$infoHash.json")
            .writeText(downloadToJson(updated).toString())
        TorrentStore.upsert(updated)
        stopIfIdle()
    }

    private fun prioritizeStream(hash: String, position: Long) {
        streamPositions[hash] = position
        if (!records.containsKey(hash)) {
            control(hash, false)
            return
        }
        val record = records.getValue(hash)
        val handle = findValidTorrent(hash) ?: return
        val info = torrentInfoOrNull(handle) ?: return
        val index = videoFileIndex(info, record.metadata) ?: return
        handle.queuePositionTop()
        val files = info.files()
        val fileSize = files.fileSize(index)
        if (fileSize <= 0) return
        val first = ((files.fileOffset(index) + position.coerceIn(0, fileSize - 1)) / info.pieceLength()).toInt()
        val urgentLast = priorityWindowLast(first, files.lastPieceIndexAtFile(index), STREAM_START_BYTES, info.pieceLength())
        val priorityBytes = streamPriorityBytes(record.metadata.downloadSpeed)
        val last = priorityWindowLast(
            first,
            files.lastPieceIndexAtFile(index),
            priorityBytes,
            info.pieceLength()
        )
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
        val info = torrentInfoOrNull(handle) ?: return
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

            try {
                handle.scrapeTracker()
            } catch (_: Exception) {
                Unit
            }

            it.lastPeerSearchAt = System.currentTimeMillis()
            if (it.paused || it.policyPauseReason != null) {
                handle.pause()
                stopIfIdle()
            }
        }
        val hash = handle.infoHash().toHex()
        streamPositions[hash]?.let { prioritizeStream(hash, it) }
    }

    private fun poll() {
        val progressValues = mutableListOf<Float>()
        val policyPauseReason = downloadBlockReason(this)

        for (record in records.values) {
            try {
                val handle = findValidTorrent(record.infoHash)

                if (handle == null) {
                    continue
                }

                applyDownloadPolicy(handle, record, policyPauseReason)
                val download = update(handle)

                if (download != null && download.status.isActive) {
                    progressValues.add(download.progress)
                }
            } catch (error: Exception) {
                failure(record.infoHash, error)
            }
        }

        val active = records.values.filter { record ->
            !record.paused &&
                record.policyPauseReason == null &&
                record.error == null
        }
        val policyPaused = records.values.count { record ->
            !record.paused &&
                record.policyPauseReason != null &&
                record.error == null
        }
        val progress = if (progressValues.isEmpty()) {
            0
        } else {
            (progressValues.average() * 100).toInt()
        }
        val manager = getSystemService(NotificationManager::class.java)
        val text = when {
            active.isNotEmpty() -> resources.getQuantityString(
                R.plurals.notification_active_downloads,
                active.size,
                active.size,
                formatBytes(active.sumOf { record -> record.metadata.downloadSpeed })
            )
            policyPaused > 0 -> resources.getQuantityString(
                R.plurals.notification_policy_paused_downloads,
                policyPaused,
                policyPaused
            )
            records.isNotEmpty() -> getString(R.string.notification_downloads_paused)
            else -> getString(R.string.notification_downloads_completed)
        }

        try {
            manager.notify(NOTIFICATION_ID, notification(text, progress))
        } catch (_: SecurityException) {
            return
        }
    }

    private fun findValidTorrent(hash: String): TorrentHandle? {
        return try {
            val handle: TorrentHandle? = session.find(Sha1Hash.parseHex(hash))

            if (handle == null || !handle.isValid) {
                null
            } else {
                handle
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun torrentInfoOrNull(handle: TorrentHandle): TorrentInfo? {
        return try {
            handle.torrentFile()
        } catch (_: Exception) {
            null
        }
    }

    private fun applyDownloadPolicy(
        handle: TorrentHandle,
        record: Record,
        blockReason: String?
    ) {
        if (blockReason != null) {
            record.policyPauseReason = blockReason

            if (!record.paused && record.error == null) {
                handle.pause()
            }

            return
        }

        val wasPausedByPolicy = record.policyPauseReason != null
        record.policyPauseReason = null

        if (wasPausedByPolicy && !record.paused && record.error == null) {
            record.lastPayloadAt = System.currentTimeMillis()
            handle.resume()
            handle.forceReannounce()
            handle.forceDHTAnnounce()
        }
    }

    private fun update(handle: TorrentHandle, completed: Boolean = false): TorrentDownload? {
        val info = torrentInfoOrNull(handle) ?: return null
        val status = handle.status(TorrentHandle.QUERY_PIECES)
        val hash = status.infoHashes.getBest().toHex()
        val record = records[hash] ?: return null
        val now = System.currentTimeMillis()
        val downloadedBytes = status.totalDone().coerceAtLeast(0)
        if (downloadedBytes > record.lastDownloadedBytes) {
            record.lastDownloadedBytes = downloadedBytes
            record.lastPayloadAt = now
        }
        if (
            !record.paused &&
            record.policyPauseReason == null &&
            !status.isFinished &&
            status.downloadRate() == 0 &&
            now - record.lastPeerSearchAt >= 45_000
        ) {
            handle.forceReannounce()
            handle.forceDHTAnnounce()
            record.lastPeerSearchAt = now
        }
        val videoIndex = videoFileIndex(info, record.metadata)
        val video = videoIndex?.let { File(downloadDirectory, info.files().filePath(it)) }
        val fileProgress = handle.fileProgress()
        val completedFiles = record.metadata.selectedFileIndices.filter { index ->
            index in fileProgress.indices &&
                index in 0 until info.files().numFiles() &&
                fileProgress[index] >= info.files().fileSize(index)
        }
        val download = record.metadata.copy(
            status = torrentStatus(
                record.error != null,
                completed || status.isFinished,
                record.paused || record.policyPauseReason != null,
                status.numPeers(), status.downloadRate(), now, record.lastPayloadAt
            ),
            progress = status.progress().coerceIn(0f, 1f),
            downloadSpeed = status.downloadRate().toLong().coerceAtLeast(0),
            downloadedBytes = downloadedBytes,
            sizeBytes = status.totalWanted().coerceAtLeast(0),
            peers = status.numPeers().coerceAtLeast(0),
            videoPath = video?.absolutePath,
            error = record.error ?: record.policyPauseReason,
            streamableBytes = contiguousVideoBytes(hash, info, status.pieces(), videoIndex),
            videoSizeBytes = videoIndex?.let { info.files().fileSize(it) } ?: 0,
            completedFileIndices = completedFiles,
            connectedSeeders = status.numSeeds().coerceAtLeast(0),
            knownPeers = status.listPeers().coerceAtLeast(0),
            connectionCandidates = status.connectCandidates().coerceAtLeast(0),
            trackerSeeders = status.numComplete().takeIf { it >= 0 }
        )
        record.metadata = download
        TorrentStore.upsert(download)
        if (now - record.lastPersistAt >= 5_000 || download.status == TorrentStatus.COMPLETED) {
            val persistedDownload = if (
                record.policyPauseReason != null &&
                !record.paused &&
                record.error == null
            ) {
                download.copy(
                    status = TorrentStatus.QUEUED,
                    error = null
                )
            } else {
                download
            }

            persist(record, persistedDownload)
            record.lastPersistAt = now
        }

        if (
            download.status.isActive &&
            now - record.lastResumeRequestAt >= RESUME_DATA_INTERVAL_MS &&
            handle.needSaveResumeData()
        ) {
            handle.saveResumeData()
            record.lastResumeRequestAt = now
        }

        return download
    }

    private fun failure(hash: String?, error: Throwable) {
        AppErrors.record("torrent.download", error)
        val record = hash?.let(records::get) ?: return
        record.error = error.message?.take(500) ?: "Falha no download torrent."
        val failed = record.metadata.copy(status = TorrentStatus.FAILED, downloadSpeed = 0, peers = 0, error = record.error)
        TorrentStore.upsert(failed)

        try {
            persist(record, failed)
        } catch (_: Exception) {
            Unit
        }

        stopIfIdle()
    }

    private fun failurePending(releaseId: String, title: String, hash: String, error: Throwable) {
        val record = records[hash]
        if (record != null) return failure(hash, error)
        AppErrors.record("torrent.add", error)
        val message = error.message?.take(500) ?: "Falha no download torrent."
        val failed = (TorrentStore.get(hash) ?: TorrentDownload(
            releaseId,
            hash,
            title,
            TorrentStatus.FAILED,
            0f,
            0,
            0,
            0,
            0,
            null,
            message
        )).copy(status = TorrentStatus.FAILED, error = message)
        TorrentStore.upsert(failed)
        try {
            File(torrentMetadataDirectory(this), "$hash.json")
                .writeText(downloadToJson(failed).toString())
        } catch (_: Exception) {
            Unit
        }
    }

    private fun checkpoint() {
        for (record in records.values) {
            try {
                persist(record, record.metadata)
                val handle = findValidTorrent(record.infoHash)

                if (handle != null && handle.needSaveResumeData()) {
                    handle.saveResumeData()
                }
            } catch (_: Exception) {
                // O checkpoint é uma última tentativa; a atualização periódica continua sendo a fonte principal.
            }
        }
    }

    private fun persist(record: Record, download: TorrentDownload) {
        File(torrentMetadataDirectory(this), "${record.infoHash}.json").writeText(downloadToJson(download).toString())
    }

    private fun startDownload(torrentInfo: TorrentInfo, infoHash: String) {
        val resumeFile = resumeDataFile(infoHash)

        if (resumeFile.isFile) {
            try {
                session.download(
                    torrentInfo,
                    downloadDirectory,
                    resumeFile,
                    null,
                    emptyList<TcpEndpoint>(),
                    torrent_flags_t()
                )
                return
            } catch (_: Exception) {
                resumeFile.delete()
            }
        }

        session.download(torrentInfo, downloadDirectory)
    }

    private fun persistResumeData(
        infoHash: String,
        bytes: ByteArray,
        torrentBytes: ByteArray?
    ) {
        if (!infoHash.matches(Regex("[a-fA-F0-9]{40}")) || bytes.isEmpty()) {
            return
        }

        writeAtomically(resumeDataFile(infoHash), bytes)

        val metadataBytes = torrentBytes ?: return
        if (metadataBytes.isEmpty()) {
            return
        }

        try {
            validateTorrentMetadataSize(metadataBytes)
            val info = TorrentInfo(metadataBytes)
            validateTorrent(info)
            if (!info.infoHash().toHex().equals(infoHash, ignoreCase = true)) {
                return
            }
            writeAtomically(
                File(torrentMetadataDirectory(this), "$infoHash.torrent"),
                metadataBytes
            )
        } catch (_: Exception) {
            return
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)

        if (target.isFile && !target.delete()) {
            temporary.delete()
            return
        }

        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }
    }

    private fun resumeDataFile(infoHash: String): File {
        return File(torrentMetadataDirectory(this), "$infoHash.resume")
    }

    private fun cacheAnimeCover(animeId: Int?, url: String?): String? {
        if (animeId == null || url.isNullOrBlank()) {
            return null
        }

        return try {
            downloadAnimeCover(animeId, url)
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadAnimeCover(animeId: Int, url: String): String {
        val source = URL(url)
        require(source.protocol == "https") {
            "URL de capa inválida."
        }
        val directory = File(filesDir, "anime-covers").apply {
            mkdirs()
        }
        val target = File(directory, "$animeId.img")

        if (target.isFile && target.length() > 0) {
            return target.absolutePath
        }

        val temporary = File(directory, "$animeId.tmp")
        val connection = source.openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "image/*")
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")

        try {
            if (connection.responseCode !in 200..299 || connection.url.protocol != "https") {
                throw IOException("Não foi possível salvar a capa.")
            }

            if (!connection.contentType.orEmpty().startsWith("image/")) {
                throw IOException("A capa recebida não é uma imagem.")
            }

            if (connection.contentLengthLong > MAX_COVER_BYTES) {
                throw IOException("Capa grande demais.")
            }

            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16_384)

                    while (true) {
                        val read = input.read(buffer)

                        if (read < 0) {
                            break
                        }

                        total += read

                        if (total > MAX_COVER_BYTES) {
                            throw IOException("Capa grande demais.")
                        }

                        output.write(buffer, 0, read)
                    }
                }
            }

            require(total > 0) {
                "Capa vazia."
            }

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            return target.absolutePath
        } finally {
            connection.disconnect()

            if (!target.isFile) {
                temporary.delete()
            }
        }
    }

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
        enqueue("torrent.stop") {
            if (sessionHolder.isInitialized()) {
                session.removeListener(this)
                session.stop()
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
        private const val ACTION_REMOVE_EPISODE = "com.kitsuneandroid.REMOVE_TORRENT_EPISODE"
        private const val ACTION_STREAM = "com.kitsuneandroid.STREAM_TORRENT"
        private const val EXTRA_ID = "release_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HASH = "info_hash"
        private const val EXTRA_FILES = "file_indices"
        private const val EXTRA_VIDEO_FILE = "video_file_index"
        private const val EXTRA_MAGNET = "magnet_uri"
        private const val EXTRA_PROVIDER = "provider_id"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_POSITION = "position"
        private const val MAX_COVER_BYTES = 5L * 1024 * 1024
        private const val STREAM_START_BYTES = 1024 * 1024
        private const val STARTUP_PRIORITY_BYTES = 32 * 1024 * 1024
        private const val RESUME_DATA_INTERVAL_MS = 30_000L

        fun inspect(
            context: Context,
            release: ReleaseCandidate
        ): List<TorrentFileChoice> {
            val bytes = torrentMetadata(context, release)
            val info = TorrentInfo(bytes)
            val files = info.files()
            return (0 until files.numFiles()).mapNotNull { index ->
                val path = files.filePath(index)
                val extension = File(path).extension.lowercase()
                if (extension !in torrentDownloadableExtensions) null
                else TorrentFileChoice(index, path, files.fileSize(index), extension in torrentVideoExtensions)
            }
        }

        fun torrentMetadata(
            context: Context,
            release: ReleaseCandidate
        ): ByteArray {
            val torrentFile = File(
                torrentMetadataDirectory(context),
                "${release.infoHash}.torrent"
            )
            val bytes = when {
                torrentFile.isFile -> torrentFile.readBytes()
                release.magnetUri != null -> fetchMagnetMetadata(
                    context,
                    release.magnetUri
                )
                else -> downloadTorrent(release.id, release.providerId)
            }
            validateTorrentMetadataSize(bytes)
            val info = TorrentInfo(bytes)
            validateTorrent(info)
            require(info.infoHash().toHex().equals(release.infoHash, ignoreCase = true)) { "O arquivo recebido não corresponde à release selecionada." }

            if (!torrentFile.isFile) {
                torrentFile.writeBytes(bytes)
            }
            return bytes
        }

        fun enqueue(context: Context, anime: Anime, episode: Int?, release: ReleaseCandidate, selectedFiles: List<Int>, videoFile: Int) {
            val previous = TorrentStore.get(release.infoHash)
            val files = (previous?.selectedFileIndices.orEmpty() + selectedFiles).distinct()
            val initialStatus = initialTorrentStatus(release.magnetUri)
            TorrentStore.upsert(
                (previous ?: TorrentDownload(
                    releaseId = release.id,
                    infoHash = release.infoHash,
                    name = release.title,
                    status = initialStatus,
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
                    episode = episode,
                    magnetUri = release.magnetUri,
                    providerId = release.providerId
                )).copy(
                    name = release.title,
                    status = initialStatus,
                    error = null,
                    animeId = anime.id,
                    animeTitle = anime.title,
                    animeCoverUrl = anime.cover,
                    episode = episode,
                    selectedFileIndices = files,
                    videoFileIndex = videoFile,
                    magnetUri = release.magnetUri,
                    providerId = release.providerId
                )
            )
            start(context, Intent(context, TorrentService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_ID, release.id)
                putExtra(EXTRA_TITLE, release.title)
                putExtra(EXTRA_HASH, release.infoHash)
                putExtra(EXTRA_FILES, files.toIntArray())
                putExtra(EXTRA_VIDEO_FILE, videoFile)
                release.magnetUri?.let { putExtra(EXTRA_MAGNET, it) }
                putExtra(EXTRA_PROVIDER, release.providerId)
            })
        }

        fun pause(context: Context, hash: String) = control(context, ACTION_PAUSE, hash)
        fun resume(context: Context, download: TorrentDownload) {
            if (download.status == TorrentStatus.FAILED) {
                val retryStatus = initialTorrentStatus(download.magnetUri)
                val retry = download.copy(
                    status = retryStatus,
                    downloadSpeed = 0,
                    error = null
                )
                TorrentStore.upsert(retry)
                start(context, Intent(context, TorrentService::class.java).apply {
                    action = ACTION_ADD
                    putExtra(EXTRA_ID, retry.releaseId)
                    putExtra(EXTRA_TITLE, retry.name)
                    putExtra(EXTRA_HASH, retry.infoHash)
                    putExtra(EXTRA_FILES, retry.selectedFileIndices.toIntArray())
                    retry.magnetUri?.let { putExtra(EXTRA_MAGNET, it) }
                    putExtra(EXTRA_PROVIDER, retry.providerId)
                    retry.videoFileIndex?.let { videoFileIndex ->
                        putExtra(EXTRA_VIDEO_FILE, videoFileIndex)
                    }
                })
            } else {
                control(context, ACTION_RESUME, download.infoHash)
            }
        }
        fun remove(context: Context, hash: String) = control(context, ACTION_REMOVE, hash)

        fun removeEpisode(context: Context, download: TorrentDownload) {
            val intent = Intent(context, TorrentService::class.java).apply {
                action = ACTION_REMOVE_EPISODE
                putExtra(EXTRA_HASH, download.infoHash)
                download.episode?.let { episode ->
                    putExtra(EXTRA_EPISODE, episode)
                }
                download.videoFileIndex?.let { videoFile ->
                    putExtra(EXTRA_VIDEO_FILE, videoFile)
                }
            }

            start(context, intent)
        }

        fun prefetchEpisode(context: Context, download: TorrentDownload, target: TorrentEpisodeTarget) {
            if (target.videoFileIndex in download.selectedFileIndices) {
                return
            }

            val files = (download.selectedFileIndices + target.selectedFileIndices).distinct()
            val status: TorrentStatus

            if (download.status.isActive) {
                status = download.status
            } else {
                status = TorrentStatus.QUEUED
            }

            val updated = download.copy(
                status = status,
                error = null,
                selectedFileIndices = files
            )

            TorrentStore.upsert(updated)
            val intent = Intent(context, TorrentService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_ID, updated.releaseId)
                putExtra(EXTRA_TITLE, updated.name)
                putExtra(EXTRA_HASH, updated.infoHash)
                putExtra(EXTRA_FILES, files.toIntArray())
                updated.videoFileIndex?.let { videoFileIndex ->
                    putExtra(EXTRA_VIDEO_FILE, videoFileIndex)
                }
            }
            start(context, intent)
        }

        fun switchEpisode(context: Context, download: TorrentDownload, target: TorrentEpisodeTarget): TorrentDownload {
            val files = (download.selectedFileIndices + target.selectedFileIndices).distinct()
            val alreadyDownloaded = target.videoFileIndex in download.completedFileIndices ||
                (
                    download.status == TorrentStatus.COMPLETED &&
                        target.videoFileIndex in download.selectedFileIndices
                    )

            if (alreadyDownloaded) {
                return download.copy(
                    episode = target.episode,
                    videoPath = target.videoPath,
                    streamableBytes = File(target.videoPath).length(),
                    videoSizeBytes = File(target.videoPath).length(),
                    videoFileIndex = target.videoFileIndex
                )
            }

            val updated = download.copy(
                status = TorrentStatus.QUEUED,
                error = null,
                episode = target.episode,
                videoPath = target.videoPath,
                streamableBytes = 0,
                videoSizeBytes = 0,
                selectedFileIndices = files,
                videoFileIndex = target.videoFileIndex
            )

            TorrentStore.upsert(updated)
            val intent = Intent(context, TorrentService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_ID, updated.releaseId)
                putExtra(EXTRA_TITLE, updated.name)
                putExtra(EXTRA_HASH, updated.infoHash)
                putExtra(EXTRA_FILES, files.toIntArray())
                putExtra(EXTRA_VIDEO_FILE, target.videoFileIndex)
            }
            start(context, intent)

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
