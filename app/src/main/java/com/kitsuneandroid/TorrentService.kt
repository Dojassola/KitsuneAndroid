package com.kitsuneandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TorrentDownload(
    val releaseId: String,
    val infoHash: String,
    val name: String,
    val status: String,
    val progress: Float,
    val downloadSpeed: Long,
    val downloadedBytes: Long,
    val sizeBytes: Long,
    val peers: Int,
    val videoPath: String?,
    val error: String?
)

object TorrentStore {
    val downloads = mutableStateListOf<TorrentDownload>()
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context) {
        val loaded = metadataDirectory(context).listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { downloadFromJson(JSONObject(it.readText())) }.getOrNull() }
        main.post {
            downloads.clear()
            downloads.addAll(loaded.sortedByDescending { it.status == "downloading" })
        }
    }

    fun upsert(download: TorrentDownload) = main.post {
        val index = downloads.indexOfFirst { it.infoHash == download.infoHash }
        if (index < 0) downloads.add(download) else downloads[index] = download
    }

    fun remove(infoHash: String) = main.post { downloads.removeAll { it.infoHash == infoHash } }
}

class TorrentService : Service(), AlertListener {
    private data class Record(
        val releaseId: String,
        val title: String,
        val infoHash: String,
        val torrentFile: File,
        var paused: Boolean = false,
        var error: String? = null,
        var lastPersistAt: Long = 0,
        var lastPeerSearchAt: Long = 0
    )

    private val session = SessionManager()
    private val records = ConcurrentHashMap<String, Record>()
    private val work = Executors.newSingleThreadExecutor()
    private val polling = Executors.newSingleThreadScheduledExecutor()
    private lateinit var downloadDirectory: File

