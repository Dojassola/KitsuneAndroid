package com.kitsuneandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun torrentMetadataDirectory(context: Context): File {
    return File(context.filesDir, "torrents").apply {
        mkdirs()
    }
}

internal fun downloadToJson(download: TorrentDownload): JSONObject {
    val selectedFiles = JSONArray()

    for (index in download.selectedFileIndices) {
        selectedFiles.put(index)
    }

    val completedFiles = JSONArray()

    for (index in download.completedFileIndices) {
        completedFiles.put(index)
    }

    return JSONObject()
        .put("releaseId", download.releaseId)
        .put("infoHash", download.infoHash)
        .put("name", download.name)
        .put("status", download.status.persistedValue)
        .put("progress", download.progress.toDouble())
        .put("downloadSpeed", download.downloadSpeed)
        .put("downloadedBytes", download.downloadedBytes)
        .put("sizeBytes", download.sizeBytes)
        .put("peers", download.peers)
        .put("videoPath", download.videoPath ?: JSONObject.NULL)
        .put("error", download.error ?: JSONObject.NULL)
        .put("animeId", download.animeId ?: JSONObject.NULL)
        .put("animeTitle", download.animeTitle ?: JSONObject.NULL)
        .put("animeCoverUrl", download.animeCoverUrl ?: JSONObject.NULL)
        .put("animeCoverPath", download.animeCoverPath ?: JSONObject.NULL)
        .put("episode", download.episode ?: JSONObject.NULL)
        .put("streamableBytes", download.streamableBytes)
        .put("videoSizeBytes", download.videoSizeBytes)
        .put("selectedFileIndices", selectedFiles)
        .put("completedFileIndices", completedFiles)
        .put("videoFileIndex", download.videoFileIndex ?: JSONObject.NULL)
        .put("connectedSeeders", download.connectedSeeders)
        .put("knownPeers", download.knownPeers)
        .put("connectionCandidates", download.connectionCandidates)
        .put("trackerSeeders", download.trackerSeeders ?: JSONObject.NULL)
        .put("magnetUri", download.magnetUri ?: JSONObject.NULL)
        .put("providerId", download.providerId)
        .put("queuePosition", download.queuePosition)
}

internal fun downloadFromJson(json: JSONObject): TorrentDownload {
    return TorrentDownload(
        releaseId = json.getString("releaseId"),
        infoHash = json.getString("infoHash"),
        name = json.getString("name"),
        status = TorrentStatus.fromPersisted(json.getString("status")),
        progress = json.optDouble("progress").toFloat(),
        downloadSpeed = json.optLong("downloadSpeed"),
        downloadedBytes = json.optLong("downloadedBytes"),
        sizeBytes = json.optLong("sizeBytes"),
        peers = json.optInt("peers"),
        videoPath = optionalJsonString(json, "videoPath"),
        error = optionalJsonString(json, "error"),
        animeId = json.optInt("animeId").takeIf { value -> value > 0 },
        animeTitle = optionalJsonString(json, "animeTitle"),
        animeCoverUrl = optionalJsonString(json, "animeCoverUrl"),
        animeCoverPath = optionalJsonString(json, "animeCoverPath"),
        episode = json.optInt("episode").takeIf { value -> value > 0 },
        streamableBytes = json.optLong("streamableBytes"),
        videoSizeBytes = json.optLong("videoSizeBytes"),
        selectedFileIndices = jsonIntList(json, "selectedFileIndices"),
        completedFileIndices = jsonIntList(json, "completedFileIndices"),
        videoFileIndex = json.optInt("videoFileIndex", -1)
            .takeIf { value -> value >= 0 },
        connectedSeeders = json.optInt("connectedSeeders"),
        knownPeers = json.optInt("knownPeers"),
        connectionCandidates = json.optInt("connectionCandidates"),
        trackerSeeders = json.optInt("trackerSeeders", -1)
            .takeIf { value -> value >= 0 },
        magnetUri = optionalJsonString(json, "magnetUri"),
        providerId = json.optString("providerId").ifBlank { "nyaa" },
        queuePosition = json.optInt("queuePosition", -1)
    )
}

private fun optionalJsonString(json: JSONObject, key: String): String? {
    val value = json.optString(key)

    if (value.isBlank() || value == "null") {
        return null
    }

    return value
}

private fun jsonIntList(json: JSONObject, key: String): List<Int> {
    val array = json.optJSONArray(key)

    if (array == null) {
        return emptyList()
    }

    val values = mutableListOf<Int>()

    for (index in 0 until array.length()) {
        val value = array.optInt(index, -1)

        if (value >= 0) {
            values.add(value)
        }
    }

    return values
}
