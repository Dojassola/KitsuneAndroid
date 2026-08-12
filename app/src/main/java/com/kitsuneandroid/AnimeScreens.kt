@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kitsuneandroid

import android.content.Intent
import android.net.Uri
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun AnimeDetails(
    anime: Anime,
    favorite: Boolean,
    offlineEpisodes: List<TorrentDownload>,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onWatch: () -> Unit,
    onEpisode: (Episode) -> Unit,
    onPlayOffline: (TorrentDownload) -> Unit,
    onReleases: (Int?) -> Unit,
    onSeason: (Anime) -> Unit
) {
    val context = LocalContext.current
    val metadataLanguage = remember(anime.id) { loadMetadataLanguage(context) }
    var animeDescription by remember(anime.id) { mutableStateOf(anime.description) }
    var episodes by remember(anime.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var episodeLoading by remember(anime.id) { mutableStateOf(anime.format != "MOVIE") }
    var episodeError by remember(anime.id) { mutableStateOf<String?>(null) }
    var seasons by remember(anime.id) { mutableStateOf(listOf(anime)) }
    var seasonLoading by remember(anime.id) { mutableStateOf(true) }
    var seasonError by remember(anime.id) { mutableStateOf<String?>(null) }
    val displayedSeasonNumber = seasons
        .firstOrNull { season -> season.id == anime.id }
        ?.seasonNumber
        ?: anime.seasonNumber

    LaunchedEffect(anime.id, metadataLanguage) {
        animeDescription = withContext(Dispatchers.IO) {
            EpisodeApi.localized(anime.description, metadataLanguage)
        }
    }

    LaunchedEffect(anime.id) {
        if (anime.format == "MOVIE") {
            return@LaunchedEffect
        }

        try {
            episodes = withContext(Dispatchers.IO) {
                EpisodeApi.list(anime)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            episodeError = failure.message
                ?: context.getString(R.string.error_load_episodes)
        } finally {
            episodeLoading = false
        }
    }
    LaunchedEffect(anime.id) {
        try {
            seasons = withContext(Dispatchers.IO) {
                AnimeApi.seasonChain(anime)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            seasonError = failure.message
                ?: context.getString(R.string.error_load_seasons)
        } finally {
            seasonLoading = false
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(anime.title, maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AsyncImage(
                    model = anime.banner ?: anime.cover,
                    contentDescription = stringResource(R.string.image_of, anime.title),
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(16.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            anime.year?.toString(), anime.format?.replace('_', ' '),
                            displayedSeasonNumber?.let { stringResource(R.string.season_number, it) },
                            anime.episodes?.let { pluralStringResource(R.plurals.episode_count, it, it) },
                            anime.score?.let { "★ $it%" }
                        ).joinToString("  •  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (anime.genres.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(anime.genres.joinToString(" • "), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onWatch) { Text(stringResource(R.string.watch_file)) }
                        Button(onClick = onFavorite) {
                            Text(stringResource(if (favorite) R.string.remove_favorite else R.string.add_favorite))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.synopsis), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(animeDescription.ifBlank { stringResource(R.string.synopsis_unavailable) })
                    anime.status?.let {
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.status_value, it.replace('_', ' ')), style = MaterialTheme.typography.labelLarge)
                    }
                    if (anime.format == "MOVIE") {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { onReleases(null) }) { Text(stringResource(R.string.find_movie_video)) }
                    }
                }
            }
            if (anime.format != "MOVIE") {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val currentIndex = seasons.indexOfFirst { season -> season.id == anime.id }
                            .coerceAtLeast(0)
                        val currentSeason = seasons.getOrNull(currentIndex) ?: anime
                        val previousSeasonLabel = stringResource(R.string.previous_season).uppercase()
                        val nextSeasonLabel = stringResource(R.string.next_season).uppercase()
                        val adjacentSeasons = listOfNotNull(
                            seasons.getOrNull(currentIndex - 1)?.let { previousSeasonLabel to it },
                            seasons.getOrNull(currentIndex + 1)?.let { nextSeasonLabel to it }
                        )

                        Text(
                            stringResource(R.string.season_number, currentSeason.seasonNumber ?: currentIndex + 1),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (seasonLoading) CircularProgressIndicator(Modifier.padding(vertical = 12.dp))
                        adjacentSeasons.forEach { (label, seasonAnime) ->
                            Card(
                                Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onSeason(seasonAnime) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            stringResource(R.string.season_title, seasonAnime.seasonNumber ?: currentIndex + 1, seasonAnime.title),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            listOfNotNull(
                                                seasonAnime.year?.toString(),
                                                seasonAnime.episodes?.let { pluralStringResource(R.plurals.episode_count, it, it) }
                                            ).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(stringResource(R.string.view_chevron))
                                }
                            }
                        }
                        seasonError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            if (anime.format != "MOVIE") {
                item {
                    Text(stringResource(R.string.episodes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    if (episodeLoading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    episodeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                lazyItems(episodes, key = { it.number }) { episode ->
                    val offlineDownload = offlineEpisode(
                        episodes = offlineEpisodes,
                        animeId = anime.id,
                        episodeNumber = episode.number
                    )
                    val offlineUri = offlineDownload?.let { download ->
                        playbackUri(download).toString()
                    }
                    val history = historyForEpisode(
                        history = VideoHistory.items,
                        animeId = anime.id,
                        episode = episode.number,
                        offlineUri = offlineUri
                    )
                    val completed = VideoHistory.isEpisodeCompleted(
                        history = history,
                        animeId = anime.id,
                        episode = episode.number,
                        uri = offlineUri
                    )
                    val watchStatus = when {
                        completed -> stringResource(R.string.watched)
                        history != null -> stringResource(
                            R.string.stopped_at,
                            formatDuration(history.positionMs)
                        )
                        else -> null
                    }
                    val watchToggleDescription = stringResource(
                        if (completed) R.string.mark_as_unwatched else R.string.mark_as_watched
                    )
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .clickable {
                                if (offlineDownload != null) {
                                    onPlayOffline(offlineDownload)
                                } else {
                                    onEpisode(episode)
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("EP\n${episode.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.episode_number, episode.number), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOfNotNull(
                                        episode.airedAt?.substringBefore('T'),
                                        episode.durationSeconds?.let { "${it / 60} min" },
                                        stringResource(R.string.filler).takeIf { episode.filler },
                                        stringResource(R.string.recap).takeIf { episode.recap },
                                        stringResource(R.string.available_offline).takeIf { offlineDownload != null },
                                        watchStatus
                                    ).joinToString(" • ").ifBlank { stringResource(R.string.view_episode_info) },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(
                                    checked = completed,
                                    onCheckedChange = { checked ->
                                        VideoHistory.setEpisodeCompleted(
                                            context = context,
                                            animeId = anime.id,
                                            episode = episode.number,
                                            uri = offlineUri,
                                            completed = checked
                                        )
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = watchToggleDescription
                                    }
                                )
                                Text(
                                    if (offlineDownload == null) "›" else stringResource(R.string.watch),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun EpisodeScreen(
    anime: Anime,
    initialEpisode: Episode,
    initialReleases: List<ReleaseCandidate>?,
    onBack: () -> Unit,
    onReleases: (List<ReleaseCandidate>, ReleaseCandidate?) -> Unit
) {
    val context = LocalContext.current
    val releasePreferences = remember { loadReleasePreferences(context) }
    val externalSubtitlesMatchPreference = remember {
        loadSubtitleProviderSettings(context).matches(releasePreferences.language)
    }
    val metadataLanguage = remember { loadMetadataLanguage(context) }
    val playbackCapabilities = remember { PlaybackCapabilities.detect() }
    var episode by remember(anime.id, initialEpisode.number) { mutableStateOf(initialEpisode) }
    var animeSynopsis by remember(anime.id) { mutableStateOf(anime.description) }
    var loading by remember(anime.id, initialEpisode.number) { mutableStateOf(true) }
    var error by remember(anime.id, initialEpisode.number) { mutableStateOf<String?>(null) }
    var releases by remember(anime.id, initialEpisode.number) { mutableStateOf(initialReleases.orEmpty()) }
    var releaseLoading by remember(anime.id, initialEpisode.number) { mutableStateOf(initialReleases == null) }
    var releaseError by remember(anime.id, initialEpisode.number) { mutableStateOf<String?>(null) }
    var providerWarnings by remember(anime.id, initialEpisode.number) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    LaunchedEffect(anime.id, initialEpisode.number) {
        try {
            val details = withContext(Dispatchers.IO) {
                EpisodeApi.details(anime, initialEpisode.number, metadataLanguage) to
                    EpisodeApi.localized(anime.description, metadataLanguage)
            }
            val episodeDetails = details.first
            animeSynopsis = details.second
            episode = mergeEpisodeDetails(
                initial = initialEpisode,
                fetched = episodeDetails,
                displayedThumbnail = anime.banner ?: anime.cover
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            error = failure.message
                ?: context.getString(R.string.error_load_episode_details)
        } finally {
            loading = false
        }
    }
    LaunchedEffect(anime.id, initialEpisode.number) {
        if (initialReleases != null) {
            releases = initialReleases
            releaseLoading = false
            return@LaunchedEffect
        }
        try {
            providerWarnings = emptyMap()
            val request = StreamRequest(
                anime = anime,
                episode = initialEpisode.number,
                remoteVideoId = initialEpisode.remoteVideoId,
                preferences = releasePreferences,
                remoteProviders = loadRemoteProviderConfigs(context),
                builtInProviders = loadBuiltInStreamProviders(context),
                playbackCapabilities = playbackCapabilities
            )
            val providerResult = StreamProviders.search(
                request = request,
                onUpdate = { partialResult ->
                    if (partialResult is ProviderResult.Success) {
                        releases = partialResult.value
                        releaseLoading = false
                        releaseError = null
                    }
                },
                onProviderFailure = { failure ->
                    providerWarnings = providerWarnings + (
                        failure.providerId to providerFailureMessage(failure)
                    )
                }
            )

            when (providerResult) {
                is ProviderResult.Success -> {
                    releases = providerResult.value
                }

                ProviderResult.Empty -> {
                    releases = emptyList()
                }

                is ProviderResult.Failure -> {
                    releaseError = context.getString(R.string.error_query_video_providers)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            releaseError = context.getString(R.string.error_search_videos)
        } finally {
            releaseLoading = false
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.anime_episode_title, anime.title, episode.number), maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AsyncImage(
                    model = episode.thumbnail ?: anime.banner ?: anime.cover,
                    contentDescription = stringResource(R.string.episode_image, episode.number),
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.episode_number_padded, episode.number.toString().padStart(2, '0')), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(episode.title ?: stringResource(R.string.episode_number, episode.number), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    episode.japaneseTitle?.takeIf { it != episode.title }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    episode.romanjiTitle?.takeIf { it != episode.title && it != episode.japaneseTitle }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        listOfNotNull(
                            episode.airedAt?.substringBefore('T'),
                            episode.durationSeconds?.let { "${it / 60} min" },
                            stringResource(R.string.filler).takeIf { episode.filler },
                            stringResource(R.string.recap).takeIf { episode.recap }
                        ).joinToString("  •  ").ifBlank { stringResource(R.string.playback_info_unavailable) },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(stringResource(R.string.best_option_to_watch), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val recommended = recommendedRelease(
                        releases = releases,
                        preferences = releasePreferences,
                        playbackCapabilities = playbackCapabilities,
                        externalSubtitlesMatchPreference = externalSubtitlesMatchPreference
                    )
                    when {
                        releaseLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp))
                            Text(stringResource(R.string.searching_quality_seeders))
                        }
                        recommended != null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(stringResource(R.string.recommended).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(recommended.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(
                                    listOfNotNull(
                                        recommended.parsed.resolution?.let { "${it}p" },
                                        recommended.parsed.codec,
                                        pluralStringResource(
                                            R.plurals.seeders_informed,
                                            recommended.seeders,
                                            recommended.seeders
                                        ),
                                        "PT-BR".takeIf { recommended.parsed.ptBr },
                                        stringResource(R.string.dubbed).takeIf { recommended.parsed.dubbed },
                                        stringResource(R.string.batch_only_episode).takeIf { recommended.parsed.batch }
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { onReleases(releases, recommended) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                ) { Text(stringResource(R.string.download_and_watch)) }
                                TextButton(onClick = { onReleases(releases, null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.view_all_options)) }
                            }
                        }
                        else -> {
                            Text(releaseError ?: stringResource(R.string.no_compatible_video), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onReleases(releases, null) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.try_full_search)) }
                        }
                    }
                    if (providerWarnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.some_video_providers_failed,
                                providerWarnings.values.joinToString(" • ")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    val hasEpisodeSynopsis = !episode.synopsis.isNullOrBlank()
                    Text(stringResource(if (hasEpisodeSynopsis) R.string.episode_synopsis else R.string.about_anime), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (hasEpisodeSynopsis) episode.synopsis.orEmpty() else animeSynopsis.ifBlank { stringResource(R.string.synopsis_unavailable) })
                    if (!hasEpisodeSynopsis) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.episode_synopsis_not_registered), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (loading) {
                        Spacer(Modifier.height(18.dp))
                        CircularProgressIndicator()
                    }
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun ReleaseScreen(
    anime: Anime,
    episode: Int?,
    initialReleases: List<ReleaseCandidate>?,
    autoReleaseId: String?,
    onBack: () -> Unit,
    onDownload: (ReleaseCandidate, List<Int>, Int) -> Unit,
    onPlayDirect: (ReleaseCandidate) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val releasePreferences = remember { loadReleasePreferences(context) }
    val externalSubtitlesMatchPreference = remember {
        loadSubtitleProviderSettings(context).matches(releasePreferences.language)
    }
    val playbackCapabilities = remember { PlaybackCapabilities.detect() }
    var releases by remember(anime.id, episode) { mutableStateOf(initialReleases.orEmpty()) }
    var loading by remember(anime.id, episode) { mutableStateOf(initialReleases == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inspectingId by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var selectedRelease by remember { mutableStateOf<ReleaseCandidate?>(null) }
    var choices by remember { mutableStateOf<List<TorrentFileChoice>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var automaticHandled by remember(anime.id, episode, autoReleaseId) { mutableStateOf(false) }
    var releaseSort by rememberSaveable(anime.id, episode) {
        mutableStateOf(ReleaseSort.RECOMMENDED)
    }
    var providerWarnings by remember(anime.id, episode) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    fun inspect(release: ReleaseCandidate) {
        inspectingId = release.id
        fileError = null
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    TorrentService.inspect(context, release)
                }
                val selection = release.torrentFileIndex?.let { fileIndex ->
                    explicitTorrentSelection(files, fileIndex)
                } ?: defaultTorrentSelection(files, episode)

                when {
                    selection == null -> {
                        fileError = context.getString(R.string.release_has_no_recognized_video)
                    }
                    files.count(TorrentFileChoice::isVideo) == 1 -> {
                        onDownload(release, selection.first, selection.second)
                    }
                    else -> {
                        selectedRelease = release
                        choices = files
                        selectedFiles = selection.first.toSet()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                fileError = failure.message
                    ?: context.getString(R.string.error_read_torrent_files)
            } finally {
                inspectingId = null
            }
        }
    }

    fun openRelease(release: ReleaseCandidate) {
        if (release.directUrl != null) {
            onPlayDirect(release)
            return
        }

        inspect(release)
    }

    LaunchedEffect(anime.id, episode) {
        if (initialReleases != null) {
            releases = initialReleases
            loading = false
            return@LaunchedEffect
        }
        providerWarnings = emptyMap()
        val request = StreamRequest(
            anime = anime,
            episode = episode,
            preferences = releasePreferences,
            remoteProviders = loadRemoteProviderConfigs(context),
            builtInProviders = loadBuiltInStreamProviders(context),
            playbackCapabilities = playbackCapabilities
        )
        val result = StreamProviders.search(
            request = request,
            onUpdate = { partialResult ->
                if (partialResult is ProviderResult.Success) {
                    releases = partialResult.value
                    loading = false
                    error = null
                }
            },
            onProviderFailure = { failure ->
                providerWarnings = providerWarnings + (
                    failure.providerId to providerFailureMessage(failure)
                )
            }
        )

        when (result) {
            is ProviderResult.Success -> {
                releases = result.value
            }

            ProviderResult.Empty -> {
                releases = emptyList()
            }

            is ProviderResult.Failure -> {
                error = result.message
            }
        }

        loading = false
    }
    LaunchedEffect(releases, autoReleaseId) {
        if (!automaticHandled && autoReleaseId != null) {
            releases.firstOrNull { it.id == autoReleaseId }?.let {
                automaticHandled = true
                openRelease(it)
            }
        }
    }
    selectedRelease?.let { release ->
        val videoFile = primaryTorrentVideo(choices, selectedFiles, episode)
        AlertDialog(
            onDismissRequest = { selectedRelease = null },
            title = { Text(stringResource(R.string.choose_files)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    lazyItems(choices, key = TorrentFileChoice::index) { file ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedFiles = if (file.index in selectedFiles) selectedFiles - file.index else selectedFiles + file.index
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = file.index in selectedFiles,
                                onCheckedChange = { checked ->
                                    selectedFiles = if (checked) selectedFiles + file.index else selectedFiles - file.index
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(File(file.path).name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${stringResource(if (file.isVideo) R.string.video else R.string.subtitle)} • ${formatBytes(file.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = videoFile != null,
                    onClick = {
                        val files = choices.filter { it.index in selectedFiles }.map(TorrentFileChoice::index)
                        onDownload(release, files, requireNotNull(videoFile))
                        selectedRelease = null
                    }
                ) { Text(stringResource(R.string.download_selected)) }
            },
            dismissButton = { TextButton(onClick = { selectedRelease = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    episode?.let { stringResource(R.string.choose_video_episode, it) }
                        ?: stringResource(R.string.choose_video),
                    maxLines = 1
                )
            },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.seeders_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (providerWarnings.isNotEmpty()) {
                item {
                    Text(
                        stringResource(
                            R.string.some_video_providers_failed,
                            providerWarnings.values.joinToString(" • ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            fileError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
            if (!loading && error == null && releases.isEmpty()) item { Text(stringResource(R.string.no_compatible_video), modifier = Modifier.padding(16.dp)) }
            val recommended = recommendedRelease(
                releases = releases,
                preferences = releasePreferences,
                externalSubtitlesMatchPreference = externalSubtitlesMatchPreference
            )
            if (releases.size > 1) {
                item {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReleaseSort.entries.forEach { sort ->
                            val label = when (sort) {
                                ReleaseSort.RECOMMENDED -> stringResource(R.string.sort_recommended)
                                ReleaseSort.SEEDERS -> stringResource(R.string.sort_seeders)
                                ReleaseSort.SIZE -> stringResource(R.string.sort_smallest)
                            }
                            FilterChip(
                                selected = releaseSort == sort,
                                onClick = { releaseSort = sort },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
            val orderedReleases = sortedReleaseOptions(
                releases = releases,
                recommendedId = recommended?.id,
                sort = releaseSort
            )
            lazyItems(orderedReleases, key = { it.id }) { release ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        if (release.id == recommended?.id) Text(stringResource(R.string.recommended).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(release.title, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            releaseSummary(
                                release = release,
                                seedersInformed = pluralStringResource(
                                    R.plurals.seeders_informed,
                                    release.seeders,
                                    release.seeders
                                ),
                                seedersUnknown = stringResource(R.string.seeders_unknown),
                                directStream = stringResource(R.string.direct_stream),
                                batch = stringResource(R.string.batch),
                                score = stringResource(R.string.score_value, release.score)
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (release.reasons.isNotEmpty()) {
                            Text(
                                localizedReleaseReasons(release.reasons.take(4)).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            Button(onClick = { openRelease(release) }, enabled = inspectingId == null) {
                                if (inspectingId == release.id) {
                                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                                } else {
                                    Text(stringResource(if (release.directUrl != null) R.string.watch else R.string.download_and_watch))
                                }
                            }
                            release.sourceUrl?.let { sourceUrl ->
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                                }) { Text(stringResource(R.string.view_source)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun providerFailureMessage(failure: ProviderResult.Failure): String {
    return "${streamProviderLabel(failure.providerId)}: ${failure.message}"
}

internal fun releaseSummary(
    release: ReleaseCandidate,
    seedersInformed: String = "${release.seeders} seeders informed",
    seedersUnknown: String = "Seeders unknown",
    directStream: String = "Direct stream",
    batch: String = "Batch",
    score: String = "score ${release.score}"
): String {
    val details = mutableListOf(release.providerIds.joinToString(" + ", transform = ::streamProviderLabel))
    release.parsed.resolution?.let { resolution -> details += "${resolution}p" }
    details += release.parsed.codec
    if (release.parsed.tenBit) details += "10-bit"
    if (release.sizeBytes > 0) details += formatBytes(release.sizeBytes)
    if (release.seeders > 0) {
        details += seedersInformed
    } else if (release.magnetUri != null) {
        details += seedersUnknown
    }
    if (release.directUrl != null) details += directStream
    if (release.parsed.batch) details += batch
    details += score
    return details.joinToString(" • ")
}

@Composable
private fun localizedReleaseReasons(reasons: List<String>): List<String> {
    if (LocalConfiguration.current.locales[0].language == "pt") {
        return reasons
    }

    return reasons.map { reason ->
        when {
            reason == "Título reconhecido" -> stringResource(R.string.reason_title_recognized)
            reason == "Indica legenda PT-BR" -> stringResource(R.string.reason_pt_br_subtitle)
            reason == "Decodificação por hardware disponível" -> stringResource(R.string.reason_hardware_decoding)
            reason == "Compatível por software; pode consumir mais bateria" -> stringResource(R.string.reason_software_decoding)
            reason == "Codec ou perfil incompatível com este aparelho" -> stringResource(R.string.reason_codec_incompatible)
            reason == "Perfil 10-bit sem compatibilidade confirmada" -> stringResource(R.string.reason_10bit_unknown)
            reason == "Compatibilidade do codec não confirmada" -> stringResource(R.string.reason_codec_unknown)
            reason.startsWith("Episódio ") && reason.endsWith(" corresponde") -> {
                stringResource(R.string.reason_episode_matches, reason.removePrefix("Episódio ").removeSuffix(" corresponde"))
            }
            reason.endsWith(" seeders informados") -> {
                stringResource(R.string.reason_seeders_reported, reason.substringBefore(' '))
            }
            reason.startsWith("Prioridade ") && reason.endsWith(" do provedor") -> {
                stringResource(R.string.reason_provider_priority, reason.removePrefix("Prioridade ").removeSuffix(" do provedor"))
            }
            reason.startsWith("Stream direto fornecido por ") -> {
                stringResource(R.string.reason_direct_stream_by, reason.removePrefix("Stream direto fornecido por "))
            }
            reason.startsWith("Torrent fornecido por ") -> {
                stringResource(R.string.reason_torrent_by, reason.removePrefix("Torrent fornecido por "))
            }
            reason.startsWith("Fornecido por ") -> {
                stringResource(R.string.reason_provided_by, reason.removePrefix("Fornecido por "))
            }
            else -> reason
        }
    }
}
