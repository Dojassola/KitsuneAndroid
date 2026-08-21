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
    val merged = mutableListOf<Anime>()
    val positionsByMalId = mutableMapOf<Int, Int>()
    val positionsByTitle = mutableMapOf<String, Int>()

    fun register(position: Int, anime: Anime) {
        anime.malId?.let { malId ->
            positionsByMalId[malId] = position
        }
        anime.catalogIdentityKeys().forEach { key ->
            positionsByTitle.putIfAbsent(key, position)
        }
    }

    catalogs.forEach { catalog ->
        catalog.forEach { anime ->
            val malPosition = anime.malId?.let(positionsByMalId::get)
            val titlePosition = anime.catalogIdentityKeys()
                .firstNotNullOfOrNull(positionsByTitle::get)
                ?.takeIf { position ->
                    val existing = merged[position]
                    val differentRemoteSource = existing.remoteManifestUrl != anime.remoteManifestUrl &&
                        (existing.remoteManifestUrl != null || anime.remoteManifestUrl != null)
                    if (differentRemoteSource) {
                        return@takeIf false
                    }

                    val existingMalId = existing.malId
                    val malIdsAreCompatible = existingMalId == null ||
                        anime.malId == null ||
                        existingMalId == anime.malId
                    malIdsAreCompatible
                }
            val position = malPosition ?: titlePosition

            if (position == null) {
                merged += anime
                register(merged.lastIndex, anime)
            } else {
                merged[position] = mergeAnimeMetadata(merged[position], anime)
                register(position, merged[position])
            }
        }
    }

    return merged
}

internal fun mergeCatalogPages(pages: List<CatalogPage>): CatalogPage {
    return CatalogPage(
        items = mergeCatalogs(pages.map(CatalogPage::items)),
        hasNextPage = pages.any(CatalogPage::hasNextPage)
    )
}
