package com.kitsuneandroid

import android.content.Context

internal enum class CatalogProvider(val label: String) {
    ANILIST("AniList"),
    JIKAN("Jikan"),
    KITSU("Kitsu")
}

internal enum class CatalogSection {
    ANIME,
    SERIES,
    MOVIES;

    fun acceptsType(type: String?): Boolean {
        val normalized = type.orEmpty().lowercase()
        return when (this) {
            ANIME -> "anime" in normalized
            SERIES -> normalized in setOf("series", "show", "tv")
            MOVIES -> "movie" in normalized
        }
    }

    fun accepts(item: Anime): Boolean {
        val remoteType = item.remoteMediaType
        if (remoteType != null) {
            return acceptsType(remoteType)
        }

        return when (this) {
            ANIME -> !item.format.equals("MOVIE", ignoreCase = true)
            SERIES -> false
            MOVIES -> item.format.equals("MOVIE", ignoreCase = true)
        }
    }

    fun contentKind(): String = when (this) {
        ANIME -> "anime-series"
        SERIES -> "series"
        MOVIES -> "movie"
    }
}

internal data class CatalogPage(
    val items: List<Anime>,
    val hasNextPage: Boolean
)

private const val CATALOG_PROVIDER_PREFERENCES = "kitsune"
private const val ENABLED_PROVIDERS = "catalog_providers_enabled"

internal fun loadCatalogProviders(context: Context): Set<CatalogProvider> {
    val saved = context
        .getSharedPreferences(CATALOG_PROVIDER_PREFERENCES, Context.MODE_PRIVATE)
        .getStringSet(ENABLED_PROVIDERS, null)
        ?: return CatalogProvider.entries.toSet()

    return saved.mapNotNullTo(mutableSetOf()) { name ->
        val migratedName = if (name == "MY_ANIME_LIST") "JIKAN" else name
        CatalogProvider.entries.firstOrNull { provider -> provider.name == migratedName }
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

internal fun mergeCatalogPages(pages: List<CatalogPage>): CatalogPage {
    return CatalogPage(
        items = mergeCatalogs(pages.map(CatalogPage::items)),
        hasNextPage = pages.any(CatalogPage::hasNextPage)
    )
}
