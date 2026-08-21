package com.kitsuneandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.DateFormat
import java.util.Date

internal data class ContinueWatchingItem(
    val anime: Anime,
    val history: WatchedVideo
)

internal fun continueWatchingItems(
    history: List<WatchedVideo>,
    knownAnime: List<Anime>
): List<ContinueWatchingItem> {
    val knownById = knownAnime.associateBy(Anime::id)
    return history
        .asSequence()
        .filter { item -> item.animeId != null && !item.completed }
        .sortedByDescending(WatchedVideo::watchedAt)
        .distinctBy(WatchedVideo::animeId)
        .take(10)
        .map { item ->
            val animeId = requireNotNull(item.animeId)
            val fallbackTitle = item.animeTitle
                ?.takeIf(String::isNotBlank)
                ?: item.title
            val anime = knownById[animeId] ?: Anime(
                id = animeId,
                malId = null,
                title = fallbackTitle,
                romajiTitle = fallbackTitle,
                englishTitle = null,
                description = "",
                cover = item.animeCoverUrl ?: item.animeCoverPath.orEmpty(),
                banner = null,
                episodes = null,
                score = null,
                year = null,
                season = null,
                format = null,
                status = null,
                genres = emptyList()
            )
            ContinueWatchingItem(anime, item)
        }
        .toList()
}

@Composable
internal fun HomeDiscoveryHeader(
    discovery: Map<HomeSection, List<Anime>>,
    loadingSections: Set<HomeSection>,
    continueWatching: List<ContinueWatchingItem>,
    onSelect: (Anime) -> Unit,
    onContinue: (WatchedVideo) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (continueWatching.isNotEmpty()) {
            HomeRow(
                title = stringResource(R.string.continue_watching),
                loading = false
            ) {
                items(continueWatching, key = { item -> item.anime.id }) { item ->
                    ContinueWatchingCard(item, onContinue)
                }
            }
        }
        DiscoveryRow(
            title = stringResource(R.string.airing_today),
            section = HomeSection.AIRING_TODAY,
            discovery = discovery,
            loadingSections = loadingSections,
            onSelect = onSelect
        )
        DiscoveryRow(
            title = stringResource(R.string.trending_now),
            section = HomeSection.TRENDING,
            discovery = discovery,
            loadingSections = loadingSections,
            onSelect = onSelect
        )
        DiscoveryRow(
            title = stringResource(R.string.upcoming_releases),
            section = HomeSection.UPCOMING,
            discovery = discovery,
            loadingSections = loadingSections,
            onSelect = onSelect
        )
        DiscoveryRow(
            title = stringResource(R.string.recommended_for_you),
            section = HomeSection.RECOMMENDED,
            discovery = discovery,
            loadingSections = loadingSections,
            onSelect = onSelect
        )
        Text(
            text = stringResource(R.string.explore_catalog),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DiscoveryRow(
    title: String,
    section: HomeSection,
    discovery: Map<HomeSection, List<Anime>>,
    loadingSections: Set<HomeSection>,
    onSelect: (Anime) -> Unit
) {
    val anime = discovery[section].orEmpty()
    if (anime.isEmpty() && section !in loadingSections) {
        return
    }

    HomeRow(
        title = title,
        loading = section in loadingSections
    ) {
        items(anime, key = Anime::id) { item ->
            HomeAnimeCard(item, onSelect)
        }
    }
}

@Composable
private fun HomeRow(
    title: String,
    loading: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
internal fun HomeAnimeCard(
    anime: Anime,
    onSelect: (Anime) -> Unit
) {
    val context = LocalContext.current
    val image = remember(anime.cover) {
        ImageRequest.Builder(context)
            .data(anime.cover)
            .crossfade(180)
            .build()
    }
    Card(
        modifier = Modifier.width(142.dp).clickable { onSelect(anime) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        AsyncImage(
            model = image,
            contentDescription = stringResource(R.string.anime_cover, anime.title),
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(8.dp)) {
            Text(
                text = anime.title,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            val metadata = homeAnimeMetadata(anime)
            if (metadata != null) {
                Text(
                    text = metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onContinue: (WatchedVideo) -> Unit
) {
    val history = item.history
    val progress = if (history.durationMs > 0) {
        (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box {
        HomeAnimeCard(item.anime) {
            onContinue(history)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .width(142.dp)
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun homeAnimeMetadata(anime: Anime): String? {
    val nextAiringAt = anime.nextAiringAt
    if (nextAiringAt != null) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(Date(nextAiringAt * 1_000))
        val episode = anime.nextAiringEpisode
        if (episode != null) {
            return stringResource(R.string.episode_at_time, episode, time)
        }
    }

    return listOfNotNull(
        anime.year?.toString(),
        anime.score?.let { score -> "★ $score%" }
    ).joinToString(" • ").takeIf(String::isNotBlank)
}
