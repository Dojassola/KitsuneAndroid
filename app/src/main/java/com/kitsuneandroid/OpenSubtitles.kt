package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class SubtitleProviderSettings(
    val language: String = "pt-br",
    val openSubtitlesEnabled: Boolean = false,
    val openSubtitlesApiKey: String = "",
    val subDlEnabled: Boolean = false,
    val subDlApiKey: String = ""
)

internal data class OpenSubtitlesSession(
    val username: String,
    val token: String,
    val apiBaseUrl: String
)

internal data class OpenSubtitlesLanguage(val code: String, val label: String)

internal val OPEN_SUBTITLES_LANGUAGES = listOf(
    OpenSubtitlesLanguage("pt-br", "Português (Brasil)"),
    OpenSubtitlesLanguage("pt-pt", "Português (Portugal)"),
    OpenSubtitlesLanguage("en", "Inglês"),
    OpenSubtitlesLanguage("es", "Espanhol"),
    OpenSubtitlesLanguage("ja", "Japonês"),
    OpenSubtitlesLanguage("fr", "Francês"),
    OpenSubtitlesLanguage("de", "Alemão"),
    OpenSubtitlesLanguage("it", "Italiano")
)

private const val OPEN_SUBTITLES_PREFERENCES = "kitsune"
private const val OPEN_SUBTITLES_ENABLED = "open_subtitles_enabled"
private const val OPEN_SUBTITLES_API_KEY = "open_subtitles_api_key"
private const val OPEN_SUBTITLES_LANGUAGE = "open_subtitles_language"
private const val SUBDL_ENABLED = "subdl_enabled"
private const val SUBDL_API_KEY = "subdl_api_key"
private const val OPEN_SUBTITLES_SESSION_PREFERENCES = "open_subtitles_session"
private const val OPEN_SUBTITLES_USERNAME = "username"
private const val OPEN_SUBTITLES_TOKEN = "token"
private const val OPEN_SUBTITLES_API_BASE_URL = "api_base_url"
private const val MAX_JSON_BYTES = 2_000_000
private const val MAX_SUBTITLE_BYTES = 3_000_000
private const val HASH_BLOCK_BYTES = 64 * 1024

private val RELEASE_NOISE_TOKENS = setOf(
    "1080p", "720p", "480p", "2160p", "webrip", "webdl", "bluray", "bdrip",
    "hevc", "x265", "h265", "x264", "h264", "avc", "aac", "flac", "mkv", "mp4",
    "multi", "multiple", "subtitle", "subtitles"
)

internal data class OpenSubtitleCandidate(
    val file: JSONObject,
    val releaseMatches: Int,
    val fpsCompatibility: Int,
    val fps: Double,
    val trusted: Boolean,
    val downloads: Int
)

internal fun loadSubtitleProviderSettings(context: Context): SubtitleProviderSettings {
    val preferences = context.getSharedPreferences(
        OPEN_SUBTITLES_PREFERENCES,
        Context.MODE_PRIVATE
    )
    return SubtitleProviderSettings(
        language = normalizeOpenSubtitlesLanguage(
            preferences.getString(OPEN_SUBTITLES_LANGUAGE, "pt-br").orEmpty()
        ),
        openSubtitlesEnabled = preferences.getBoolean(OPEN_SUBTITLES_ENABLED, false),
        openSubtitlesApiKey = preferences.getString(OPEN_SUBTITLES_API_KEY, "").orEmpty(),
        subDlEnabled = preferences.getBoolean(SUBDL_ENABLED, false),
        subDlApiKey = preferences.getString(SUBDL_API_KEY, "").orEmpty()
    )
}

internal fun SubtitleProviderSettings.hasConfiguredProvider(): Boolean {
    return (openSubtitlesEnabled && openSubtitlesApiKey.isNotBlank()) ||
        (subDlEnabled && subDlApiKey.isNotBlank())
}

internal fun SubtitleProviderSettings.matches(language: ReleaseLanguage): Boolean {
    if (!hasConfiguredProvider()) {
        return false
    }

    return when (language) {
        ReleaseLanguage.PORTUGUESE -> this.language.startsWith("pt-")
        ReleaseLanguage.ENGLISH -> this.language == "en"
        ReleaseLanguage.ANY -> true
        ReleaseLanguage.JAPANESE,
        ReleaseLanguage.DUBBED -> false
    }
}

