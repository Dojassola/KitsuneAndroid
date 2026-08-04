@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.Chapter

data class MediaChapter(val title: String, val startMs: Long, val endMs: Long)

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

internal fun introChapterAt(chapters: List<MediaChapter>, positionMs: Long): MediaChapter? = chapters.firstOrNull { chapter ->
    positionMs in chapter.startMs until chapter.endMs && chapter.title.trim().lowercase().let { title ->
        title.startsWith("opening") || title.startsWith("intro") || title.startsWith("abertura") ||
            title.matches(Regex("op\\s*\\d*")) || title.contains("creditless op") ||
            title.contains("ncop") || title.startsWith("オープニング")
    }
}
