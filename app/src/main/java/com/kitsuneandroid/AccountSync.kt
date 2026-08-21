package com.kitsuneandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors

internal enum class AccountProvider {
    ANILIST,
    MY_ANIME_LIST
}

internal data class AccountConnection(
    val configured: Boolean,
    val connected: Boolean,
    val username: String?,
    val lastSyncAt: Long,
    val error: String?
)

private data class OAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long
)

internal enum class RemoteWatchStatus {
    PLANNING,
    WATCHING,
    COMPLETED
}

private data class RemoteEntry(
    val anime: Anime,
    val progress: Int,
    val status: RemoteWatchStatus
)

private data class RemoteSnapshot(
    val username: String,
    val entries: List<RemoteEntry>
)

private data class LocalEntry(
    val anime: Anime,
    val progress: Int,
    val status: RemoteWatchStatus
)

internal object AccountSync {
    private const val REDIRECT_BASE = "kitsune://oauth"
    private const val PREFS = "kitsune_account_sync"
    private const val PENDING = "pending_progress"
    private val worker = Executors.newSingleThreadExecutor()
    val revision = mutableIntStateOf(0)

    fun connection(context: Context, provider: AccountProvider): AccountConnection {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AccountConnection(
            configured = clientId(provider).isNotBlank(),
            connected = SecureTokenStore.load(context, provider) != null,
            username = preferences.getString("username:${provider.name}", null),
            lastSyncAt = preferences.getLong("last_sync:${provider.name}", 0),
            error = preferences.getString("error:${provider.name}", null)
        )
    }

    fun connect(context: Context, provider: AccountProvider): String? {
        val clientId = clientId(provider)
        if (clientId.isBlank()) {
            return context.getString(R.string.account_sync_not_configured)
        }

        val state = randomUrlSafe(24)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.edit().putString("state:${provider.name}", state).apply()
        val url = when (provider) {
            AccountProvider.ANILIST -> {
                "https://anilist.co/api/v2/oauth/authorize" + queryParameters(
                    "client_id" to clientId,
                    "response_type" to "token",
                    "state" to state
                )
            }

            AccountProvider.MY_ANIME_LIST -> {
                val verifier = randomUrlSafe(64)
                preferences.edit().putString("verifier:${provider.name}", verifier).apply()
                "https://myanimelist.net/v1/oauth2/authorize" + queryParameters(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "code_challenge" to verifier,
                    "code_challenge_method" to "plain",
                    "state" to state,
                    "redirect_uri" to "$REDIRECT_BASE/mal"
                )
            }
        }

        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (error: Exception) {
            error.message ?: context.getString(R.string.account_sync_browser_error)
        }
    }

    fun handleOAuthCallback(context: Context, uri: Uri?): Boolean {
        if (uri?.scheme != "kitsune" || uri.host != "oauth") {
            return false
        }

        val provider = when (uri.pathSegments.firstOrNull()) {
            "anilist" -> AccountProvider.ANILIST
            "mal" -> AccountProvider.MY_ANIME_LIST
            else -> return false
        }
        val parameters = oauthParameters(uri)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expectedState = preferences.getString("state:${provider.name}", null)
        if (expectedState.isNullOrBlank() || parameters["state"] != expectedState) {
            saveError(context, provider, context.getString(R.string.account_sync_invalid_return))
            return true
        }

        preferences.edit().remove("state:${provider.name}").apply()
        worker.execute {
            try {
                val token = when (provider) {
                    AccountProvider.ANILIST -> aniListToken(parameters)
                    AccountProvider.MY_ANIME_LIST -> malToken(context, parameters)
                }
                SecureTokenStore.save(context, provider, token)
                saveError(context, provider, null)
                sync(context, provider)
            } catch (error: Exception) {
                saveError(context, provider, error.message ?: context.getString(R.string.account_sync_failed))
            }
        }
        return true
    }

