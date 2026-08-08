@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.Chapter

data class MediaChapter(val title: String, val startMs: Long, val endMs: Long)

internal enum class MediaSegmentKind(val actionLabel: String) {
    INTRO("Pular abertura"),
    RECAP("Pular resumo"),
    ENDING("Pular encerramento"),
    CREDITS("Pular créditos"),
    PREVIEW("Pular prévia")
}

internal data class MediaSegment(
    val chapter: MediaChapter,
    val kind: MediaSegmentKind
)

internal fun readMediaChapters(tracks: Tracks): List<MediaChapter> = tracks.groups
    .flatMap { group ->
        (0 until group.length).flatMap { index ->
            group.mediaTrackGroup.getFormat(index).metadata
                ?.getEntriesOfType(Chapter::class.java)
                ?.map { MediaChapter(it.title?.value.orEmpty(), it.startTimeMs, it.endTimeMs) }
                .orEmpty()
        }
    }
    .filter { it.startMs >= 0 && it.endMs > it.startMs }
    .distinctBy { Triple(it.startMs, it.endMs, it.title) }
    .sortedBy(MediaChapter::startMs)

internal fun introChapterAt(
    chapters: List<MediaChapter>,
    positionMs: Long
): MediaChapter? {
    return chapters.firstOrNull { chapter ->
        positionMs in chapter.startMs until chapter.endMs &&
            isIntroTitle(chapter.title)
    }
}

internal fun skippableSegmentAt(
    chapters: List<MediaChapter>,
    positionMs: Long
): MediaSegment? {
    return chapters.firstNotNullOfOrNull { chapter ->
        if (positionMs !in chapter.startMs until chapter.endMs) {
            return@firstNotNullOfOrNull null
        }

        segmentKind(chapter.title)?.let { kind ->
            MediaSegment(chapter, kind)
        }
    }
}

internal fun endingChapterAt(
    chapters: List<MediaChapter>,
    positionMs: Long
): MediaChapter? {
    return chapters.firstOrNull { chapter ->
        positionMs in chapter.startMs until chapter.endMs &&
            isEndingTitle(chapter.title)
    }
}

private fun isIntroTitle(value: String): Boolean {
    val title = value.trim().lowercase()

    return title.startsWith("opening") ||
        title.startsWith("intro") ||
        title.startsWith("abertura") ||
        title.matches(Regex("op\\s*\\d*")) ||
        title.contains("creditless op") ||
        title.contains("ncop") ||
        title.startsWith("オープニング")
}

private fun isEndingTitle(value: String): Boolean {
    val title = value.trim().lowercase()

    return title.startsWith("ending") ||
        title.startsWith("credits") ||
        title.startsWith("encerramento") ||
        title.startsWith("créditos") ||
        title.matches(Regex("ed\\s*\\d*")) ||
        title.contains("creditless ed") ||
        title.contains("nced") ||
        title.startsWith("エンディング")
}

private fun segmentKind(value: String): MediaSegmentKind? {
    val title = value.trim().lowercase()

    return when {
        isIntroTitle(title) -> MediaSegmentKind.INTRO
        title.startsWith("recap") ||
            title.startsWith("resumo") ||
            title.contains("previously") -> MediaSegmentKind.RECAP
        title.startsWith("preview") ||
            title.startsWith("next episode") ||
            title.startsWith("próximo episódio") ||
            title.contains("yokoku") ||
            title.contains("次回予告") -> MediaSegmentKind.PREVIEW
        title.startsWith("credits") ||
            title.startsWith("créditos") -> MediaSegmentKind.CREDITS
        isEndingTitle(title) -> MediaSegmentKind.ENDING
        else -> null
    }
}
