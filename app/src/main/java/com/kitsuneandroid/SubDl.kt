package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

internal data class SubDlCandidate(
    val url: String,
    val name: String,
    val releaseName: String,
    val language: String,
    val episode: Int?,
    val fps: Double,
    val format: String?,
    val directFile: Boolean
)

internal object SubDlProvider : OnlineSubtitleProvider {
    override val name = "SubDL"

    override fun download(
        context: Context,
        request: SubtitleSearchRequest,
        apiKey: String
    ): RemoteSubtitle {
        val key = apiKey.trim()
        if (key.isBlank()) {
            throw IOException("Configure a chave da API do SubDL no perfil.")
        }

        val search = requestSubDl(
            url = subDlSearchUrl(request, key)
        )
        if (!search.optBoolean("status", false)) {
            throw IOException(search.optString("error").ifBlank { "A busca do SubDL falhou." })
        }
        val selected = selectSubDlCandidate(search, request)
            ?: throw IOException("Nenhuma legenda compatível foi encontrada.")
        val file = downloadSubDlCandidate(context, selected, key)
        return RemoteSubtitle(
            url = Uri.fromFile(file).toString(),
            language = request.language,
            label = "${openSubtitlesLanguageLabel(request.language)} • SubDL"
        )
    }
}

internal fun selectSubDlCandidate(
    response: JSONObject,
    request: SubtitleSearchRequest
): SubDlCandidate? {
    val subtitles = response.optJSONArray("subtitles") ?: return null
    val candidates = buildList {
        for (index in 0 until subtitles.length()) {
            val subtitle = subtitles.optJSONObject(index) ?: continue
            val unpacked = subtitle.optJSONArray("unpack_files")
            if (unpacked != null && unpacked.length() > 0) {
                for (fileIndex in 0 until unpacked.length()) {
                    val file = unpacked.optJSONObject(fileIndex) ?: continue
                    parseSubDlCandidate(file, directFile = true)?.let(::add)
                }
            } else {
                parseSubDlCandidate(subtitle, directFile = false)?.let(::add)
            }
        }
    }.filter { candidate ->
        candidate.episode == null || candidate.episode == request.episode
    }

    return candidates.maxWithOrNull(
        compareBy<SubDlCandidate> { candidate -> candidate.episode == request.episode }
            .thenBy { candidate -> candidate.directFile }
            .thenBy { candidate ->
                subtitleReleaseMatchScore(
                    request.videoName,
                    "${candidate.releaseName} ${candidate.name}",
                    request.title
                )
            }
            .thenBy { candidate ->
                subtitleFpsCompatibility(request.videoFps, candidate.fps)
            }
    )
}

private fun parseSubDlCandidate(value: JSONObject, directFile: Boolean): SubDlCandidate? {
    val url = subDlDownloadUrl(value.optString("url")) ?: return null
    val format = value.stringOrNull("format")
        ?.lowercase()
        ?.takeIf(SUPPORTED_SUBTITLE_FORMATS::contains)
    return SubDlCandidate(
        url = url,
        name = value.optString("name"),
        releaseName = value.optString("release_name"),
        language = value.optString("language"),
        episode = value.optInt("episode").takeIf { episode -> episode > 0 },
        fps = value.optString("fps").toDoubleOrNull() ?: 0.0,
        format = format,
        directFile = directFile
    )
}

private fun subDlSearchUrl(request: SubtitleSearchRequest, apiKey: String): String {
    val parameters = listOf(
        "api_key" to apiKey,
        "film_name" to request.title,
        "file_name" to request.videoName,
        "type" to "tv",
        "season_number" to subDlSeasonNumber(request.videoName)?.toString(),
        "episode_number" to request.episode.toString(),
        "languages" to subDlLanguage(request.language),
        "subs_per_page" to "30",
        "releases" to "1",
        "unpack" to "1",
        "client" to "custom_integration"
    ).filter { (_, value) -> !value.isNullOrBlank() }
        .joinToString("&") { (name, value) ->
            "${name.urlEncoded()}=${requireNotNull(value).urlEncoded()}"
        }
    return "$SUBDL_SEARCH_API?$parameters"
}

internal fun subDlSeasonNumber(videoName: String?): Int? {
    val value = videoName ?: return null
    return Regex("(?i)\\bS(?:eason)?[ ._-]*0?(\\d{1,2})\\b")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { season -> season > 0 }
}