    fun disconnect(context: Context, provider: AccountProvider) {
        SecureTokenStore.remove(context, provider)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("username:${provider.name}")
            .remove("last_sync:${provider.name}")
            .remove("error:${provider.name}")
            .apply()
        notifyChanged()
    }

    fun syncNow(context: Context, provider: AccountProvider) {
        worker.execute {
            try {
                sync(context, provider)
            } catch (error: Exception) {
                saveError(context, provider, error.message ?: context.getString(R.string.account_sync_failed))
            }
        }
    }

    fun enqueueCompleted(context: Context, animeId: Int?, episode: Int?) {
        if (animeId == null || episode == null || episode <= 0 || connectedProviders(context).isEmpty()) {
            return
        }

        val pending = loadPending(context).toMutableMap()
        pending[animeId] = maxOf(pending[animeId] ?: 0, episode)
        savePending(context, pending)
        flush(context)
    }

    fun flush(context: Context) {
        worker.execute {
            val providers = connectedProviders(context)
            if (providers.isEmpty()) {
                return@execute
            }

            val successful = providers.all { provider ->
                try {
                    sync(context, provider)
                    true
                } catch (error: Exception) {
                    saveError(context, provider, error.message ?: context.getString(R.string.account_sync_failed))
                    false
                }
            }
            if (successful) {
                savePending(context, emptyMap())
            }
        }
    }

