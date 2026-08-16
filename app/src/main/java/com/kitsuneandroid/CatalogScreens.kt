package com.kitsuneandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
internal fun SearchBox(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(R.string.search_catalog)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSearch) { Text(stringResource(R.string.search)) }
    }
}

@Composable
internal fun CatalogSectionPicker(
    selected: CatalogSection,
    onSelected: (CatalogSection) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == CatalogSection.ANIME,
            onClick = { onSelected(CatalogSection.ANIME) },
            label = { Text(stringResource(R.string.anime)) }
        )
        FilterChip(
            selected = selected == CatalogSection.SERIES,
            onClick = { onSelected(CatalogSection.SERIES) },
            label = { Text(stringResource(R.string.series)) }
        )
        FilterChip(
            selected = selected == CatalogSection.MOVIES,
            onClick = { onSelected(CatalogSection.MOVIES) },
            label = { Text(stringResource(R.string.movies)) }
        )
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
    val coverRequest = remember(anime.cover) {
        ImageRequest.Builder(context)
            .data(anime.cover)
            .crossfade(180)
            .build()
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(320.dp).clickable { onSelect(anime) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = stringResource(R.string.anime_cover, anime.title),
            modifier = Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceVariant),
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
                listOfNotNull(anime.year?.toString(), anime.score?.let { "★ $it%" }).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
