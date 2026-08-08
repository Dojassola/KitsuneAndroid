package com.kitsuneandroid

data class StreamRequest(
    val anime: Anime,
    val episode: Int?,
    val preferences: ReleasePreferences
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data object Empty : ProviderResult<Nothing>
    data class Failure(val providerId: String, val message: String) : ProviderResult<Nothing>
}

interface StreamProvider {
    val id: String
    suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>>
}

object BuiltInNyaaStreamProvider : StreamProvider {
    override val id = "nyaa"

    override suspend fun streams(request: StreamRequest): ProviderResult<List<ReleaseCandidate>> = runCatching {
        ReleaseSearch.search(request.anime, request.episode, request.preferences)
    }.fold(
        onSuccess = { releases -> if (releases.isEmpty()) ProviderResult.Empty else ProviderResult.Success(releases) },
        onFailure = { ProviderResult.Failure(id, it.message ?: "Falha ao consultar o Nyaa.") }
    )
}

object StreamProviders {
    private val providers = listOf(BuiltInNyaaStreamProvider)

    suspend fun search(request: StreamRequest): ProviderResult<List<ReleaseCandidate>> =
        mergeStreamResults(providers.map { provider ->
            runCatching { provider.streams(request) }
                .getOrElse { ProviderResult.Failure(provider.id, it.message ?: "Falha inesperada no provedor.") }
        })
}

internal fun mergeStreamResults(results: List<ProviderResult<List<ReleaseCandidate>>>): ProviderResult<List<ReleaseCandidate>> {
    val releases = results.filterIsInstance<ProviderResult.Success<List<ReleaseCandidate>>>()
        .flatMap(ProviderResult.Success<List<ReleaseCandidate>>::value)
        .distinctBy(ReleaseCandidate::infoHash)
        .sortedWith(compareByDescending<ReleaseCandidate> { it.score }.thenByDescending { it.seeders })
    if (releases.isNotEmpty()) return ProviderResult.Success(releases)
    return results.filterIsInstance<ProviderResult.Failure>().firstOrNull() ?: ProviderResult.Empty
}
