package com.kitsuneandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class RemoteProviderProtocol(val title: String) {
    STREMIO("Stremio"),
    KITSUNE("Kitsune")
}

internal data class RemoteProviderConfig(
    val manifestUrl: String,
    val protocol: RemoteProviderProtocol = RemoteProviderProtocol.STREMIO,
    val providerId: String? = null,
    val name: String? = null,
    val version: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val capabilities: List<String> = emptyList()
)

internal data class RemoteProviderDescriptor(
    val protocol: RemoteProviderProtocol,
    val id: String,
    val name: String,
    val version: String?,
    val capabilities: List<String>
)

private class RemoteProviderHttpException(
    val statusCode: Int
) : IOException("Provider remoto respondeu com HTTP $statusCode.")

private const val PREFERENCES = "kitsune"
private const val REMOTE_PROVIDERS = "remote_provider_configs"
private const val LEGACY_STREMIO_ADDONS = "stremio_addon_urls"
private const val NYAA_ENABLED = "provider_nyaa_enabled"
private const val BUILT_IN_PROVIDERS = "stream_providers_enabled"
private const val MAX_REMOTE_RESPONSE_BYTES = 2 * 1024 * 1024

internal fun loadRemoteProviderConfigs(context: Context): List<RemoteProviderConfig> {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val value = preferences.getString(REMOTE_PROVIDERS, null)
        ?: preferences.getString(LEGACY_STREMIO_ADDONS, "[]").orEmpty()

    return try {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                parseRemoteProviderConfig(array.opt(index), index)?.let(::add)
            }
        }.distinctBy(RemoteProviderConfig::manifestUrl)
            .sortedBy(RemoteProviderConfig::priority)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseRemoteProviderConfig(value: Any?, priority: Int): RemoteProviderConfig? {
    if (value is String) {
        return RemoteProviderConfig(manifestUrl = value, priority = priority)
    }
    if (value !is JSONObject) {
        return null
    }

    val manifestUrl = value.optString("manifestUrl")
    if (manifestUrl.isBlank()) {
        return null
    }
    val protocol = runCatching {
        RemoteProviderProtocol.valueOf(value.optString("protocol"))
    }.getOrDefault(RemoteProviderProtocol.STREMIO)
    return RemoteProviderConfig(
        manifestUrl = manifestUrl,
        protocol = protocol,
        providerId = value.stringOrNull("providerId"),
        name = value.stringOrNull("name"),
        version = value.stringOrNull("version"),
        enabled = value.optBoolean("enabled", true),
        priority = value.optInt("priority", priority),
        capabilities = value.stringList("capabilities")
            .ifEmpty { value.stringList("resources") }
    )
}

internal fun saveRemoteProviderConfigs(
    context: Context,
    configs: List<RemoteProviderConfig>
) {
    val array = JSONArray()
    configs.distinctBy(RemoteProviderConfig::manifestUrl)
        .forEachIndexed { index, config ->
            array.put(
                JSONObject()
                    .put("manifestUrl", config.manifestUrl)
                    .put("protocol", config.protocol.name)
                    .put("providerId", config.providerId ?: JSONObject.NULL)
                    .put("name", config.name ?: JSONObject.NULL)
                    .put("version", config.version ?: JSONObject.NULL)
                    .put("enabled", config.enabled)
                    .put("capabilities", JSONArray(config.capabilities))
                    .put("priority", index)
            )
        }

    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(REMOTE_PROVIDERS, array.toString())
        .remove(LEGACY_STREMIO_ADDONS)
        .apply()
}

internal fun loadBuiltInStreamProviders(context: Context): Set<BuiltInStreamProvider> {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val saved = preferences.getStringSet(BUILT_IN_PROVIDERS, null)
    if (saved == null) {
        return buildSet {
            if (preferences.getBoolean(NYAA_ENABLED, true)) {
                add(BuiltInStreamProvider.NYAA)
            }
            add(BuiltInStreamProvider.NEKOBT)
        }
    }

    val enabled = saved.mapNotNullTo(mutableSetOf()) { name ->
        BuiltInStreamProvider.entries.firstOrNull { provider -> provider.name == name }
    }
    if ("TOKYO_TOSHOKAN" in saved) {
        enabled += BuiltInStreamProvider.NEKOBT
    }
    return enabled
}

internal fun saveBuiltInStreamProviders(
    context: Context,
    providers: Set<BuiltInStreamProvider>
) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(BUILT_IN_PROVIDERS, providers.map(BuiltInStreamProvider::name).toSet())
        .putBoolean(NYAA_ENABLED, BuiltInStreamProvider.NYAA in providers)
        .apply()
}

