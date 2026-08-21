@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kitsuneandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

internal data class CatalogFilters(
    val year: Int? = null,
    val minimumScore: Int? = null,
    val genre: String? = null,
    val season: String? = null,
    val format: String? = null,
    val status: String? = null
) {
    val activeCount: Int
        get() = listOfNotNull(year, minimumScore, genre, season, format, status).size

    val isEmpty: Boolean
        get() = activeCount == 0

    fun matches(anime: Anime): Boolean {
        if (year != null && anime.year != year) {
            return false
        }
        if (minimumScore != null && (anime.score ?: 0) < minimumScore) {
            return false
        }
        if (genre != null && anime.genres.none { value -> value.equals(genre, ignoreCase = true) }) {
            return false
        }
        if (season != null && animeSeasonResource(anime.season) != animeSeasonResource(season)) {
            return false
        }
        if (format != null && animeFormatResource(anime.format) != animeFormatResource(format)) {
            return false
        }
        if (status != null && animeStatusResource(anime.status) != animeStatusResource(status)) {
            return false
        }

        return true
    }
}

@Composable
internal fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    items: List<Anime>,
    filters: CatalogFilters,
    onFiltersChanged: (CatalogFilters) -> Unit
) {
    var filtersOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_catalog)) },
                shape = RoundedCornerShape(28.dp),
                trailingIcon = {
                    TextButton(onClick = onSearch) {
                        Text(stringResource(R.string.search))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() })
            )
            FilterChip(
                selected = !filters.isEmpty,
                onClick = { filtersOpen = true },
                label = {
                    Text(
                        if (filters.isEmpty) {
                            stringResource(R.string.catalog_filters)
                        } else {
                            stringResource(R.string.catalog_filters_active, filters.activeCount)
                        }
                    )
                }
            )
        }
    }

    if (filtersOpen) {
        CatalogFiltersSheet(
            items = items,
            filters = filters,
            onDismiss = { filtersOpen = false },
            onApply = { selected ->
                onFiltersChanged(selected)
                filtersOpen = false
            }
        )
    }
}