    override fun onCreate() {
        super.onCreate()
        downloadDirectory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "Kitsune").apply { mkdirs() }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparando downloads", 0))
        session.addListener(this)
        session.start()
        session.applySettings(SettingsPack().connectionsLimit(80).uploadRateLimit(512 * 1024).activeDownloads(3).apply { setEnableDht(true) })
        polling.scheduleWithFixedDelay(::poll, 1, 1, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> work.execute {
                val releaseId = intent.getStringExtra(EXTRA_ID).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val expectedHash = intent.getStringExtra(EXTRA_HASH).orEmpty()
                runCatching {
                    add(releaseId, title, expectedHash)
                }.onFailure { failurePending(releaseId, title, expectedHash, it) }
            }
            ACTION_PAUSE -> work.execute { control(intent.getStringExtra(EXTRA_HASH), true) }
            ACTION_RESUME -> work.execute { control(intent.getStringExtra(EXTRA_HASH), false) }
            ACTION_REMOVE -> work.execute { remove(intent.getStringExtra(EXTRA_HASH).orEmpty()) }
            else -> work.execute { restore() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun types(): IntArray? = null

    override fun alert(alert: Alert<*>) {
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
    }

    private fun add(releaseId: String, title: String, expectedHash: String) {
        require(releaseId.matches(Regex("\\d{1,12}"))) { "Identificador de release inválido." }
        require(expectedHash.matches(Regex("[a-fA-F0-9]{40}"))) { "Hash da release inválido." }
        records[expectedHash]?.let { record ->
            record.error = null
            record.paused = false
            val existing = session.find(Sha1Hash.parseHex(expectedHash))
            if (existing.isValid) existing.resume() else session.download(TorrentInfo(record.torrentFile), downloadDirectory)
            return
        }
        val bytes = downloadTorrent(releaseId)
        val info = TorrentInfo(bytes)
        validateTorrent(info)
        val hash = info.infoHash().toHex()
        require(hash.equals(expectedHash, ignoreCase = true)) { "O arquivo recebido não corresponde à release selecionada." }
        if (records.containsKey(hash)) return
        val torrentFile = File(metadataDirectory(this), "$hash.torrent").apply { writeBytes(bytes) }
        records[hash] = Record(releaseId, title.take(500).ifBlank { info.name() }, hash, torrentFile)
        persist(records.getValue(hash), TorrentDownload(releaseId, hash, title, "queued", 0f, 0, 0, info.totalSize(), 0, null, null))
        session.download(info, downloadDirectory)
    }

    private fun restore() {
        metadataDirectory(this).listFiles { file -> file.extension == "json" }.orEmpty().forEach { jsonFile ->
            runCatching {
                val saved = downloadFromJson(JSONObject(jsonFile.readText()))
                TorrentStore.upsert(saved)
                if (saved.status in setOf("completed", "paused", "failed")) return@runCatching
                if (records.containsKey(saved.infoHash)) return@runCatching
                val torrentFile = File(metadataDirectory(this), "${saved.infoHash}.torrent")
                if (!torrentFile.isFile) return@runCatching
                val info = TorrentInfo(torrentFile)
                validateTorrent(info)
                records[saved.infoHash] = Record(saved.releaseId, saved.name, saved.infoHash, torrentFile, saved.status == "paused")
                session.download(info, downloadDirectory)
            }
        }
        if (records.isEmpty()) stopSelf()
    }

    private fun control(hash: String?, pause: Boolean) {
        if (hash == null) return
        if (!records.containsKey(hash)) {
            val jsonFile = File(metadataDirectory(this), "$hash.json")
            if (!jsonFile.isFile) return
            val saved = downloadFromJson(JSONObject(jsonFile.readText()))
            val torrentFile = File(metadataDirectory(this), "$hash.torrent")
            if (!torrentFile.isFile || saved.status == "completed") return
            records[hash] = Record(saved.releaseId, saved.name, hash, torrentFile, pause)
            session.download(TorrentInfo(torrentFile), downloadDirectory)
            return
        }
        records[hash]?.let {
            it.paused = pause
            if (pause) it.lastPersistAt = 0
        }
        val handle = session.find(Sha1Hash.parseHex(hash))
        if (handle.isValid) {
            if (pause) handle.pause() else handle.resume()
            update(handle)
        }
        if (pause) stopIfIdle()
    }

    private fun remove(hash: String) {
        val record = records.remove(hash)
        val handle = runCatching { session.find(Sha1Hash.parseHex(hash)) }.getOrNull()
        val torrentFile = record?.torrentFile ?: File(metadataDirectory(this), "$hash.torrent")
        val info = when {
            handle?.isValid == true -> handle.torrentFile()
            torrentFile.isFile -> runCatching { TorrentInfo(torrentFile) }.getOrNull()
            else -> null
        }
        if (handle?.isValid == true) session.remove(handle)
        if (info != null) {
            repeat(info.files().numFiles()) { index -> safeDelete(File(downloadDirectory, info.files().filePath(index))) }
        }
        torrentFile.delete()
        File(metadataDirectory(this), "$hash.json").delete()
        TorrentStore.remove(hash)
        if (records.isEmpty()) stopSelf()
    }

    private fun configureFiles(handle: TorrentHandle) {
        val info = handle.torrentFile()
        val priorities = Array(info.files().numFiles()) { index ->
            if (File(info.files().filePath(index)).extension.lowercase() in DOWNLOADABLE_EXTENSIONS) Priority.DEFAULT else Priority.IGNORE
        }
        handle.prioritizeFiles(priorities)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        records[handle.infoHash().toHex()]?.let {
            handle.forceReannounce()
            handle.forceDHTAnnounce()
            it.lastPeerSearchAt = System.currentTimeMillis()
            if (it.paused) {
                handle.pause()
                stopIfIdle()
            }
        }
    }

    private fun poll() {
        records.values.forEach { record ->
            val handle = runCatching { session.find(Sha1Hash.parseHex(record.infoHash)) }.getOrNull() ?: return@forEach
            if (handle.isValid) update(handle)
        }
        val active = records.values.filter { !it.paused && it.error == null }
        val progressValues = active.mapNotNull { record ->
            runCatching { session.find(Sha1Hash.parseHex(record.infoHash)) }.getOrNull()?.takeIf(TorrentHandle::isValid)?.status()?.progress()
        }
        val progress = if (progressValues.isEmpty()) 0 else (progressValues.average() * 100).toInt()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(if (active.isEmpty()) "Downloads concluídos" else "${active.size} download(s) ativo(s)", progress))
    }

    private fun update(handle: TorrentHandle, completed: Boolean = false) {
        val status = handle.status()
        val hash = status.infoHashes.getBest().toHex()
        val record = records[hash] ?: return
        val now = System.currentTimeMillis()
        if (!record.paused && status.numPeers() == 0 && status.totalDone() == 0L && now - record.lastPeerSearchAt >= 60_000) {
            handle.forceReannounce()
            handle.forceDHTAnnounce()
            record.lastPeerSearchAt = now
        }
        val video = largestVideo(handle.torrentFile())
        val download = TorrentDownload(
            record.releaseId,
            hash,
            record.title,
            when { record.error != null -> "failed"; completed || status.isFinished -> "completed"; record.paused -> "paused"; status.numPeers() == 0 -> "procurando peers"; else -> "downloading" },
            status.progress().coerceIn(0f, 1f),
            status.downloadRate().toLong().coerceAtLeast(0),
            status.totalDone().coerceAtLeast(0),
            status.totalWanted().coerceAtLeast(0),
            status.numPeers().coerceAtLeast(0),
            video?.takeIf { completed || status.isFinished }?.absolutePath,
            record.error
        )
        TorrentStore.upsert(download)
        if (now - record.lastPersistAt >= 5_000 || download.status == "completed") {
            persist(record, download)
            record.lastPersistAt = now
        }
    }

    private fun failure(hash: String?, error: Throwable) {
        val record = hash?.let(records::get) ?: return
        record.error = error.message?.take(500) ?: "Falha no download torrent."
        val failed = TorrentDownload(record.releaseId, record.infoHash, record.title, "failed", 0f, 0, 0, 0, 0, null, record.error)
        TorrentStore.upsert(failed)
        persist(record, failed)
        stopIfIdle()
    }

    private fun failurePending(releaseId: String, title: String, hash: String, error: Throwable) {
        val record = records[hash]
        if (record != null) return failure(hash, error)
        val message = error.message?.take(500) ?: "Falha no download torrent."
        TorrentStore.upsert(TorrentDownload(releaseId, hash, title, "failed", 0f, 0, 0, 0, 0, null, message))
    }

    private fun persist(record: Record, download: TorrentDownload) {
        File(metadataDirectory(this), "${record.infoHash}.json").writeText(downloadToJson(download).toString())
    }

    private fun downloadTorrent(releaseId: String): ByteArray {
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
            if (File(path).extension.lowercase() in VIDEO_EXTENSIONS) video = true
        }
        require(video) { "A release não contém vídeo reconhecido." }
    }