    private fun sync(context: Context, provider: AccountProvider) {
        val token = validToken(context, provider)
            ?: throw IOException(context.getString(R.string.account_sync_login_required))
        val snapshot = when (provider) {
            AccountProvider.ANILIST -> pullAniList(token.accessToken)
            AccountProvider.MY_ANIME_LIST -> pullMal(token.accessToken)
        }

        MediaListRepository.replaceNamed(context, provider.listName(), snapshot.entries.map(RemoteEntry::anime))
        snapshot.entries.forEach { entry ->
            VideoHistory.importCompletedEpisodes(context, entry.anime.id, entry.progress)
        }

        val remoteById = snapshot.entries.associateBy { entry -> provider.remoteId(entry.anime) }
        localEntries(context).forEach { local ->
            val remoteId = provider.remoteId(local.anime) ?: return@forEach
            val remote = remoteById[remoteId]
            val progress = maxOf(local.progress, remote?.progress ?: 0)
            val status = if (remote?.status == RemoteWatchStatus.COMPLETED) {
                RemoteWatchStatus.COMPLETED
            } else {
                remoteWatchStatus(progress, local.anime.episodes)
            }
            val merged = local.copy(progress = progress, status = status)
            if (remote?.progress == merged.progress && remote.status == merged.status) {
                return@forEach
            }
            when (provider) {
                AccountProvider.ANILIST -> pushAniList(token.accessToken, remoteId, merged)
                AccountProvider.MY_ANIME_LIST -> pushMal(token.accessToken, remoteId, merged)
            }
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("username:${provider.name}", snapshot.username)
            .putLong("last_sync:${provider.name}", System.currentTimeMillis())
            .remove("error:${provider.name}")
            .apply()
        notifyChanged()
    }

    private fun localEntries(context: Context): List<LocalEntry> {
        val completedHistory = VideoHistory.items
            .filter(WatchedVideo::completed)
            .mapNotNull { item ->
                val animeId = item.animeId ?: return@mapNotNull null
                val episode = item.episode ?: return@mapNotNull null
                Triple(animeId, episode, item)
            }
        val completedByAnime = completedHistory
            .groupBy(Triple<Int, Int, WatchedVideo>::first, Triple<Int, Int, WatchedVideo>::second)
            .mapValues { (_, episodes) -> episodes.maxOrNull() ?: 0 }
        val tracked = MediaListRepository.trackedItems(context)
        val trackedIds = tracked.mapTo(mutableSetOf(), Anime::id)
        val missingIds = completedByAnime.keys.filter { animeId -> animeId > 0 && animeId !in trackedIds }
        val fetched = missingIds.chunked(50).flatMap { ids -> AnimeApi.favorites(ids.toSet()) }
        val fetchedIds = fetched.mapTo(mutableSetOf(), Anime::id)
        val fallback = completedHistory
            .map(Triple<Int, Int, WatchedVideo>::third)
            .distinctBy(WatchedVideo::animeId)
            .filter { item -> item.animeId !in trackedIds && item.animeId !in fetchedIds }
            .mapNotNull(::historyAnime)

        return (tracked + fetched + fallback).distinctBy(Anime::id).map { anime ->
            val progress = completedByAnime[anime.id] ?: 0
            val status = remoteWatchStatus(progress, anime.episodes)
            LocalEntry(anime, progress, status)
        }
    }

    private fun validToken(context: Context, provider: AccountProvider): OAuthToken? {
        val token = SecureTokenStore.load(context, provider) ?: return null
        if (token.expiresAt == 0L || token.expiresAt > System.currentTimeMillis() + 60_000) {
            return token
        }
        if (provider != AccountProvider.MY_ANIME_LIST || token.refreshToken.isNullOrBlank()) {
            SecureTokenStore.remove(context, provider)
            return null
        }

        val refreshed = refreshMalToken(token.refreshToken)
        SecureTokenStore.save(context, provider, refreshed)
        return refreshed
    }

    private fun connectedProviders(context: Context): List<AccountProvider> {
        return AccountProvider.entries.filter { provider ->
            SecureTokenStore.load(context, provider) != null
        }
    }

    private fun loadPending(context: Context): Map<Int, Int> {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING, null)
            ?: return emptyMap()
        return try {
            val json = JSONObject(value)
            json.keys().asSequence().mapNotNull { key ->
                val animeId = key.toIntOrNull() ?: return@mapNotNull null
                animeId to json.optInt(key).takeIf { episode -> episode > 0 }
            }.mapNotNull { (animeId, episode) -> episode?.let { animeId to it } }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun savePending(context: Context, pending: Map<Int, Int>) {
        val json = JSONObject()
        pending.forEach { (animeId, episode) -> json.put(animeId.toString(), episode) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING, json.toString())
            .apply()
    }

    private fun saveError(context: Context, provider: AccountProvider, error: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (error == null) {
            editor.remove("error:${provider.name}")
        } else {
            editor.putString("error:${provider.name}", error.take(300))
        }
        editor.apply()
        notifyChanged()
    }

    private fun notifyChanged() {
        android.os.Handler(contextMainLooper).post {
            revision.intValue++
        }
    }

    private val contextMainLooper = android.os.Looper.getMainLooper()
}

internal fun remoteWatchStatus(progress: Int, episodes: Int?): RemoteWatchStatus {
    if (episodes != null && episodes > 0 && progress >= episodes) {
        return RemoteWatchStatus.COMPLETED
    }
    if (progress > 0) {
        return RemoteWatchStatus.WATCHING
    }
    return RemoteWatchStatus.PLANNING
}

internal fun oauthParameters(uri: Uri): Map<String, String> {
    val query = uri.fragment?.takeIf(String::isNotBlank) ?: uri.query.orEmpty()
    if (query.isBlank()) {
        return emptyMap()
    }
    val parsed = Uri.parse("https://kitsune.invalid/?$query")
    return parsed.queryParameterNames.associateWith { key -> parsed.getQueryParameter(key).orEmpty() }
}

private fun historyAnime(item: WatchedVideo): Anime? {
    val animeId = item.animeId ?: return null
    val title = item.animeTitle ?: item.title
    return Anime(
        id = animeId,
        malId = animeId.takeIf { id -> id < 0 }?.let { id -> -id },
        title = title,
        romajiTitle = title,
        englishTitle = null,
        description = "",
        cover = item.animeCoverUrl.orEmpty(),
        banner = null,
        episodes = null,
        score = null,
        year = null,
        season = null,
        format = null,
        status = null,
        genres = emptyList()
    )
}

private fun AccountProvider.listName(): String {
    return when (this) {
        AccountProvider.ANILIST -> "AniList"
        AccountProvider.MY_ANIME_LIST -> "MyAnimeList"
    }
}

private fun AccountProvider.remoteId(anime: Anime): Int? {
    return when (this) {
        AccountProvider.ANILIST -> anime.id.takeIf { id -> id > 0 }
        AccountProvider.MY_ANIME_LIST -> anime.malId ?: anime.id.takeIf { id -> id < 0 }?.let { -it }
    }
}

private fun clientId(provider: AccountProvider): String {
    return when (provider) {
        AccountProvider.ANILIST -> BuildConfig.ANILIST_CLIENT_ID
        AccountProvider.MY_ANIME_LIST -> BuildConfig.MAL_CLIENT_ID
    }
}

private fun aniListToken(parameters: Map<String, String>): OAuthToken {
    val accessToken = parameters["access_token"].orEmpty()
    require(accessToken.isNotBlank()) { parameters["error_description"] ?: "AniList não retornou um token." }
    val expiresIn = parameters["expires_in"]?.toLongOrNull() ?: 0
    return OAuthToken(
        accessToken = accessToken,
        refreshToken = null,
        expiresAt = expiresIn.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1_000 } ?: 0
    )
}

private fun malToken(context: Context, parameters: Map<String, String>): OAuthToken {
    val code = parameters["code"].orEmpty()
    require(code.isNotBlank()) { parameters["error"] ?: "MyAnimeList não retornou o código de acesso." }
    val preferences = context.getSharedPreferences("kitsune_account_sync", Context.MODE_PRIVATE)
    val verifier = preferences.getString("verifier:${AccountProvider.MY_ANIME_LIST.name}", null)
    require(!verifier.isNullOrBlank()) { "Verificador OAuth ausente." }
    preferences.edit().remove("verifier:${AccountProvider.MY_ANIME_LIST.name}").apply()
    val response = postForm(
        "https://myanimelist.net/v1/oauth2/token",
        mapOf(
            "client_id" to BuildConfig.MAL_CLIENT_ID,
            "code" to code,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to "kitsune://oauth/mal"
        )
    )
    return parseMalToken(response)
}

private fun refreshMalToken(refreshToken: String): OAuthToken {
    val response = postForm(
        "https://myanimelist.net/v1/oauth2/token",
        mapOf(
            "client_id" to BuildConfig.MAL_CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken
        )
    )
    return parseMalToken(response)
}

private fun parseMalToken(response: JSONObject): OAuthToken {
    val expiresIn = response.optLong("expires_in")
    return OAuthToken(
        accessToken = response.getString("access_token"),
        refreshToken = response.stringOrNull("refresh_token"),
        expiresAt = System.currentTimeMillis() + expiresIn * 1_000
    )
}

private fun pullAniList(token: String): RemoteSnapshot {
    val query = """
        query {
          Viewer { id name }
          MediaListCollection(userId: 0, type: ANIME) {
            lists {
              entries {
                status
                progress
                media { ${AnimeApi.FIELDS} }
              }
            }
          }
        }
    """.trimIndent()
    val viewerResponse = aniListGraphQl(token, "query { Viewer { id name } }")
    val viewer = viewerResponse.getJSONObject("Viewer")
    val listQuery = query.replace("userId: 0", "userId: ${viewer.getInt("id")}")
    val data = aniListGraphQl(token, listQuery)
    val lists = data.getJSONObject("MediaListCollection").optJSONArray("lists") ?: JSONArray()
    val entries = buildList {
        for (listIndex in 0 until lists.length()) {
            val items = lists.getJSONObject(listIndex).optJSONArray("entries") ?: continue
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    RemoteEntry(
                        anime = AnimeApi.parseAnime(item.getJSONObject("media")),
                        progress = item.optInt("progress"),
                        status = aniListStatus(item.optString("status"))
                    )
                )
            }
        }
    }
    return RemoteSnapshot(viewer.getString("name"), entries.distinctBy { entry -> entry.anime.id })
}