internal fun moveRemoteProvider(
    configs: List<RemoteProviderConfig>,
    manifestUrl: String,
    direction: Int
): List<RemoteProviderConfig> {
    val ordered = configs.sortedBy(RemoteProviderConfig::priority).toMutableList()
    val currentIndex = ordered.indexOfFirst { config -> config.manifestUrl == manifestUrl }
    if (currentIndex < 0) {
        return configs
    }

    val targetIndex = (currentIndex + direction).coerceIn(0, ordered.lastIndex)
    if (targetIndex == currentIndex) {
        return ordered
    }

    val moved = ordered.removeAt(currentIndex)
    ordered.add(targetIndex, moved)
    return ordered.mapIndexed { index, config -> config.copy(priority = index) }
}

internal fun updateRemoteProvider(
    configs: List<RemoteProviderConfig>,
    manifestUrl: String,
    update: (RemoteProviderConfig) -> RemoteProviderConfig
): List<RemoteProviderConfig> {
    return configs.map { config ->
        if (config.manifestUrl == manifestUrl) {
            update(config)
        } else {
            config
        }
    }
}

internal fun inspectRemoteProvider(manifestUrl: String): RemoteProviderDescriptor {
    val payload = fetchRemoteJson(manifestUrl)
    if (payload.optString("schema") == KITSUNE_ADDON_SCHEMA) {
        return parseKitsuneManifest(payload).descriptor()
    }

    val manifest = parseStremioManifest(payload)
    require(manifest.resources.isNotEmpty()) {
        "O manifesto não oferece recursos compatíveis."
    }
    return RemoteProviderDescriptor(
        protocol = RemoteProviderProtocol.STREMIO,
        id = manifest.id,
        name = manifest.name,
        version = payload.stringOrNull("version"),
        capabilities = manifest.resources
    )
}

internal fun normalizeRemoteProviderUrl(value: String): String {
    val source = URL(value.trim())
    require(source.protocol == "https") {
        "O provider precisa usar HTTPS."
    }
    require(source.userInfo == null && source.query == null && source.ref == null) {
        "A URL do provider contém dados não suportados."
    }
    require(isPublicHost(source.host)) {
        "Endereços locais ou privados não são permitidos."
    }

    val path = source.path.trimEnd('/').ifBlank { "" }
    val manifestPath = if (path.endsWith(".json")) path else "$path/manifest.json"
    return URL(source.protocol, source.host, source.port, manifestPath).toString()
}

internal fun isSafeRemoteUrl(value: String): Boolean {
    return try {
        val url = URL(value)
        url.protocol == "https" && url.userInfo == null && isPublicHost(url.host)
    } catch (_: Exception) {
        false
    }
}

private fun isPublicHost(host: String): Boolean {
    val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
    if (normalized == "localhost" || normalized == "::1" || normalized.startsWith("127.")) {
        return false
    }
    if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) {
        return false
    }

    val secondIpv4Part = normalized
        .takeIf { address -> address.startsWith("172.") }
        ?.split('.')
        ?.getOrNull(1)
        ?.toIntOrNull()
    return secondIpv4Part == null || secondIpv4Part !in 16..31
}

internal fun fetchRemoteJson(value: String): JSONObject {
    val connection = URL(value).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.3")

    return try {
        if (connection.responseCode !in 200..299) {
            throw RemoteProviderHttpException(connection.responseCode)
        }
        if (!isSafeRemoteUrl(connection.url.toString())) {
            throw IOException("O provider redirecionou para um endereço não permitido.")
        }
        if (connection.contentLengthLong > MAX_REMOTE_RESPONSE_BYTES) {
            throw IOException("Resposta do provider grande demais.")
        }

        val bytes = connection.inputStream.use(::readRemoteProviderResponse)
        JSONObject(bytes.toString(StandardCharsets.UTF_8))
    } finally {
        connection.disconnect()
    }
}

internal fun fetchOptionalRemoteJson(value: String): JSONObject? {
    return try {
        fetchRemoteJson(value)
    } catch (failure: RemoteProviderHttpException) {
        if (failure.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            null
        } else {
            throw failure
        }
    }
}

private fun readRemoteProviderResponse(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) {
            break
        }
        output.write(buffer, 0, read)
        if (output.size() > MAX_REMOTE_RESPONSE_BYTES) {
            throw IOException("Resposta do provider grande demais.")
        }
    }
    return output.toByteArray()
}

internal fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) {
                add(value)
            }
        }
    }
}

internal fun sha1(value: String): String {
    return MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
