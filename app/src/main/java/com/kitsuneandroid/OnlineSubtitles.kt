package com.kitsuneandroid

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal data class SubtitleSearchRequest(
    val title: String,
    val episode: Int,
    val language: String,
    val videoFile: File?,
    val videoName: String?,
    val videoFps: Float?
)

internal interface OnlineSubtitleProvider {
    val name: String

    fun download(
        context: Context,
        request: SubtitleSearchRequest,
        apiKey: String
    ): RemoteSubtitle
}

private object OpenSubtitlesProvider : OnlineSubtitleProvider {
    override val name = "OpenSubtitles"

    override fun download(
        context: Context,
        request: SubtitleSearchRequest,
        apiKey: String
    ): RemoteSubtitle {
        return OpenSubtitles.downloadSubtitle(
            context = context,
            title = request.title,
            episode = request.episode,
            apiKey = apiKey,
            language = request.language,
            videoFile = request.videoFile,
            videoName = request.videoName,
            videoFps = request.videoFps
        )
    }
}

internal object OnlineSubtitles {
    fun download(
        context: Context,
        request: SubtitleSearchRequest,
        settings: SubtitleProviderSettings
    ): RemoteSubtitle {
        val providers = buildList {
            if (settings.openSubtitlesEnabled && settings.openSubtitlesApiKey.isNotBlank()) {
                add(OpenSubtitlesProvider to settings.openSubtitlesApiKey)
            }
            if (settings.subDlEnabled && settings.subDlApiKey.isNotBlank()) {
                add(SubDlProvider to settings.subDlApiKey)
            }
        }
        if (providers.isEmpty()) {
            throw IOException("Ative e configure um provedor de legendas no perfil.")
        }

        val failures = mutableListOf<String>()
        for ((provider, apiKey) in providers) {
            try {
                return provider.download(context, request, apiKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                failures += "${provider.name}: ${failure.message ?: "falhou"}"
            }
        }

        throw IOException(
            "Nenhum provedor encontrou uma legenda. ${failures.joinToString(" • ")}"
        )
    }
}