private fun pushAniList(token: String, mediaId: Int, entry: LocalEntry) {
    val status = when (entry.status) {
        RemoteWatchStatus.PLANNING -> "PLANNING"
        RemoteWatchStatus.WATCHING -> "CURRENT"
        RemoteWatchStatus.COMPLETED -> "COMPLETED"
    }
    aniListGraphQl(
        token,
        "mutation { SaveMediaListEntry(mediaId: $mediaId, status: $status, progress: ${entry.progress}) { id } }"
    )
}

private fun aniListStatus(value: String): RemoteWatchStatus {
    return when (value) {
        "COMPLETED" -> RemoteWatchStatus.COMPLETED
        "CURRENT", "REPEATING", "PAUSED", "DROPPED" -> RemoteWatchStatus.WATCHING
        else -> RemoteWatchStatus.PLANNING
    }
}

private fun aniListGraphQl(token: String, query: String): JSONObject {
    val connection = openConnection("https://graphql.anilist.co", "POST", token)
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true
    return useJsonConnection(connection) {
        connection.outputStream.use { output ->
            output.write(JSONObject().put("query", query).toString().toByteArray())
        }
    }.getJSONObject("data")
}

private fun pullMal(token: String): RemoteSnapshot {
    val profile = getJson("https://api.myanimelist.net/v2/users/@me", token)
    var url: String? = "https://api.myanimelist.net/v2/users/@me/animelist" + queryParameters(
        "fields" to "list_status,alternative_titles,num_episodes,mean,media_type,status,start_season,genres",
        "limit" to "1000",
        "nsfw" to "true"
    )
    val entries = mutableListOf<RemoteEntry>()
    while (url != null && entries.size < 10_000) {
        val page = getJson(url, token)
        val data = page.optJSONArray("data") ?: JSONArray()
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            val node = item.getJSONObject("node")
            val listStatus = item.getJSONObject("list_status")
            entries += RemoteEntry(
                anime = parseMalAnime(node),
                progress = listStatus.optInt("num_episodes_watched"),
                status = malStatus(listStatus.optString("status"))
            )
        }
        url = page.optJSONObject("paging")?.stringOrNull("next")
    }
    return RemoteSnapshot(profile.optString("name", "MyAnimeList"), entries.distinctBy { entry -> entry.anime.malId })
}

