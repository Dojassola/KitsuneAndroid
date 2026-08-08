@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kitsuneandroid

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AnimeDetails(
    anime: Anime,
    favorite: Boolean,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onWatch: () -> Unit,
    onEpisode: (Episode) -> Unit,
    onReleases: (Int?) -> Unit,
    onSeason: (Anime) -> Unit
) {
    var episodes by remember(anime.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var episodeLoading by remember(anime.id) { mutableStateOf(anime.format != "MOVIE") }
    var episodeError by remember(anime.id) { mutableStateOf<String?>(null) }
    var seasonRelations by remember(anime.id) { mutableStateOf<List<AnimeSeasonRelation>>(emptyList()) }
    var seasonLoading by remember(anime.id) { mutableStateOf(true) }
    var seasonError by remember(anime.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(anime.id) {
        if (anime.format == "MOVIE") return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { EpisodeApi.list(anime) } }
            .onSuccess { episodes = it }
            .onFailure { episodeError = it.message ?: "Não foi possível carregar os episódios." }
        episodeLoading = false
    }
    LaunchedEffect(anime.id) {
        runCatching { withContext(Dispatchers.IO) { AnimeApi.seasonRelations(anime) } }
            .onSuccess { seasonRelations = it }
            .onFailure { seasonError = "Não foi possível carregar as outras temporadas." }
        seasonLoading = false
    }

    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(anime.title, maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AsyncImage(
                    model = anime.banner ?: anime.cover,
                    contentDescription = "Imagem de ${anime.title}",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(16.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            anime.year?.toString(), anime.format?.replace('_', ' '),
                            anime.episodes?.let { "$it episódios" }, anime.score?.let { "★ $it%" }
                        ).joinToString("  •  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (anime.genres.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(anime.genres.joinToString(" • "), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onWatch) { Text("Assistir arquivo") }
                        Button(onClick = onFavorite) { Text(if (favorite) "Remover favorito" else "Favoritar") }
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    Text("Sinopse", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(anime.description.ifBlank { "Sinopse indisponível." })
                    anime.status?.let {
                        Spacer(Modifier.height(20.dp))
                        Text("Status: ${it.replace('_', ' ')}", style = MaterialTheme.typography.labelLarge)
                    }
                    if (anime.format == "MOVIE") {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { onReleases(null) }) { Text("Encontrar vídeo do filme") }
                    }
                }
            }
            if (seasonLoading || seasonRelations.isNotEmpty() || seasonError != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Temporadas e continuações", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (seasonLoading) CircularProgressIndicator(Modifier.padding(vertical = 12.dp))
                        seasonRelations.forEach { relation ->
                            val seasonAnime = relation.anime
                            Card(
                                Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onSeason(seasonAnime) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (relation.type == "PREQUEL") "TEMPORADA ANTERIOR" else "PRÓXIMA TEMPORADA",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(seasonAnime.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOfNotNull(seasonAnime.year?.toString(), seasonAnime.episodes?.let { "$it episódios" }).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text("Ver ›")
                                }
                            }
                        }
                        seasonError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            if (anime.format != "MOVIE") {
                item {
                    Text("Episódios", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    if (episodeLoading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    episodeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                lazyItems(episodes, key = { it.number }) { episode ->
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { onEpisode(episode) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("EP\n${episode.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Episódio ${episode.number}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOfNotNull(
                                        episode.airedAt?.substringBefore('T'),
                                        episode.durationSeconds?.let { "${it / 60} min" },
                                        "Filler".takeIf { episode.filler },
                                        "Recap".takeIf { episode.recap }
                                    ).joinToString(" • ").ifBlank { "Ver informações do episódio" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("›", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EpisodeScreen(
    anime: Anime,
    initialEpisode: Episode,
    initialReleases: List<ReleaseCandidate>?,
    onBack: () -> Unit,
    onReleases: (List<ReleaseCandidate>, ReleaseCandidate?) -> Unit
) {
    val context = LocalContext.current
    val releasePreferences = remember { loadReleasePreferences(context) }
    var episode by remember(anime.id, initialEpisode.number) { mutableStateOf(initialEpisode) }
    var animeSynopsis by remember(anime.id) { mutableStateOf(anime.description) }
    var loading by remember(anime.id, initialEpisode.number) { mutableStateOf(true) }
    var error by remember(anime.id, initialEpisode.number) { mutableStateOf<String?>(null) }
    var releases by remember(anime.id, initialEpisode.number) { mutableStateOf(initialReleases.orEmpty()) }
    var releaseLoading by remember(anime.id, initialEpisode.number) { mutableStateOf(initialReleases == null) }
    var releaseError by remember(anime.id, initialEpisode.number) { mutableStateOf<String?>(null) }

    LaunchedEffect(anime.id, initialEpisode.number) {
        runCatching {
            withContext(Dispatchers.IO) {
                EpisodeApi.details(anime, initialEpisode.number) to EpisodeApi.portuguese(anime.description)
            }
        }.onSuccess { (details, translatedAnimeSynopsis) ->
                animeSynopsis = translatedAnimeSynopsis
                val it = details
                episode = it.copy(
                    title = it.title ?: initialEpisode.title,
                    japaneseTitle = it.japaneseTitle ?: initialEpisode.japaneseTitle,
                    romanjiTitle = it.romanjiTitle ?: initialEpisode.romanjiTitle,
                    airedAt = it.airedAt ?: initialEpisode.airedAt,
                    durationSeconds = it.durationSeconds ?: initialEpisode.durationSeconds,
                    synopsis = it.synopsis ?: initialEpisode.synopsis
                )
            }
            .onFailure { error = it.message ?: "Não foi possível carregar todos os detalhes." }
        loading = false
    }
    LaunchedEffect(anime.id, initialEpisode.number) {
        if (initialReleases != null) {
            releases = initialReleases
            releaseLoading = false
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { ReleaseSearch.search(anime, initialEpisode.number, releasePreferences) } }
            .onSuccess { releases = it }
            .onFailure { releaseError = it.message ?: "Não foi possível procurar vídeos agora." }
        releaseLoading = false
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("${anime.title} • EP ${episode.number}", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AsyncImage(
                    model = episode.thumbnail ?: anime.banner ?: anime.cover,
                    contentDescription = "Imagem do episódio ${episode.number}",
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(18.dp)) {
                    Text("EPISÓDIO ${episode.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(episode.title ?: "Episódio ${episode.number}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
                            "Filler".takeIf { episode.filler },
                            "Recap".takeIf { episode.recap }
                        ).joinToString("  •  ").ifBlank { "Informações de exibição indisponíveis" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Melhor opção para assistir", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val recommended = recommendedRelease(releases, releasePreferences)
                    when {
                        releaseLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp))
                            Text("Procurando qualidade e seeders…")
                        }
                        recommended != null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("RECOMENDADO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(recommended.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(
                                    listOfNotNull(
                                        recommended.parsed.resolution?.let { "${it}p" },
                                        recommended.parsed.codec,
                                        "${recommended.seeders} seeders informados",
                                        "PT-BR".takeIf { recommended.parsed.ptBr },
                                        "Dublado".takeIf { recommended.parsed.dubbed },
                                        "Pacote; só este episódio".takeIf { recommended.parsed.batch }
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { onReleases(releases, recommended) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                ) { Text("Baixar e assistir") }
                                TextButton(onClick = { onReleases(releases, null) }, modifier = Modifier.fillMaxWidth()) { Text("Ver todas as opções") }
                            }
                        }
                        else -> {
                            Text(releaseError ?: "Nenhum vídeo compatível encontrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onReleases(releases, null) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Tentar busca completa") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    val hasEpisodeSynopsis = !episode.synopsis.isNullOrBlank()
                    Text(if (hasEpisodeSynopsis) "Sinopse do episódio" else "Sobre o anime", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (hasEpisodeSynopsis) episode.synopsis.orEmpty() else animeSynopsis.ifBlank { "Sinopse indisponível." })
                    if (!hasEpisodeSynopsis) {
                        Spacer(Modifier.height(8.dp))
                        Text("Este episódio ainda não possui uma sinopse cadastrada nos provedores.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun ReleaseScreen(
    anime: Anime,
    episode: Int?,
    initialReleases: List<ReleaseCandidate>?,
    autoReleaseId: String?,
    onBack: () -> Unit,
    onDownload: (ReleaseCandidate, List<Int>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val releasePreferences = remember { loadReleasePreferences(context) }
    var releases by remember(anime.id, episode) { mutableStateOf(initialReleases.orEmpty()) }
    var loading by remember(anime.id, episode) { mutableStateOf(initialReleases == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inspectingId by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var selectedRelease by remember { mutableStateOf<ReleaseCandidate?>(null) }
    var choices by remember { mutableStateOf<List<TorrentFileChoice>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var automaticHandled by remember(anime.id, episode, autoReleaseId) { mutableStateOf(false) }

    fun inspect(release: ReleaseCandidate) {
        inspectingId = release.id
        fileError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { TorrentService.inspect(release) } }
                .onSuccess { files ->
                    val selection = defaultTorrentSelection(files, episode)
                    when {
                        selection == null -> fileError = "A release não contém vídeo reconhecido."
                        files.count(TorrentFileChoice::isVideo) == 1 -> onDownload(release, selection.first, selection.second)
                        else -> {
                            selectedRelease = release
                            choices = files
                            selectedFiles = selection.first.toSet()
                        }
                    }
                }
                .onFailure { fileError = it.message ?: "Não foi possível ler os arquivos do torrent." }
            inspectingId = null
        }
    }

    LaunchedEffect(anime.id, episode) {
        if (initialReleases != null) {
            releases = initialReleases
            loading = false
            return@LaunchedEffect
        }
        when (val result = withContext(Dispatchers.IO) {
            StreamProviders.search(StreamRequest(anime, episode, releasePreferences))
        }) {
            is ProviderResult.Success -> releases = result.value
            ProviderResult.Empty -> releases = emptyList()
            is ProviderResult.Failure -> error = result.message
        }
        loading = false
    }
    LaunchedEffect(releases, autoReleaseId) {
        if (!automaticHandled && autoReleaseId != null) {
            releases.firstOrNull { it.id == autoReleaseId }?.let {
                automaticHandled = true
                inspect(it)
            }
        }
    }
    selectedRelease?.let { release ->
        val videoFile = primaryTorrentVideo(choices, selectedFiles, episode)
        AlertDialog(
            onDismissRequest = { selectedRelease = null },
            title = { Text("Escolher arquivos") },
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
                                    "${if (file.isVideo) "Vídeo" else "Legenda"} • ${formatBytes(file.sizeBytes)}",
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
                ) { Text("Baixar selecionados") }
            },
            dismissButton = { TextButton(onClick = { selectedRelease = null }) { Text("Cancelar") } }
        )
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(episode?.let { "Escolher vídeo • Episódio $it" } ?: "Escolher vídeo", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    "Seeders são informados para o torrent inteiro. Em pacotes, o app baixa e prioriza somente o episódio escolhido; peers conectados aparecem em Downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            fileError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
            if (!loading && error == null && releases.isEmpty()) item { Text("Nenhum vídeo compatível encontrado.", modifier = Modifier.padding(16.dp)) }
            val recommended = recommendedRelease(releases, releasePreferences)
            val orderedReleases = recommended?.let { listOf(it) + releases.filterNot { release -> release.id == it.id } } ?: releases
            lazyItems(orderedReleases, key = { it.id }) { release ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        if (release.id == recommended?.id) Text("RECOMENDADO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(release.title, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                release.parsed.resolution?.let { "${it}p" }, release.parsed.codec,
                                "10-bit".takeIf { release.parsed.tenBit },
                                formatBytes(release.sizeBytes), "${release.seeders} seeders informados",
                                "Pacote".takeIf { release.parsed.batch }, "score ${release.score}"
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (release.reasons.isNotEmpty()) Text(release.reasons.take(4).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                            Button(onClick = { inspect(release) }, enabled = inspectingId == null) {
                                if (inspectingId == release.id) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                                else Text("Baixar e assistir")
                            }
                            release.sourceUrl?.let { sourceUrl ->
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                                }) { Text("Ver origem") }
                            }
                        }
                    }
                }
            }
        }
    }
}