@Composable
internal fun CatalogSectionPicker(
    selected: CatalogSection,
    onSelected: (CatalogSection) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == CatalogSection.ANIME,
            onClick = { onSelected(CatalogSection.ANIME) },
            label = { Text(stringResource(R.string.anime)) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selected == CatalogSection.SERIES,
            onClick = { onSelected(CatalogSection.SERIES) },
            label = { Text(stringResource(R.string.series)) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selected == CatalogSection.MOVIES,
            onClick = { onSelected(CatalogSection.MOVIES) },
            label = { Text(stringResource(R.string.movies)) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CatalogFiltersSheet(
    items: List<Anime>,
    filters: CatalogFilters,
    onDismiss: () -> Unit,
    onApply: (CatalogFilters) -> Unit
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    val years = remember(items) {
        items.mapNotNull(Anime::year).distinct().sortedDescending()
    }
    val genres = remember(items) {
        items.flatMap(Anime::genres).distinct().sorted()
    }
    val seasonOptions = listOf(
        "WINTER" to stringResource(R.string.anime_season_winter),
        "SPRING" to stringResource(R.string.anime_season_spring),
        "SUMMER" to stringResource(R.string.anime_season_summer),
        "FALL" to stringResource(R.string.anime_season_fall)
    )
    val formatOptions = listOf(
        "TV" to stringResource(R.string.anime_format_tv),
        "MOVIE" to stringResource(R.string.anime_format_movie),
        "ONA" to stringResource(R.string.anime_format_ona),
        "OVA" to stringResource(R.string.anime_format_ova),
        "SPECIAL" to stringResource(R.string.anime_format_special)
    )
    val statusOptions = listOf(
        "RELEASING" to stringResource(R.string.anime_status_releasing),
        "NOT_YET_RELEASED" to stringResource(R.string.anime_status_upcoming),
        "FINISHED" to stringResource(R.string.anime_status_finished),
        "HIATUS" to stringResource(R.string.anime_status_hiatus)
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.catalog_filters),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { draft = CatalogFilters() }) {
                    Text(stringResource(R.string.clear_filters))
                }
            }
            Text(
                text = stringResource(R.string.catalog_filters_local_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            FilterChoiceGroup(
                title = stringResource(R.string.filter_year),
                options = years.map { year -> year to year.toString() },
                selected = draft.year,
                onSelected = { value -> draft = draft.copy(year = value) }
            )
            FilterChoiceGroup(
                title = stringResource(R.string.filter_rating),
                options = listOf(70 to "70%+", 80 to "80%+", 90 to "90%+"),
                selected = draft.minimumScore,
                onSelected = { value -> draft = draft.copy(minimumScore = value) }
            )
            FilterChoiceGroup(
                title = stringResource(R.string.filter_genres),
                options = genres.map { genre -> genre to genre },
                selected = draft.genre,
                onSelected = { value -> draft = draft.copy(genre = value) }
            )
            FilterChoiceGroup(
                title = stringResource(R.string.filter_season),
                options = seasonOptions,
                selected = draft.season,
                onSelected = { value -> draft = draft.copy(season = value) }
            )
            FilterChoiceGroup(
                title = stringResource(R.string.filter_format),
                options = formatOptions,
                selected = draft.format,
                onSelected = { value -> draft = draft.copy(format = value) }
            )
            FilterChoiceGroup(
                title = stringResource(R.string.filter_status),
                options = statusOptions,
                selected = draft.status,
                onSelected = { value -> draft = draft.copy(status = value) }
            )
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@Composable
private fun <T> FilterChoiceGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelected: (T?) -> Unit
) {
    if (options.isEmpty()) {
        return
    }

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = {
                    onSelected(value.takeUnless { selected == value })
                },
                label = { Text(label) }
            )
        }
    }
}

@Composable
internal fun Catalog(
    items: List<Anime>,
    state: LazyGridState,
    loading: Boolean,
    error: String?,
    emptyMessage: String,
    offlineAnimeIds: Set<Int>,
    onRetry: () -> Unit,
    onSelect: (Anime) -> Unit,
    searchQuery: String? = null,
    canLoadMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {}
) {
    val shouldLoadMore = remember(state, items.size, canLoadMore, loadingMore) {
        derivedStateOf {
            val lastVisibleIndex = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            shouldLoadNextCatalogPage(
                lastVisibleIndex = lastVisibleIndex,
                itemCount = items.size,
                canLoadMore = canLoadMore,
                loadingMore = loadingMore
            )
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            onLoadMore()
        }
    }

    when {
        loading && items.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        error != null && items.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.try_again))
                }
            }
        }
        items.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(emptyMessage)
            }
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val normalizedQuery = searchQuery?.trim().orEmpty()
                if (normalizedQuery.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.catalog_search_results,
                                items.size,
                                items.size,
                                normalizedQuery
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(items, key = { anime -> anime.id }) { anime ->
                    Box(Modifier.animateItem()) {
                        AnimeCard(
                            anime = anime,
                            availableOffline = anime.id in offlineAnimeIds,
                            onSelect = onSelect
                        )
                    }
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

internal fun shouldLoadNextCatalogPage(
    lastVisibleIndex: Int,
    itemCount: Int,
    canLoadMore: Boolean,
    loadingMore: Boolean
): Boolean {
    return canLoadMore &&
        !loadingMore &&
        itemCount > 0 &&
        lastVisibleIndex >= itemCount - 7
}

@Composable
private fun AnimeCard(
    anime: Anime,
    availableOffline: Boolean,
    onSelect: (Anime) -> Unit
) {
    val context = LocalContext.current
    val format = localizedAnimeFormat(anime.format)
    val status = localizedAnimeStatus(anime.status)
    val episodeCount = anime.episodes?.let { count ->
        pluralStringResource(R.plurals.episode_count, count, count)
    }
    val mainMetadata = listOfNotNull(
        anime.year?.toString(),
        format,
        episodeCount
    ).joinToString("  •  ")
    val secondaryMetadata = listOfNotNull(
        status,
        anime.score?.let { score -> "★ $score%" }
    ).joinToString("  •  ")
    val coverRequest = remember(anime.cover) {
        ImageRequest.Builder(context)
            .data(anime.cover)
            .crossfade(180)
            .build()
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(388.dp).clickable { onSelect(anime) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = stringResource(R.string.anime_cover, anime.title),
            modifier = Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(10.dp)) {
            Text(
                text = anime.title,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (availableOffline) {
                Text(
                    text = stringResource(R.string.available_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = mainMetadata,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = secondaryMetadata,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun localizedAnimeStatus(status: String?): String? {
    val resource = animeStatusResource(status)
    if (resource != null) {
        return stringResource(resource)
    }

    return metadataFallbackLabel(status)
}

@Composable
internal fun localizedAnimeFormat(format: String?): String? {
    val resource = animeFormatResource(format)
    if (resource != null) {
        return stringResource(resource)
    }

    return metadataFallbackLabel(format)
}

@Composable
internal fun localizedAnimeSeason(season: String?): String? {
    val resource = animeSeasonResource(season)
    if (resource != null) {
        return stringResource(resource)
    }

    return metadataFallbackLabel(season)
}