private fun subDlLanguage(language: String): String {
    return when (language.lowercase()) {
        "pt-br" -> "BR_PT"
        "pt-pt" -> "PT"
        "en" -> "EN"
        "es" -> "ES"
        "ja" -> "JA"
        "fr" -> "FR"
        "de" -> "DE"
        "it" -> "IT"
        else -> "BR_PT"
    }
}

private fun subDlDownloadUrl(value: String): String? {
    val candidate = if (value.startsWith("/")) {
        "$SUBDL_DOWNLOAD_HOST$value"
    } else {
        value
    }
    val url = runCatching { URL(candidate) }.getOrNull() ?: return null
    if (url.protocol != "https" || url.host.lowercase() != "dl.subdl.com") {
        return null
    }
    return url.toString()
}

private fun requestSubDl(url: String): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.3")
    return try {
        val responseCode = connection.responseCode
        val input = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val response = input?.use { stream ->
            readBounded(stream, MAX_SUBDL_JSON_BYTES)
        } ?: ByteArray(0)
        if (responseCode !in 200..299) {
            val message = runCatching {
                JSONObject(response.toString(StandardCharsets.UTF_8)).optString("error")
            }.getOrNull().orEmpty()
            throw IOException(message.ifBlank { "SubDL HTTP $responseCode" })
        }
        JSONObject(response.toString(StandardCharsets.UTF_8))
    } finally {
        connection.disconnect()
    }
}

private fun downloadSubDlCandidate(
    context: Context,
    candidate: SubDlCandidate,
    apiKey: String
): File {
    val directory = File(context.filesDir, "subtitles").apply(File::mkdirs)
    val cachePrefix = "subdl-${sha1(candidate.url)}"
    directory.listFiles { file -> file.name.startsWith("$cachePrefix.") }
        ?.firstOrNull { file -> file.length() in 1..MAX_SUBDL_FILE_BYTES.toLong() }
        ?.let { return it }

    val connection = URL(candidate.url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("x-api-key", apiKey)
    val bytes = try {
        if (connection.responseCode !in 200..299) {
            throw IOException("Falha ao baixar do SubDL: HTTP ${connection.responseCode}")
        }
        if (connection.url.protocol != "https" || connection.url.host != "dl.subdl.com") {
            throw IOException("O SubDL redirecionou para um endereço inválido.")
        }
        connection.inputStream.use { input -> readBounded(input, MAX_SUBDL_FILE_BYTES) }
    } finally {
        connection.disconnect()
    }

    val (subtitleBytes, archiveExtension) = if (bytes.isZip()) {
        extractSubtitleFromZip(bytes)
    } else {
        bytes to null
    }
    val extension = candidate.format
        ?: subtitleExtension(candidate.name)
        ?: archiveExtension
        ?: "srt"
    val target = File(directory, "$cachePrefix.$extension")
    val temporary = File.createTempFile("$cachePrefix.", ".tmp", directory)
    try {
        temporary.writeBytes(subtitleBytes)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
        }
    } finally {
        temporary.delete()
    }
    return target
}

private fun extractSubtitleFromZip(bytes: ByteArray): Pair<ByteArray, String> {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var inspectedEntries = 0
        while (inspectedEntries < MAX_ZIP_ENTRIES) {
            val entry = zip.nextEntry ?: break
            inspectedEntries++
            val extension = subtitleExtension(entry.name)
            if (!entry.isDirectory && extension != null) {
                return readBounded(zip, MAX_SUBDL_FILE_BYTES) to extension
            }
        }
    }
    throw IOException("O pacote do SubDL não contém uma legenda compatível.")
}

private fun subtitleExtension(value: String): String? {
    return value.substringAfterLast('.', "")
        .lowercase()
        .takeIf(SUPPORTED_SUBTITLE_FORMATS::contains)
}

private fun ByteArray.isZip(): Boolean {
    return size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()
}

private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) {
            break
        }
        output.write(buffer, 0, count)
        if (output.size() > maximumBytes) {
            throw IOException("A resposta do SubDL é grande demais.")
        }
    }
    return output.toByteArray()
}

private fun String.urlEncoded(): String {
    return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

private val SUPPORTED_SUBTITLE_FORMATS = setOf("srt", "ass", "ssa", "vtt")
private const val SUBDL_SEARCH_API = "https://api.subdl.com/api/v1/subtitles"
private const val SUBDL_DOWNLOAD_HOST = "https://dl.subdl.com"
private const val MAX_SUBDL_JSON_BYTES = 2_000_000
private const val MAX_SUBDL_FILE_BYTES = 5_000_000
private const val MAX_ZIP_ENTRIES = 100
