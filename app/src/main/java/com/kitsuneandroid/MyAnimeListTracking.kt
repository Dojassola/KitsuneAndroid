package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class MalConnection(
    val clientId: String,
    val username: String?,
    val connected: Boolean
)

internal data class MalListEntry(
    val malId: Int,
    val status: String,
    val watchedEpisodes: Int,
    val totalEpisodes: Int?
)

internal data class MalImportResult(
    val anime: List<Anime>,
    val entries: List<MalListEntry>
)

private data class MalTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long
)

internal object MyAnimeListTracking {
    private const val PREFERENCES = "kitsune"
    private const val CLIENT_ID = "mal_client_id"
    private const val USERNAME = "mal_username"
    private const val PENDING_STATE = "mal_pending_state"
    private const val PENDING_VERIFIER = "mal_pending_verifier"
    private const val TOKENS = "mal_tokens"
    private const val TRACKING_ENTRIES = "mal_tracking_entries"
    private const val ANIME_MAPPINGS = "mal_anime_mappings"
    private const val REDIRECT_URI = "kitsuneandroid://mal-auth"
    private const val OAUTH_BASE = "https://myanimelist.net/v1/oauth2"
    private const val API_BASE = "https://api.myanimelist.net/v2"
    private val trackingExecutor = Executors.newSingleThreadExecutor()

    fun connection(context: Context): MalConnection {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val clientId = preferences.getString(CLIENT_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: BuildConfig.MAL_CLIENT_ID
        val username = preferences.getString(USERNAME, null)
        return MalConnection(
            clientId = clientId,
            username = username,
            connected = MalSecrets.read(context, TOKENS) != null
        )
    }

    fun saveClientId(context: Context, clientId: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CLIENT_ID, clientId.trim())
            .apply()
    }

    fun beginAuthorization(context: Context, clientId: String): Uri {
        val normalizedClientId = clientId.trim()
        require(normalizedClientId.isNotEmpty()) {
            "Informe o Client ID do MyAnimeList."
        }
        saveClientId(context, normalizedClientId)
        val verifier = randomUrlToken(64)
        val state = randomUrlToken(32)
        MalSecrets.write(context, PENDING_VERIFIER, verifier)
        MalSecrets.write(context, PENDING_STATE, state)
        return Uri.parse("$OAUTH_BASE/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", normalizedClientId)
            .appendQueryParameter("code_challenge", verifier)
            .appendQueryParameter("code_challenge_method", "plain")
            .appendQueryParameter("state", state)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()
    }

    fun completeAuthorization(context: Context, callback: Uri): MalConnection {
        require(callback.scheme == "kitsuneandroid" && callback.host == "mal-auth") {
            "Retorno inválido do MyAnimeList."
        }
        callback.getQueryParameter("error")?.let { error ->
            throw IOException(callback.getQueryParameter("error_description") ?: error)
        }
        val expectedState = MalSecrets.read(context, PENDING_STATE)
        val returnedState = callback.getQueryParameter("state")
        require(!expectedState.isNullOrBlank() && returnedState == expectedState) {
            "A confirmação do MyAnimeList expirou. Tente conectar novamente."
        }
        val code = callback.getQueryParameter("code")
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("O MyAnimeList não retornou o código de autorização.")
        val verifier = MalSecrets.read(context, PENDING_VERIFIER)
            ?: throw IOException("A confirmação do MyAnimeList expirou.")
        val clientId = connection(context).clientId
        val tokenPayload = postForm(
            "$OAUTH_BASE/token",
            mapOf(
                "client_id" to clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to REDIRECT_URI
            )
        )
        saveTokens(context, tokenPayload)
        MalSecrets.remove(context, PENDING_STATE)
        MalSecrets.remove(context, PENDING_VERIFIER)

        val profile = apiGet(context, "$API_BASE/users/@me")
        val username = profile.optString("name")
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(USERNAME, username)
            .apply()
        return connection(context)
    }

    fun importList(context: Context): MalImportResult {
        val anime = mutableListOf<Anime>()
        val entries = mutableListOf<MalListEntry>()
        var offset = 0
        while (true) {
            val fields = "list_status,num_episodes,alternative_titles,main_picture," +
                "mean,media_type,start_season,status,genres,synopsis"
            val url = "$API_BASE/users/@me/animelist?limit=100&offset=$offset&" +
                "sort=list_updated_at&fields=${encode(fields)}"
            val payload = apiGet(context, url)
            val parsed = parseMalList(payload)
            anime += parsed.anime
            entries += parsed.entries
            if (payload.optJSONObject("paging")?.stringOrNull("next") == null || parsed.anime.isEmpty()) {
                break
            }
            offset += 100
        }
        saveEntries(context, entries)
        rememberAnime(context, anime)
        FavoriteRepository.addAll(context, anime)
        return MalImportResult(anime.distinctBy(Anime::malId), entries.distinctBy(MalListEntry::malId))
    }