    private fun largestVideo(info: TorrentInfo): File? = (0 until info.files().numFiles())
        .filter { File(info.files().filePath(it)).extension.lowercase() in VIDEO_EXTENSIONS }
        .maxByOrNull { info.files().fileSize(it) }
        ?.let { File(downloadDirectory, info.files().filePath(it)) }

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
        polling.shutdownNow()
        work.shutdownNow()
        session.removeListener(this)
        session.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "kitsune_downloads"
        private const val NOTIFICATION_ID = 47
        private const val ACTION_ADD = "com.kitsuneandroid.ADD_TORRENT"
        private const val ACTION_PAUSE = "com.kitsuneandroid.PAUSE_TORRENT"
        private const val ACTION_RESUME = "com.kitsuneandroid.RESUME_TORRENT"
        private const val ACTION_REMOVE = "com.kitsuneandroid.REMOVE_TORRENT"
        private const val EXTRA_ID = "release_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HASH = "info_hash"
        private const val MAX_TORRENT_BYTES = 5 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024 * 1024
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "webm", "avi", "m4v", "mov", "ts", "m2ts")
        private val DOWNLOADABLE_EXTENSIONS = VIDEO_EXTENSIONS + setOf("srt", "ass", "ssa", "vtt")

        fun enqueue(context: Context, release: ReleaseCandidate) {
            TorrentStore.upsert(TorrentDownload(
                release.id, release.infoHash, release.title, "queued", 0f, 0, 0,
                release.sizeBytes, 0, null, null
            ))
            start(context, Intent(context, TorrentService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_ID, release.id)
                putExtra(EXTRA_TITLE, release.title)
                putExtra(EXTRA_HASH, release.infoHash)
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
                })
            } else control(context, ACTION_RESUME, download.infoHash)
        }
        fun remove(context: Context, hash: String) = control(context, ACTION_REMOVE, hash)
        fun restore(context: Context) = start(context, Intent(context, TorrentService::class.java))

        private fun control(context: Context, actionName: String, hash: String) = start(context, Intent(context, TorrentService::class.java).apply {
            action = actionName
            putExtra(EXTRA_HASH, hash)
        })

        private fun start(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)
    }
}

private fun metadataDirectory(context: Context) = File(context.filesDir, "torrents").apply { mkdirs() }

private fun downloadToJson(download: TorrentDownload) = JSONObject()
    .put("releaseId", download.releaseId).put("infoHash", download.infoHash).put("name", download.name)
    .put("status", download.status).put("progress", download.progress.toDouble()).put("downloadSpeed", download.downloadSpeed)
    .put("downloadedBytes", download.downloadedBytes).put("sizeBytes", download.sizeBytes).put("peers", download.peers)
    .put("videoPath", download.videoPath ?: JSONObject.NULL).put("error", download.error ?: JSONObject.NULL)

private fun downloadFromJson(json: JSONObject) = TorrentDownload(
    json.getString("releaseId"), json.getString("infoHash"), json.getString("name"), json.getString("status"),
    json.optDouble("progress").toFloat(), json.optLong("downloadSpeed"), json.optLong("downloadedBytes"),
    json.optLong("sizeBytes"), json.optInt("peers"), json.optString("videoPath").takeIf { it.isNotBlank() && it != "null" },
    json.optString("error").takeIf { it.isNotBlank() && it != "null" }
)
