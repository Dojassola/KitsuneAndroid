package com.kitsuneandroid

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CancellationException

internal enum class BuiltInStreamProvider(val title: String) {
    NYAA("Nyaa"),
    TOKYO_TOSHOKAN("Tokyo Toshokan")
}

internal data class StreamRequest(
    val anime: Anime,
    val episode: Int?,
    val preferences: ReleasePreferences,
    val stremioAddons: List<StremioAddonConfig> = emptyList(),
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

internal object StreamProviders {
    suspend fun search(
        request: StreamRequest
    ): ProviderResult<List<ReleaseCandidate>> = coroutineScope {
        val providers = buildList<StreamProvider> {
            for (provider in BuiltInStreamProvider.entries) {
                if (provider in request.builtInProviders) add(provider.implementation())
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
            ProviderResult.Failure(
                providerId = provider.id,
                message = error.message.trimmedOrNull()
                    ?: "Falha inesperada ao consultar o provedor ${provider.id}.",
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
        .distinctBy { release -> release.infoHash.lowercase() }
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

private fun BuiltInStreamProvider.implementation(): StreamProvider = when (this) {
    BuiltInStreamProvider.NYAA -> BuiltInNyaaStreamProvider
    BuiltInStreamProvider.TOKYO_TOSHOKAN -> TokyoToshoStreamProvider
}

internal fun streamProviderLabel(providerId: String): String = when {
    providerId == "nyaa" -> "Nyaa"
    providerId == "tokyotosho" -> "Tokyo Toshokan"
    providerId.startsWith("stremio:") -> "Addon Stremio"
    else -> providerId
}