    fun disconnect(context: Context) {
        MalSecrets.remove(context, TOKENS)
        MalSecrets.remove(context, PENDING_STATE)
        MalSecrets.remove(context, PENDING_VERIFIER)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(USERNAME)
            .apply()
    }

    fun rememberAnime(context: Context, anime: Anime) {
        rememberAnime(context, listOf(anime))
    }

    fun watchedEpisodes(context: Context, anime: Anime): Int {
        val malId = anime.malId ?: return 0
        return loadEntries(context)[malId]?.watchedEpisodes ?: 0
    }

    fun isTrackedEpisodeCompleted(context: Context, animeId: Int?, episode: Int?): Boolean {
        if (animeId == null || episode == null) {
            return false
        }
        val mapping = loadMappings(context).optJSONObject(animeId.toString()) ?: return false
        val malId = mapping.optInt("malId").takeIf { value -> value > 0 } ?: return false
        return episode <= (loadEntries(context)[malId]?.watchedEpisodes ?: 0)
    }

    fun recordCompletedEpisode(context: Context, animeId: Int?, episode: Int?) {
        if (animeId == null || episode == null || MalSecrets.read(context, TOKENS) == null) {
            return
        }
        val appContext = context.applicationContext
        trackingExecutor.execute {
            runCatching {
                val mapping = loadMappings(appContext).optJSONObject(animeId.toString())
                    ?: return@runCatching
                val malId = mapping.optInt("malId").takeIf { value -> value > 0 }
                    ?: return@runCatching
                val entries = loadEntries(appContext)
                val previous = entries[malId]
                if (previous != null && previous.watchedEpisodes >= episode) {
                    return@runCatching
                }
                val totalEpisodes = mapping.optInt("episodes").takeIf { value -> value > 0 }
                val status = malStatusForEpisode(episode, totalEpisodes)
                updateListStatus(appContext, malId, episode, status)
                saveEntries(
                    appContext,
                    entries.values + MalListEntry(malId, status, episode, totalEpisodes)
                )
            }
        }
    }

