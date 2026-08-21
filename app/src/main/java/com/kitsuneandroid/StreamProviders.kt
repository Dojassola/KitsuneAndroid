package com.kitsuneandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CancellationException

internal enum class BuiltInStreamProvider(val title: String) {
    NYAA("Nyaa"),
    NEKOBT("nekoBT"),
    SUKEBEI("Nyaa Sukebei")
}

internal data class StreamRequest(
    val anime: Anime,
    val episode: Int?,
    val preferences: ReleasePreferences,
    val remoteVideoId: String? = null,
    val remoteProviders: List<RemoteProviderConfig> = emptyList(),
    val builtInProviders: Set<BuiltInStreamProvider> = BuiltInStreamProvider.entries.toSet(),
    val playbackCapabilities: PlaybackCapabilities = PlaybackCapabilities.commonAndroid()
)

internal sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data object Empty : ProviderResult<Nothing>
    data class Failure(
        val providerId: String,
        val message: String,
        val cause: Throwable? = null
    ) : ProviderResult<Nothing>
}

internal interface StreamProvider {
    val id: String
    suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>>
}

internal object BuiltInNyaaStreamProvider : StreamProvider {
    override val id = "nyaa"

    override suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>> {
        val releases = ReleaseSearch.search(
            anime = request.anime,
            episode = request.episode,
            preferences = request.preferences,
            playbackCapabilities = request.playbackCapabilities
        )

        if (releases.isEmpty()) {
            return ProviderResult.Empty
        }

        return ProviderResult.Success(releases)
    }
}

internal object BuiltInSukebeiStreamProvider : StreamProvider {
    override val id = "sukebei"

    override suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>> {
        val releases = ReleaseSearch.search(
            anime = request.anime,
            episode = request.episode,
            preferences = request.preferences,
            playbackCapabilities = request.playbackCapabilities,
            source = NyaaSource.SUKEBEI
        )

        if (releases.isEmpty()) {
            return ProviderResult.Empty
        }

        return ProviderResult.Success(releases)
    }
}

internal object StreamProviders {
    suspend fun search(
        request: StreamRequest,
        onUpdate: (ProviderResult<List<ReleaseCandidate>>) -> Unit = {},
        onProviderFailure: (ProviderResult.Failure) -> Unit = {}
    ): ProviderResult<List<ReleaseCandidate>> = coroutineScope {
        val providers = buildList<StreamProvider> {
            for (provider in BuiltInStreamProvider.entries) {
                if (provider in request.builtInProviders) add(provider.implementation())
            }
            val remoteProviders = request.remoteProviders.filter { config ->
                config.enabled && config.streamEnabled && config.canProvideStreams()
            }
            for (config in remoteProviders) {
                add(remoteStreamProvider(config))
            }
        }
        if (providers.isEmpty()) {
            return@coroutineScope ProviderResult.Empty
        }

        val responses = Channel<ProviderResult<List<ReleaseCandidate>>>(providers.size)
        for (provider in providers) {
            launch(Dispatchers.IO) {
                responses.send(searchProvider(provider, request))
            }
        }

        val results = mutableListOf<ProviderResult<List<ReleaseCandidate>>>()
        repeat(providers.size) {
            val response = responses.receive()
            results += response
            if (response is ProviderResult.Failure) {
                onProviderFailure(response)
            }
            val partialResult = mergeStreamResults(results)
            if (partialResult is ProviderResult.Success) {
                onUpdate(partialResult)
            }
        }

        mergeStreamResults(results)
    }

    private suspend fun searchProvider(
        provider: StreamProvider,
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> {
        return try {
            when (val result = provider.streams(request)) {
                is ProviderResult.Success -> ProviderResult.Success(
                    result.value.map { release ->
                        applyPreferredSubtitleScore(release, request.preferences.language)
                    }
                )
                ProviderResult.Empty -> ProviderResult.Empty
                is ProviderResult.Failure -> {
                    result.cause?.let { failure ->
                        AppErrors.record("provider.${provider.id}", failure)
                    }
                    result
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppErrors.record("provider.${provider.id}", error)
            ProviderResult.Failure(
                providerId = provider.id,
                message = error.message.trimmedOrNull()
                    ?: "Falha inesperada ao consultar o provedor ${provider.id}.",
                cause = error
            )
        }
    }
}

private fun RemoteProviderConfig.canProvideStreams(): Boolean {
    return capabilities.isEmpty() || "stream" in capabilities || "streams" in capabilities
}

internal fun mergeStreamResults(
    results: List<ProviderResult<List<ReleaseCandidate>>>
): ProviderResult<List<ReleaseCandidate>> {
    val successfulResults = results
        .filterIsInstance<ProviderResult.Success<List<ReleaseCandidate>>>()
    val releaseOrder = compareByDescending<ReleaseCandidate> { release -> release.score }
        .thenByDescending { release -> release.seeders }
    val releases = successfulResults
        .flatMap { result -> result.value }
        .groupBy { release -> release.infoHash.lowercase() }
        .values
        .map { matches ->
            val bestMatch = matches.minWithOrNull(releaseOrder)!!
            bestMatch.copy(
                parsed = bestMatch.parsed.copy(
                    subtitleLanguages = matches.flatMapTo(linkedSetOf()) { release ->
                        release.parsed.subtitleLanguages
                    }
                ),
                providerIds = matches.flatMapTo(linkedSetOf()) { release -> release.providerIds },
                remoteSubtitles = matches
                    .flatMap(ReleaseCandidate::remoteSubtitles)
                    .distinctBy { subtitle -> subtitle.url }
            )
        }
        .sortedWith(releaseOrder)

    if (releases.isNotEmpty()) {
        return ProviderResult.Success(releases)
    }

    val firstFailure = results
        .filterIsInstance<ProviderResult.Failure>()
        .firstOrNull()

    if (firstFailure != null) {
        return firstFailure
    }

    return ProviderResult.Empty
}

internal suspend fun <Input, Output> parallelProviderRequests(
    inputs: List<Input>,
    maxConcurrency: Int = 4,
    request: suspend (Input) -> Output
): List<Output> = coroutineScope {
    require(maxConcurrency > 0) {
        "maxConcurrency must be positive."
    }
    val permits = Semaphore(maxConcurrency)
    inputs.map { input ->
        async(Dispatchers.IO) {
            permits.withPermit {
                request(input)
            }
        }
    }.awaitAll()
}

private fun BuiltInStreamProvider.implementation(): StreamProvider = when (this) {
    BuiltInStreamProvider.NYAA -> BuiltInNyaaStreamProvider
    BuiltInStreamProvider.NEKOBT -> NekoBtStreamProvider
    BuiltInStreamProvider.SUKEBEI -> BuiltInSukebeiStreamProvider
}

internal fun streamProviderLabel(providerId: String): String = when {
    providerId == "nyaa" -> "Nyaa"
    providerId == "nekobt" -> "nekoBT"
    providerId == "sukebei" -> "Nyaa Sukebei"
    providerId.startsWith("stremio:") -> "Addon Stremio"
    providerId.startsWith("kitsune:") -> "Provider Kitsune"
    else -> providerId
}
