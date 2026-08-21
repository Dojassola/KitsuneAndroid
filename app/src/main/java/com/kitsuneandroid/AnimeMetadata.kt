package com.kitsuneandroid

import java.text.Normalizer

internal fun Anime.catalogIdentityKeys(): Set<String> {
    return buildList {
        add(title)
        add(romajiTitle)
        englishTitle?.let(::add)
        addAll(aliases)
    }
        .map(::normalizeCatalogText)
        .filter(String::isNotBlank)
        .toSet()
}

internal fun Anime.matchesCatalogQuery(query: String): Boolean {
    val terms = normalizeCatalogText(query)
        .split(' ')
        .filter(String::isNotBlank)

    if (terms.isEmpty()) {
        return true
    }

    return catalogIdentityKeys().any { title ->
        terms.all(title::contains)
    }
}

internal fun mergeAnimeMetadata(first: Anime, second: Anime): Anime {
    val (primary, supplement) = preferredAnime(first, second)
    val title = primary.title.ifBlank { supplement.title }
    val romajiTitle = primary.romajiTitle.ifBlank { supplement.romajiTitle }
    val englishTitle = primary.englishTitle ?: supplement.englishTitle
    val mainTitleKeys = listOfNotNull(title, romajiTitle, englishTitle)
        .map(::normalizeCatalogText)
        .toSet()
    val aliases = buildList {
        addAll(primary.aliases)
        addAll(supplement.aliases)
        add(supplement.title)
        add(supplement.romajiTitle)
        supplement.englishTitle?.let(::add)
    }
        .filter { alias ->
            val normalized = normalizeCatalogText(alias)
            normalized.isNotBlank() && normalized !in mainTitleKeys
        }
        .distinctBy(::normalizeCatalogText)

    return primary.copy(
        malId = primary.malId ?: supplement.malId,
        title = title,
        romajiTitle = romajiTitle,
        englishTitle = englishTitle,
        description = primary.description.ifBlank { supplement.description },
        cover = primary.cover.ifBlank { supplement.cover },
        banner = primary.banner ?: supplement.banner,
        episodes = primary.episodes ?: supplement.episodes,
        score = primary.score ?: supplement.score,
        year = primary.year ?: supplement.year,
        season = primary.season ?: supplement.season,
        format = primary.format ?: supplement.format,
        status = primary.status ?: supplement.status,
        genres = (primary.genres + supplement.genres)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeCatalogText),
        aliases = aliases,
        nextAiringEpisode = primary.nextAiringEpisode ?: supplement.nextAiringEpisode,
        nextAiringAt = primary.nextAiringAt ?: supplement.nextAiringAt,
        seasonNumber = primary.seasonNumber ?: supplement.seasonNumber
    )
}

internal fun normalizeCatalogText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

internal fun metadataFallbackLabel(value: String?): String? {
    val normalized = value
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null

    return normalized
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::titlecase)
}

internal fun animeStatusResource(status: String?): Int? {
    return when (status?.uppercase()) {
        "RELEASING", "AIRING", "CURRENT" -> R.string.anime_status_releasing
        "FINISHED", "COMPLETED" -> R.string.anime_status_finished
        "NOT_YET_RELEASED", "UPCOMING", "TBA", "UNRELEASED" -> R.string.anime_status_upcoming
        "CANCELLED", "CANCELED" -> R.string.anime_status_cancelled
        "HIATUS" -> R.string.anime_status_hiatus
        else -> null
    }
}

internal fun animeFormatResource(format: String?): Int? {
    return when (format?.uppercase()) {
        "TV", "SERIES", "SHOW" -> R.string.anime_format_tv
        "TV_SHORT" -> R.string.anime_format_tv_short
        "MOVIE" -> R.string.anime_format_movie
        "SPECIAL" -> R.string.anime_format_special
        "MUSIC" -> R.string.anime_format_music
        "OVA" -> R.string.anime_format_ova
        "ONA" -> R.string.anime_format_ona
        else -> null
    }
}

internal fun animeSeasonResource(season: String?): Int? {
    return when (season?.uppercase()) {
        "WINTER" -> R.string.anime_season_winter
        "SPRING" -> R.string.anime_season_spring
        "SUMMER" -> R.string.anime_season_summer
        "FALL", "AUTUMN" -> R.string.anime_season_fall
        else -> null
    }
}

private fun preferredAnime(first: Anime, second: Anime): Pair<Anime, Anime> {
    if (first.remoteManifestUrl != null || second.remoteManifestUrl != null) {
        return first to second
    }

    return if (second.catalogIdentityQuality() > first.catalogIdentityQuality()) {
        second to first
    } else {
        first to second
    }
}

private fun Anime.catalogIdentityQuality(): Int {
    return when {
        id > 0 -> 3
        malId != null -> 2
        else -> 1
    }
}
