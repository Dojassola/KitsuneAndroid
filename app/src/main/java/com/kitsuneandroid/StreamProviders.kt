package com.kitsuneandroid

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CancellationException

internal data class StreamRequest(
    val anime: Anime,
    val episode: Int?,
    val preferences: ReleasePreferences,
    val stremioAddons: List<StremioAddonConfig> = emptyList(),
    val nyaaEnabled: Boolean = true,
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

internal object StreamProviders {
    suspend fun search(
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> = coroutineScope {
        val providers = buildList<StreamProvider> {
            if (request.nyaaEnabled) {
                add(BuiltInNyaaStreamProvider)
            }

            for (config in request.stremioAddons.filter(StremioAddonConfig::enabled)) {
                add(StremioStreamProvider(config))
            }
        }
        val results = providers.map { provider ->
            async {
                searchProvider(provider, request)
            }
        }.awaitAll()

        mergeStreamResults(results)
    }

    private suspend fun searchProvider(
        provider: StreamProvider,
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> {
        return try {
            provider.streams(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val errorMessage = error.message
            val message: String

            if (errorMessage.isNullOrBlank()) {
                message = "Falha inesperada ao consultar o provedor ${provider.id}."
            } else {
                message = errorMessage
            }

            ProviderResult.Failure(
                providerId = provider.id,
                message = message,
                cause = error
            )
        }
    }
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
        .distinctBy(ReleaseCandidate::infoHash)
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
