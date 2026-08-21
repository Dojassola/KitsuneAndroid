package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val MAX_MEDIA_LIST_IMPORT_BYTES = 5 * 1024 * 1024

internal data class MediaList(
    val id: String,
    val name: String,
    val items: List<Anime>
)

internal object MediaListRepository {
    private const val PREFERENCES = "kitsune"
    private const val STORAGE_KEY = "media_lists"
    private const val FORMAT = "kitsune-media-lists"

    fun lists(context: Context): List<MediaList> {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(STORAGE_KEY, null)
            ?: return emptyList()
        return decodeMediaLists(value)
    }

    fun create(context: Context, name: String): MediaList {
        val normalizedName = requireListName(name)
        val created = MediaList(UUID.randomUUID().toString(), normalizedName, emptyList())
        save(context, lists(context) + created)
        return created
    }

    fun rename(context: Context, listId: String, name: String) {
        val normalizedName = requireListName(name)
        save(context, lists(context).map { list ->
            if (list.id == listId) list.copy(name = normalizedName) else list
        })
    }

    fun delete(context: Context, listId: String) {
        save(context, lists(context).filterNot { list -> list.id == listId })
    }

    fun setItem(context: Context, listId: String, anime: Anime, included: Boolean) {
        save(context, lists(context).map { list ->
            if (list.id != listId) {
                return@map list
            }

            val remaining = list.items.filterNot { item -> item.id == anime.id }
            list.copy(items = if (included) listOf(anime) + remaining else remaining)
        })
    }

    fun trackedItems(context: Context): List<Anime> {
        return (FavoriteRepository.items(context) + lists(context).flatMap(MediaList::items))
            .distinctBy(Anime::id)
    }

    fun replaceNamed(context: Context, name: String, items: List<Anime>) {
        val normalizedName = requireListName(name)
        val current = lists(context)
        val existing = current.firstOrNull { list ->
            list.name.equals(normalizedName, ignoreCase = true)
        }
        val imported = MediaList(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = normalizedName,
            items = items.distinctBy { anime -> anime.malId ?: anime.id }
        )
        save(context, current.filterNot { list -> list.id == existing?.id } + imported)
    }

    fun export(context: Context, destination: Uri) {
        val output = context.contentResolver.openOutputStream(destination)
            ?: throw IOException("Não foi possível criar o arquivo de listas.")
        output.bufferedWriter().use { writer ->
            writer.write(encodeMediaLists(lists(context)))
        }
    }

    fun import(context: Context, source: Uri): Int {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Não foi possível abrir o arquivo de listas.")
        val payload = input.use { stream ->
            readMediaListBytes(stream).toString(Charsets.UTF_8)
        }
        val imported = decodeMediaLists(payload)
        require(imported.isNotEmpty()) { "Nenhuma lista válida foi encontrada." }

        val merged = lists(context).toMutableList()
        imported.forEach { incoming ->
            val index = merged.indexOfFirst { current ->
                current.id == incoming.id || current.name.equals(incoming.name, ignoreCase = true)
            }
            if (index < 0) {
                merged += incoming
            } else {
                val current = merged[index]
                merged[index] = current.copy(
                    items = (incoming.items + current.items).distinctBy(Anime::id)
                )
            }
        }
        save(context, merged)
        return imported.sumOf { list -> list.items.size }
    }

    private fun save(context: Context, lists: List<MediaList>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(STORAGE_KEY, encodeMediaLists(lists))
            .apply()
    }

    private fun requireListName(name: String): String {
        val normalized = name.trim().take(60)
        require(normalized.isNotEmpty()) { "Informe um nome para a lista." }
        return normalized
    }
}

private fun readMediaListBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) {
            return output.toByteArray()
        }
        output.write(buffer, 0, read)
        require(output.size() <= MAX_MEDIA_LIST_IMPORT_BYTES) { "O arquivo de listas é grande demais." }
    }
}

internal fun encodeMediaLists(lists: List<MediaList>): String {
    val encodedLists = JSONArray()
    lists.take(100).forEach { list ->
        encodedLists.put(
            JSONObject()
                .put("id", list.id)
                .put("name", list.name)
                .put("items", JSONArray(encodeAnimeList(list.items.take(5_000))))
        )
    }
    return JSONObject()
        .put("format", "kitsune-media-lists")
        .put("version", 1)
        .put("lists", encodedLists)
        .toString()
}

internal fun decodeMediaLists(value: String): List<MediaList> {
    return try {
        val root = JSONObject(value)
        if (root.optString("format") != "kitsune-media-lists") {
            return emptyList()
        }
        val lists = root.optJSONArray("lists") ?: return emptyList()
        buildList {
            for (index in 0 until minOf(lists.length(), 100)) {
                val item = lists.optJSONObject(index) ?: continue
                val name = item.optString("name").trim().take(60)
                if (name.isEmpty()) {
                    continue
                }
                val id = item.optString("id").takeIf(String::isNotBlank)
                    ?: UUID.randomUUID().toString()
                val anime = decodeAnimeList(item.optJSONArray("items")?.toString() ?: "[]")
                    .distinctBy(Anime::id)
                    .take(5_000)
                add(MediaList(id, name, anime))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