private fun pushMal(token: String, animeId: Int, entry: LocalEntry) {
    val status = when (entry.status) {
        RemoteWatchStatus.PLANNING -> "plan_to_watch"
        RemoteWatchStatus.WATCHING -> "watching"
        RemoteWatchStatus.COMPLETED -> "completed"
    }
    patchForm(
        "https://api.myanimelist.net/v2/anime/$animeId/my_list_status",
        token,
        mapOf("status" to status, "num_watched_episodes" to entry.progress.toString())
    )
}

private fun parseMalAnime(item: JSONObject): Anime {
    val malId = item.getInt("id")
    val alternativeTitles = item.optJSONObject("alternative_titles")
    val romaji = item.optString("title", "Sem título")
    val english = alternativeTitles?.stringOrNull("en")
    val picture = item.optJSONObject("main_picture")
    val startSeason = item.optJSONObject("start_season")
    val genres = item.optJSONArray("genres") ?: JSONArray()
    return Anime(
        id = -malId,
        malId = malId,
        title = english ?: romaji,
        romajiTitle = romaji,
        englishTitle = english,
        description = "",
        cover = picture?.optString("large").orEmpty().ifBlank { picture?.optString("medium").orEmpty() },
        banner = null,
        episodes = item.optInt("num_episodes").takeIf { count -> count > 0 },
        score = item.optDouble("mean").takeIf { score -> score > 0 }?.times(10)?.toInt(),
        year = startSeason?.optInt("year")?.takeIf { year -> year > 0 },
        season = startSeason?.stringOrNull("season")?.uppercase(),
        format = item.stringOrNull("media_type")?.uppercase(),
        status = when (item.optString("status")) {
            "currently_airing" -> "RELEASING"
            "finished_airing" -> "FINISHED"
            else -> "NOT_YET_RELEASED"
        },
        genres = List(genres.length()) { index -> genres.getJSONObject(index).optString("name") }
    )
}

