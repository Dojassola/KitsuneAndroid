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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collect

private data class CatalogScrollPosition(
    val lastIndex: Int,
    val scrolling: Boolean
)

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
    loadingPage: Boolean = false,
    onNextPage: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onSelect: (Anime) -> Unit
) {
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
            LaunchedEffect(
                state,
                items.size,
                loading,
                loadingPage,
                error
            ) {
                var requestSent = false

                snapshotFlow {
                    CatalogScrollPosition(
                        lastIndex = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
                        scrolling = state.isScrollInProgress
                    )
                }.collect { position ->
                    if (
                        !requestSent &&
                        position.scrolling &&
                        !loading &&
                        !loadingPage &&
                        error == null &&
                        position.lastIndex >= items.lastIndex - 5 &&
                        onNextPage != null
                    ) {
                        requestSent = true
                        onNextPage()
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { anime -> anime.id }) { anime ->
                    AnimeCard(
                        anime = anime,
                        availableOffline = anime.id in offlineAnimeIds,
                        onSelect = onSelect
                    )
                }
                if (loading || loadingPage || error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (error == null) {
                                CircularProgressIndicator()
                            } else {
                                Button(onClick = onRetry) {
                                    Text(stringResource(R.string.try_again))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeCard(
    anime: Anime,
    availableOffline: Boolean,
    onSelect: (Anime) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(320.dp).clickable { onSelect(anime) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AsyncImage(
            model = anime.cover,
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
