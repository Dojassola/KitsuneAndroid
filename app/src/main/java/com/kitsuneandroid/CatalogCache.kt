package com.kitsuneandroid

import android.content.Context

private const val PREFERENCES = "kitsune"
private const val CATALOG_CACHE_PREFIX = "catalog_cache"
private const val MAX_CACHED_ANIME = 90

internal object CatalogCache {
    fun load(context: Context, section: CatalogSection = CatalogSection.SHOWS): List<Anime> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val payload = preferences.getString(key(context, section), null)
            ?: preferences.getString(legacyKey(context), null).takeIf { section == CatalogSection.SHOWS }
            ?: return emptyList()
        return decodeAnimeList(payload)
    }

    fun save(
        context: Context,
        anime: List<Anime>,
        section: CatalogSection = CatalogSection.SHOWS
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(key(context, section), encodeAnimeList(catalogItemsForCache(anime)))
            .apply()
    }

    private fun key(context: Context, section: CatalogSection): String {
        return "${legacyKey(context)}:${section.name}"
    }

    private fun legacyKey(context: Context): String {
        return "$CATALOG_CACHE_PREFIX:${loadMetadataLanguage(context).name}"
    }
}

internal fun catalogItemsForCache(anime: List<Anime>): List<Anime> {
    return anime.distinctBy(Anime::id).take(MAX_CACHED_ANIME)
}
