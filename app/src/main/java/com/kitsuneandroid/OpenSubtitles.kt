package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class OpenSubtitlesSettings(
    val enabled: Boolean,
    val apiKey: String
)

private const val OPEN_SUBTITLES_PREFERENCES = "kitsune"
private const val OPEN_SUBTITLES_ENABLED = "open_subtitles_enabled"
private const val OPEN_SUBTITLES_API_KEY = "open_subtitles_api_key"
private const val MAX_JSON_BYTES = 2_000_000
private const val MAX_SUBTITLE_BYTES = 3_000_000
private const val HASH_BLOCK_BYTES = 64 * 1024

internal fun loadOpenSubtitlesSettings(context: Context): OpenSubtitlesSettings {
    val preferences = context.getSharedPreferences(
        OPEN_SUBTITLES_PREFERENCES,
        Context.MODE_PRIVATE
    )
    return OpenSubtitlesSettings(
        enabled = preferences.getBoolean(OPEN_SUBTITLES_ENABLED, false),
        apiKey = preferences.getString(OPEN_SUBTITLES_API_KEY, "").orEmpty()
    )
}

internal fun saveOpenSubtitlesSettings(
    context: Context,
    settings: OpenSubtitlesSettings
) {
    context.getSharedPreferences(OPEN_SUBTITLES_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(OPEN_SUBTITLES_ENABLED, settings.enabled)
        .putString(OPEN_SUBTITLES_API_KEY, settings.apiKey.trim())
        .apply()
}

internal object OpenSubtitles {
    private const val API = "https://api.opensubtitles.com/api/v1"

    fun downloadPortuguese(
        context: Context,
        title: String,
        episode: Int,
        apiKey: String,
        videoFile: File? = null
    ): RemoteSubtitle {
        val key = apiKey.trim()

        if (key.isBlank()) {
            throw IOException("Configure a chave da API do OpenSubtitles no perfil.")
        }

        val exactSearch = videoFile?.let { file ->
            val hash = runCatching { openSubtitlesHash(file) }.getOrNull() ?: return@let null
            runCatching {
                requestJson(
                    url = "$API/subtitles?moviehash=$hash&moviebytesize=${file.length()}&languages=pt-br&order_by=download_count&order_direction=desc",
                    apiKey = key
                )
            }.getOrNull()?.takeIf { response -> !response.optJSONArray("data").isNullOrEmpty() }
        }
        val search = exactSearch ?: run {
            val query = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
            requestJson(
                url = "$API/subtitles?type=episode&query=$query&episode_number=$episode&languages=pt-br&order_by=download_count&order_direction=desc",
                apiKey = key
            )
        }
        val result = search.optJSONArray("data")?.optJSONObject(0)
            ?: throw IOException("Nenhuma legenda em português foi encontrada.")
        val attributes = result.optJSONObject("attributes")
        val file = attributes?.optJSONArray("files")?.optJSONObject(0)
            ?: throw IOException("O OpenSubtitles não informou um arquivo para esta legenda.")
        val fileId = file.optInt("file_id").takeIf { id -> id > 0 }
            ?: throw IOException("O OpenSubtitles retornou uma legenda inválida.")
        val download = requestJson(
            url = "$API/download",
            apiKey = key,
            body = JSONObject()
                .put("file_id", fileId)
                .put("sub_format", "srt")
                .toString()
        )
        val link = download.optString("link")

        if (!link.startsWith("https://")) {
            throw IOException("O OpenSubtitles retornou um endereço de download inválido.")
        }

        val directory = File(context.filesDir, "subtitles").apply(File::mkdirs)
        val target = File(directory, "opensubtitles-$fileId.srt")
        downloadFile(link, target)

        return RemoteSubtitle(
            url = Uri.fromFile(target).toString(),
            language = "pt-BR",
            label = "Português (Brasil) • OpenSubtitles"
        )
    }

    private fun requestJson(
        url: String,
        apiKey: String,
        body: String? = null
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Api-Key", apiKey)
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { writer -> writer.write(body) }
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use(::readJsonResponse) ?: ByteArray(0)
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(String(response, StandardCharsets.UTF_8)).optString("message")
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "OpenSubtitles HTTP $code" })
            }

            JSONObject(String(response, StandardCharsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun readJsonResponse(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)

        while (true) {
            val count = input.read(buffer)

            if (count < 0) {
                break
            }

            output.write(buffer, 0, count)
            if (output.size() > MAX_JSON_BYTES) {
                throw IOException("A resposta do OpenSubtitles é grande demais.")
            }
        }

        return output.toByteArray()
    }

    private fun downloadFile(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true

        try {
            val code = connection.responseCode

            if (code !in 200..299) {
                throw IOException("Falha ao baixar a legenda: HTTP $code")
            }
            if (connection.url.protocol != "https") {
                throw IOException("O OpenSubtitles redirecionou para um endereço inválido.")
            }

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0

                    while (true) {
                        val count = input.read(buffer)

                        if (count < 0) {
                            break
                        }

                        total += count
                        if (total > MAX_SUBTITLE_BYTES) {
                            throw IOException("A legenda é grande demais.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (failure: Exception) {
            target.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }
}

internal fun openSubtitlesHash(file: File): String? {
    val size = file.length()
    if (!file.isFile || size < HASH_BLOCK_BYTES * 2L) return null
    var hash = size

    RandomAccessFile(file, "r").use { input ->
        repeat(HASH_BLOCK_BYTES / Long.SIZE_BYTES) {
            hash += java.lang.Long.reverseBytes(input.readLong())
        }
        input.seek(size - HASH_BLOCK_BYTES)
        repeat(HASH_BLOCK_BYTES / Long.SIZE_BYTES) {
            hash += java.lang.Long.reverseBytes(input.readLong())
        }
    }

    return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
}

private fun org.json.JSONArray?.isNullOrEmpty(): Boolean = this == null || length() == 0