    private fun rememberAnime(context: Context, anime: Collection<Anime>) {
        val mappings = loadMappings(context)
        anime.forEach { item ->
            val malId = item.malId ?: return@forEach
            mappings.put(
                item.id.toString(),
                JSONObject()
                    .put("malId", malId)
                    .put("episodes", item.episodes ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ANIME_MAPPINGS, mappings.toString())
            .apply()
    }

    private fun updateListStatus(
        context: Context,
        malId: Int,
        episode: Int,
        status: String
    ) {
        val body = formBody(
            mapOf(
                "status" to status,
                "num_watched_episodes" to episode.toString()
            )
        )
        apiRequest(
            context = context,
            url = "$API_BASE/anime/$malId/my_list_status",
            method = "PUT",
            body = body
        )
    }

    private fun apiGet(context: Context, url: String): JSONObject {
        return apiRequest(context, url, "GET")
    }

    private fun apiRequest(
        context: Context,
        url: String,
        method: String,
        body: String? = null
    ): JSONObject {
        val accessToken = accessToken(context)
        return requestJson(
            url = url,
            method = method,
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            body = body
        )
    }

    private fun accessToken(context: Context): String {
        val tokens = readTokens(context)
            ?: throw IOException("Conecte sua conta do MyAnimeList novamente.")
        if (tokens.expiresAtMs > System.currentTimeMillis() + 60_000) {
            return tokens.accessToken
        }
        val refreshToken = tokens.refreshToken
            ?: throw IOException("A sessão do MyAnimeList expirou.")
        val payload = postForm(
            "$OAUTH_BASE/token",
            mapOf(
                "client_id" to connection(context).clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken
            )
        )
        saveTokens(context, payload, refreshToken)
        return requireNotNull(readTokens(context)).accessToken
    }

    private fun saveTokens(
        context: Context,
        payload: JSONObject,
        fallbackRefreshToken: String? = null
    ) {
        val accessToken = payload.optString("access_token")
        if (accessToken.isBlank()) {
            throw IOException("O MyAnimeList não retornou um token válido.")
        }
        val tokens = JSONObject()
            .put("accessToken", accessToken)
            .put("refreshToken", payload.stringOrNull("refresh_token") ?: fallbackRefreshToken)
            .put(
                "expiresAtMs",
                System.currentTimeMillis() + payload.optLong("expires_in", 3_600L) * 1_000
            )
        MalSecrets.write(context, TOKENS, tokens.toString())
    }

    private fun readTokens(context: Context): MalTokens? {
        val payload = MalSecrets.read(context, TOKENS) ?: return null
        return runCatching {
            val json = JSONObject(payload)
            MalTokens(
                accessToken = json.getString("accessToken"),
                refreshToken = json.stringOrNull("refreshToken"),
                expiresAtMs = json.getLong("expiresAtMs")
            )
        }.getOrNull()
    }

    private fun loadEntries(context: Context): Map<Int, MalListEntry> {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TRACKING_ENTRIES, "[]")
            .orEmpty()
        return runCatching {
            val array = JSONArray(value)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val entry = MalListEntry(
                        malId = item.getInt("malId"),
                        status = item.getString("status"),
                        watchedEpisodes = item.optInt("watchedEpisodes"),
                        totalEpisodes = item.optInt("totalEpisodes").takeIf { count -> count > 0 }
                    )
                    put(entry.malId, entry)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveEntries(context: Context, entries: Collection<MalListEntry>) {
        val array = JSONArray()
        entries.distinctBy(MalListEntry::malId).forEach { entry ->
            array.put(
                JSONObject()
                    .put("malId", entry.malId)
                    .put("status", entry.status)
                    .put("watchedEpisodes", entry.watchedEpisodes)
                    .put("totalEpisodes", entry.totalEpisodes ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(TRACKING_ENTRIES, array.toString())
            .apply()
    }

    private fun loadMappings(context: Context): JSONObject {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ANIME_MAPPINGS, "{}")
            .orEmpty()
        return runCatching { JSONObject(value) }.getOrDefault(JSONObject())
    }
}

internal fun parseMalList(payload: JSONObject): MalImportResult {
    val data = payload.optJSONArray("data") ?: JSONArray()
    val anime = mutableListOf<Anime>()
    val entries = mutableListOf<MalListEntry>()
    for (index in 0 until data.length()) {
        val item = data.optJSONObject(index) ?: continue
        val node = item.optJSONObject("node") ?: continue
        val malId = node.optInt("id").takeIf { value -> value > 0 } ?: continue
        val title = node.optString("title").takeIf(String::isNotBlank) ?: continue
        val alternativeTitles = node.optJSONObject("alternative_titles")
        val startSeason = node.optJSONObject("start_season")
        val picture = node.optJSONObject("main_picture")
        val totalEpisodes = node.optInt("num_episodes").takeIf { count -> count > 0 }
        anime += Anime(
            id = -1_600_000_000 + malId,
            malId = malId,
            title = alternativeTitles?.stringOrNull("en") ?: title,
            romajiTitle = title,
            englishTitle = alternativeTitles?.stringOrNull("en"),
            description = node.optString("synopsis"),
            cover = picture?.stringOrNull("large") ?: picture?.stringOrNull("medium").orEmpty(),
            banner = null,
            episodes = totalEpisodes,
            score = node.optDouble("mean").takeIf { score -> score > 0 }?.times(10)?.toInt(),
            year = startSeason?.optInt("year")?.takeIf { year -> year > 0 },
            season = startSeason?.stringOrNull("season"),
            format = node.stringOrNull("media_type")?.uppercase(),
            status = node.stringOrNull("status"),
            genres = node.optJSONArray("genres")?.let { genres ->
                buildList {
                    for (genreIndex in 0 until genres.length()) {
                        genres.optJSONObject(genreIndex)?.stringOrNull("name")?.let(::add)
                    }
                }
            }.orEmpty(),
            aliases = alternativeTitles?.optJSONArray("synonyms").strings()
        )
        val listStatus = item.optJSONObject("list_status") ?: JSONObject()
        entries += MalListEntry(
            malId = malId,
            status = listStatus.optString("status").ifBlank { "plan_to_watch" },
            watchedEpisodes = listStatus.optInt("num_episodes_watched"),
            totalEpisodes = totalEpisodes
        )
    }
    return MalImportResult(anime, entries)
}

internal fun malStatusForEpisode(episode: Int, totalEpisodes: Int?): String {
    return if (totalEpisodes != null && episode >= totalEpisodes) {
        "completed"
    } else {
        "watching"
    }
}

private fun randomUrlToken(bytes: Int): String {
    val value = ByteArray(bytes)
    SecureRandom().nextBytes(value)
    return Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

private fun postForm(url: String, values: Map<String, String>): JSONObject {
    return requestJson(url, "POST", body = formBody(values))
}

private fun requestJson(
    url: String,
    method: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null
): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 15_000
    connection.readTimeout = 20_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.0")
    headers.forEach(connection::setRequestProperty)
    if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { output ->
            output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
    }
    return try {
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { reader -> reader.readText() }
            .orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
            throw IOException(message?.takeIf(String::isNotBlank) ?: "MyAnimeList HTTP $status")
        }
        JSONObject(response.ifBlank { "{}" })
    } finally {
        connection.disconnect()
    }
}

private fun formBody(values: Map<String, String>): String {
    return values.entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }
}

private fun encode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private object MalSecrets {
    private const val KEY_ALIAS = "kitsune_mal_tokens"
    private const val PREFERENCES = "kitsune_mal_credentials"

    fun write(context: Context, key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(key, payload)
            .apply()
    }

    fun read(context: Context, key: String): String? {
        val payload = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key, null)
            ?: return null
        return runCatching {
            val parts = payload.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                StandardCharsets.UTF_8
            )
        }.getOrElse {
            remove(context, key)
            null
        }
    }

    fun remove(context: Context, key: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { key ->
            return key
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