private fun malStatus(value: String): RemoteWatchStatus {
    return when (value) {
        "completed" -> RemoteWatchStatus.COMPLETED
        "watching", "on_hold", "dropped" -> RemoteWatchStatus.WATCHING
        else -> RemoteWatchStatus.PLANNING
    }
}

private fun getJson(url: String, token: String): JSONObject {
    return useJsonConnection(openConnection(url, "GET", token))
}

private fun patchForm(url: String, token: String, parameters: Map<String, String>): JSONObject {
    val connection = openConnection(url, "PATCH", token)
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.doOutput = true
    return useJsonConnection(connection) {
        connection.outputStream.use { output -> output.write(formBody(parameters).toByteArray()) }
    }
}

private fun postForm(url: String, parameters: Map<String, String>): JSONObject {
    val connection = openConnection(url, "POST", null)
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.doOutput = true
    return useJsonConnection(connection) {
        connection.outputStream.use { output -> output.write(formBody(parameters).toByteArray()) }
    }
}

private fun openConnection(url: String, method: String, token: String?): HttpURLConnection {
    return (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "KitsuneAndroid/${BuildConfig.VERSION_NAME}")
        if (token != null) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }
}

private fun useJsonConnection(connection: HttpURLConnection, write: (() -> Unit)? = null): JSONObject {
    return try {
        write?.invoke()
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IOException(jsonError(response) ?: "HTTP $status")
        }
        JSONObject(response)
    } finally {
        connection.disconnect()
    }
}

private fun jsonError(response: String): String? {
    return try {
        val json = JSONObject(response)
        json.stringOrNull("message")
            ?: json.optJSONArray("errors")?.optJSONObject(0)?.stringOrNull("message")
            ?: json.stringOrNull("error")
    } catch (_: Exception) {
        null
    }
}

private fun formBody(parameters: Map<String, String>): String {
    return parameters.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }
}

private fun queryParameters(vararg parameters: Pair<String, String>): String {
    return "?" + formBody(parameters.toMap())
}

private fun urlEncode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private fun randomUrlSafe(size: Int): String {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private object SecureTokenStore {
    private const val PREFS = "kitsune_account_tokens"

    fun save(context: Context, provider: AccountProvider, token: OAuthToken) {
        val json = JSONObject()
            .put("access", token.accessToken)
            .put("refresh", token.refreshToken ?: JSONObject.NULL)
            .put("expires", token.expiresAt)
            .toString()
        SecureLocalStore.save(context, PREFS, provider.name, json)
    }

    fun load(context: Context, provider: AccountProvider): OAuthToken? {
        val payload = SecureLocalStore.load(context, PREFS, provider.name) ?: return null
        return try {
            val token = JSONObject(payload)
            OAuthToken(
                accessToken = token.getString("access"),
                refreshToken = token.stringOrNull("refresh"),
                expiresAt = token.optLong("expires")
            )
        } catch (_: Exception) {
            remove(context, provider)
            null
        }
    }

    fun remove(context: Context, provider: AccountProvider) {
        SecureLocalStore.remove(context, PREFS, provider.name)
    }
}