internal fun saveSubtitleProviderSettings(
    context: Context,
    settings: SubtitleProviderSettings
) {
    context.getSharedPreferences(OPEN_SUBTITLES_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(OPEN_SUBTITLES_ENABLED, settings.openSubtitlesEnabled)
        .putString(OPEN_SUBTITLES_API_KEY, settings.openSubtitlesApiKey.trim())
        .putString(OPEN_SUBTITLES_LANGUAGE, normalizeOpenSubtitlesLanguage(settings.language))
        .putBoolean(SUBDL_ENABLED, settings.subDlEnabled)
        .putString(SUBDL_API_KEY, settings.subDlApiKey.trim())
        .apply()
}

internal fun loadOpenSubtitlesSession(context: Context): OpenSubtitlesSession? {
    val preferences = context.getSharedPreferences(
        OPEN_SUBTITLES_SESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )
    val token = preferences.getString(OPEN_SUBTITLES_TOKEN, "").orEmpty()
    if (token.isBlank()) {
        return null
    }

    return OpenSubtitlesSession(
        username = preferences.getString(OPEN_SUBTITLES_USERNAME, "").orEmpty(),
        token = token,
        apiBaseUrl = openSubtitlesApiBaseUrl(
            preferences.getString(OPEN_SUBTITLES_API_BASE_URL, "").orEmpty()
        )
    )
}

internal fun clearOpenSubtitlesSession(context: Context) {
    context.getSharedPreferences(OPEN_SUBTITLES_SESSION_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}

internal fun openSubtitlesLanguageLabel(code: String): String {
    return OPEN_SUBTITLES_LANGUAGES.firstOrNull { language -> language.code == code }?.label
        ?: OPEN_SUBTITLES_LANGUAGES.first().label
}

private fun normalizeOpenSubtitlesLanguage(code: String): String {
    return code.lowercase().takeIf { value ->
        OPEN_SUBTITLES_LANGUAGES.any { language -> language.code == value }
    } ?: "pt-br"
}

internal object OpenSubtitles {
    private const val API = "https://api.opensubtitles.com/api/v1"

    fun login(
        context: Context,
        apiKey: String,
        username: String,
        password: String
    ): OpenSubtitlesSession {
        val key = apiKey.trim()
        val account = username.trim()
        if (key.isBlank() || account.isBlank() || password.isBlank()) {
            throw IOException("Informe a chave da API, o usuário e a senha.")
        }

        val response = requestJson(
            context = context,
            url = "$API/login",
            apiKey = key,
            body = JSONObject()
                .put("username", account)
                .put("password", password)
                .toString()
        )
        val token = response.optString("token")
        if (token.isBlank()) {
            throw IOException("O OpenSubtitles não retornou uma sessão válida.")
        }

        val session = OpenSubtitlesSession(
            username = response.optJSONObject("user")?.optString("username").orEmpty().ifBlank { account },
            token = token,
            apiBaseUrl = openSubtitlesApiBaseUrl(response.optString("base_url"))
        )
        context.getSharedPreferences(OPEN_SUBTITLES_SESSION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(OPEN_SUBTITLES_USERNAME, session.username)
            .putString(OPEN_SUBTITLES_TOKEN, session.token)
            .putString(OPEN_SUBTITLES_API_BASE_URL, session.apiBaseUrl)
            .apply()
        return session
    }

    fun downloadSubtitle(
        context: Context,
        title: String,
        episode: Int,
        apiKey: String,
        language: String,
        videoFile: File? = null,
        videoName: String? = videoFile?.name,
        videoFps: Float? = null
    ): RemoteSubtitle {
        val key = apiKey.trim()
        val languageCode = normalizeOpenSubtitlesLanguage(language)
        val session = loadOpenSubtitlesSession(context)
        val api = session?.apiBaseUrl ?: API

        if (key.isBlank()) {
            throw IOException("Configure a chave da API do OpenSubtitles no perfil.")
        }

        val exactSearch = videoFile?.let { file ->
            val hash = runCatching { openSubtitlesHash(file) }.getOrNull() ?: return@let null
            runCatching {
                requestJson(
                    context = context,
                    url = "$api/subtitles?moviehash=$hash&moviebytesize=${file.length()}&moviehash_match=only&languages=$languageCode&order_by=download_count&order_direction=desc",
                    apiKey = key,
                    token = session?.token
                )
            }.getOrNull()?.takeIf { response -> !response.optJSONArray("data").isNullOrEmpty() }
        }
        val search = exactSearch ?: run {
            val query = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
            requestJson(
                context = context,
                url = "$api/subtitles?type=episode&query=$query&episode_number=$episode&languages=$languageCode&order_by=download_count&order_direction=desc",
                apiKey = key,
                token = session?.token
            )
        }
        val selected = selectOpenSubtitleCandidate(
            search = search,
            title = title,
            videoName = videoName,
            videoFps = videoFps
        )
            ?: throw IOException("O OpenSubtitles não informou um arquivo para esta legenda.")
        val file = selected.file
        val fileId = file.optInt("file_id").takeIf { id -> id > 0 }
            ?: throw IOException("O OpenSubtitles retornou uma legenda inválida.")
        val directory = File(context.filesDir, "subtitles").apply(File::mkdirs)
        val target = subtitleCacheFile(
            directory = directory,
            fileId = fileId,
            videoFps = videoFps,
            subtitleFps = selected.fps
        )
        if (target.isFile && target.length() in 1..MAX_SUBTITLE_BYTES.toLong()) {
            return remoteOpenSubtitle(target, languageCode)
        }

        val downloadRequest = JSONObject()
            .put("file_id", fileId)
            .put("sub_format", "srt")
        subtitleFpsConversion(videoFps, selected.fps)?.let { conversion ->
            downloadRequest
                .put("in_fps", conversion.first)
                .put("out_fps", conversion.second)
        }
        val download = requestJson(
            context = context,
            url = "$api/download",
            apiKey = key,
            body = downloadRequest.toString(),
            token = session?.token
        )
        val link = download.optString("link")

        if (!link.startsWith("https://")) {
            throw IOException("O OpenSubtitles retornou um endereço de download inválido.")
        }

        downloadFile(link, target)
        return remoteOpenSubtitle(target, languageCode)
    }

    private fun requestJson(
        context: Context,
        url: String,
        apiKey: String,
        body: String? = null,
        token: String? = null
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Api-Key", apiKey)
        connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }

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
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED && token != null) {
                    clearOpenSubtitlesSession(context)
                    return requestJson(context, url, apiKey, body)
                }
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
        val temporary = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
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
                temporary.outputStream().use { output ->
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
            if (target.isFile && !target.delete()) {
                throw IOException("Não foi possível atualizar a legenda armazenada.")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }
}

internal fun selectOpenSubtitleCandidate(
    search: JSONObject,
    title: String,
    videoName: String?,
    videoFps: Float?
): OpenSubtitleCandidate? {
    val results = search.optJSONArray("data") ?: return null
    val candidates = buildList {
        for (index in 0 until results.length()) {
            val attributes = results.optJSONObject(index)?.optJSONObject("attributes") ?: continue
            val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: continue
            if (file.optInt("file_id") <= 0) {
                continue
            }

            val subtitleName = listOf(
                attributes.optString("release"),
                file.optString("file_name")
            ).joinToString(" ")
            val subtitleFps = attributes.optDouble("fps", 0.0)
            add(
                OpenSubtitleCandidate(
                    file = file,
                    releaseMatches = subtitleReleaseMatchScore(videoName, subtitleName, title),
                    fpsCompatibility = subtitleFpsCompatibility(videoFps, subtitleFps),
                    fps = subtitleFps,
                    trusted = attributes.optBoolean("from_trusted"),
                    downloads = attributes.optInt("download_count")
                )
            )
        }
    }

    return candidates.maxWithOrNull(
        compareBy<OpenSubtitleCandidate> { candidate -> candidate.releaseMatches }
            .thenBy { candidate -> candidate.fpsCompatibility }
            .thenBy { candidate -> candidate.trusted }
            .thenBy { candidate -> candidate.downloads }
    )
}

internal fun subtitleReleaseMatchScore(
    videoName: String?,
    subtitleName: String,
    title: String
): Int {
    if (videoName.isNullOrBlank()) {
        return 0
    }

    val ignored = releaseTokens(title) + RELEASE_NOISE_TOKENS
    val videoTokens = releaseTokens(videoName) - ignored
    val subtitleTokens = releaseTokens(subtitleName) - ignored
    return videoTokens.intersect(subtitleTokens).size
}

private fun releaseTokens(value: String): Set<String> {
    return value.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filterTo(mutableSetOf()) { token -> token.length >= 3 }
}

internal fun subtitleFpsCompatibility(videoFps: Float?, subtitleFps: Double): Int {
    if (videoFps == null || videoFps <= 0 || subtitleFps <= 0) {
        return 1
    }

    return if (kotlin.math.abs(videoFps - subtitleFps) <= 0.05) {
        2
    } else {
        0
    }
}

internal fun subtitleFpsConversion(videoFps: Float?, subtitleFps: Double): Pair<Double, Double>? {
    if (subtitleFpsCompatibility(videoFps, subtitleFps) != 0 || videoFps == null) {
        return null
    }

    return subtitleFps to videoFps.toDouble()
}

private fun subtitleCacheFile(
    directory: File,
    fileId: Int,
    videoFps: Float?,
    subtitleFps: Double
): File {
    val conversion = subtitleFpsConversion(videoFps, subtitleFps)
    val timing = if (conversion == null) {
        "original"
    } else {
        "${conversion.first}-${conversion.second}"
    }
    val timingHash = MessageDigest.getInstance("SHA-256")
        .digest(timing.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> "%02x".format(byte) }
    return File(directory, "opensubtitles-$fileId-$timingHash.srt")
}

private fun remoteOpenSubtitle(file: File, languageCode: String): RemoteSubtitle {
    return RemoteSubtitle(
        url = Uri.fromFile(file).toString(),
        language = languageCode,
        label = "${openSubtitlesLanguageLabel(languageCode)} • OpenSubtitles"
    )
}

internal fun openSubtitlesApiBaseUrl(value: String): String {
    val candidate = if (value.startsWith("https://")) {
        value
    } else {
        "https://$value"
    }
    val uri = runCatching { URI(candidate) }.getOrNull()
    val host = uri?.host?.lowercase()
    if (uri?.scheme != "https" || host == null ||
        (host != "opensubtitles.com" && !host.endsWith(".opensubtitles.com"))
    ) {
        return "https://api.opensubtitles.com/api/v1"
    }

    return "https://$host/api/v1"
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
