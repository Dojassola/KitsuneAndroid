@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.concurrent.atomic.AtomicLong

private const val MAX_BUFFERED_SUBTITLE_GROUPS = 4_000
private const val SUBTITLE_LOOKAHEAD_US = 2 * 60 * 1_000_000L

internal data class TimedSubtitleCues(
    val language: String?,
    val label: String?,
    val startTimeUs: Long,
    val durationUs: Long,
    val cues: List<Cue>
)

internal class SubtitleCueTimeline {
    private val groups = mutableListOf<TimedSubtitleCues>()
    private val groupKeys = mutableSetOf<Pair<Long, String>>()

    @Synchronized
    fun add(language: String?, label: String?, cues: CuesWithTiming) {
        if (cues.startTimeUs == C.TIME_UNSET || cues.cues.isEmpty()) {
            return
        }

        val group = TimedSubtitleCues(
            language = language,
            label = label,
            startTimeUs = cues.startTimeUs,
            durationUs = cues.durationUs,
            cues = cues.cues
        )
        val groupKey = group.startTimeUs to subtitleCueKey(group.cues)
        if (!groupKeys.add(groupKey)) {
            return
        }

        groups.add(group)
        if (groups.size > MAX_BUFFERED_SUBTITLE_GROUPS) {
            val removed = groups.removeAt(0)
            groupKeys.remove(removed.startTimeUs to subtitleCueKey(removed.cues))
        }
    }

    @Synchronized
    fun upcoming(sourceLanguage: String, positionUs: Long, limit: Int = 24): List<List<Cue>> {
        val endUs = positionUs + SUBTITLE_LOOKAHEAD_US
        return groups.asSequence()
            .filter { group ->
                group.startTimeUs <= endUs && (
                    group.startTimeUs >= positionUs - 2_000_000L ||
                        group.isActiveAt(positionUs)
                    )
            }
            .filter { group ->
                subtitleTranslationLanguage(group.language, group.label) == sourceLanguage
            }
            .sortedBy(TimedSubtitleCues::startTimeUs)
            .distinctBy { group -> subtitleCueKey(group.cues) }
            .take(limit)
            .map(TimedSubtitleCues::cues)
            .toList()
    }

    private fun TimedSubtitleCues.isActiveAt(positionUs: Long): Boolean {
        if (durationUs == C.TIME_UNSET || durationUs <= 0) {
            return false
        }

        return startTimeUs <= positionUs && positionUs <= startTimeUs + durationUs
    }
}

internal class SubtitleTiming(initialOffsetMs: Long) {
    private val offsetUs = AtomicLong(initialOffsetMs * 1_000)

    fun setOffsetMs(value: Long) {
        offsetUs.set(value * 1_000)
    }

    fun offsetUs(): Long {
        return offsetUs.get()
    }
}

internal class OffsetSubtitleParserFactory(
    private val timing: SubtitleTiming,
    private val timeline: SubtitleCueTimeline,
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory()
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(format)
    }

    override fun create(format: Format): SubtitleParser {
        return OffsetSubtitleParser(
            delegate = delegate.create(format),
            timing = timing,
            timeline = timeline,
            format = format,
            cueReplacementBehavior = delegate.getCueReplacementBehavior(format)
        )
    }
}

private class OffsetSubtitleParser(
    private val delegate: SubtitleParser,
    private val timing: SubtitleTiming,
    private val timeline: SubtitleCueTimeline,
    private val format: Format,
    private val cueReplacementBehavior: Int
) : SubtitleParser {
    override fun getCueReplacementBehavior(): Int {
        return cueReplacementBehavior
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        delegate.parse(data, offset, length, outputOptions) { cues ->
            val shiftedStart = if (cues.startTimeUs == C.TIME_UNSET) {
                C.TIME_UNSET
            } else {
                (cues.startTimeUs + timing.offsetUs()).coerceAtLeast(0)
            }
            val shiftedCues = CuesWithTiming(
                cues.cues,
                shiftedStart,
                cues.durationUs
            )
            timeline.add(format.language, format.label, shiftedCues)
            output.accept(shiftedCues)
        }
    }

    override fun reset() {
        delegate.reset()
    }
}
