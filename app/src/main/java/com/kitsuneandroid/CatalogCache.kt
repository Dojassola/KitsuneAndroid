package com.kitsuneandroid

import android.content.Context

private const val PREFERENCES = "kitsune"
private const val CATALOG_CACHE_PREFIX = "catalog_cache"
private const val MAX_CACHED_ANIME = 90

internal object CatalogCache {
    fun load(context: Context): List<Anime> {
        val payload = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key(context), null)
            ?: return emptyList()
        return decodeAnimeList(payload)
    }

    fun save(context: Context, anime: List<Anime>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(key(context), encodeAnimeList(catalogItemsForCache(anime)))
            .apply()
    }

    private fun key(context: Context): String {
        return "$CATALOG_CACHE_PREFIX:${loadMetadataLanguage(context).name}"
    }
}

internal fun catalogItemsForCache(anime: List<Anime>): List<Anime> {
    return anime.distinctBy(Anime::id).take(MAX_CACHED_ANIME)
}
