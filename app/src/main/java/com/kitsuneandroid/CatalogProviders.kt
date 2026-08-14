package com.kitsuneandroid

import android.content.Context

internal enum class CatalogProvider(val label: String) {
    ANILIST("AniList"),
    MY_ANIME_LIST("MyAnimeList (Jikan)"),
    KITSU("Kitsu")
}

internal enum class CatalogSection {
    SHOWS,
    MOVIES;

    fun acceptsType(type: String?): Boolean {
        val movie = type.equals("movie", ignoreCase = true)
        return if (this == MOVIES) movie else !movie
    }

    fun accepts(item: Anime): Boolean {
        return acceptsType(item.remoteMediaType ?: item.format)
    }
}

private const val CATALOG_PROVIDER_PREFERENCES = "kitsune"
private const val ENABLED_PROVIDERS = "catalog_providers_enabled"

internal fun loadCatalogProviders(context: Context): Set<CatalogProvider> {
    val saved = context
        .getSharedPreferences(CATALOG_PROVIDER_PREFERENCES, Context.MODE_PRIVATE)
        .getStringSet(ENABLED_PROVIDERS, null)
        ?: return CatalogProvider.entries.toSet()

    return saved.mapNotNullTo(mutableSetOf()) { name ->
        CatalogProvider.entries.firstOrNull { provider -> provider.name == name }
    }
}

internal fun saveCatalogProviders(context: Context, providers: Set<CatalogProvider>) {
    context.getSharedPreferences(CATALOG_PROVIDER_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(ENABLED_PROVIDERS, providers.mapTo(mutableSetOf(), CatalogProvider::name))
        .apply()
}

internal fun mergeCatalogs(catalogs: List<List<Anime>>): List<Anime> {
    val seen = mutableSetOf<String>()

    return catalogs.flatten().filter { anime ->
        val key = anime.malId?.let { malId -> "mal:$malId" }
            ?: "title:${anime.romajiTitle.trim().lowercase()}"
        seen.add(key)
    }
}
