@file:androidx.media3.common.util.UnstableApi

package com.kitsuneandroid

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.concurrent.atomic.AtomicLong

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
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory()
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(format)
    }

    override fun create(format: Format): SubtitleParser {
        return OffsetSubtitleParser(delegate.create(format), timing)
    }
}

private class OffsetSubtitleParser(
    private val delegate: SubtitleParser,
    private val timing: SubtitleTiming
) : SubtitleParser {
    override fun getCueReplacementBehavior(): Int {
        return delegate.cueReplacementBehavior
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
            output.accept(
                CuesWithTiming(
                    cues.cues,
                    shiftedStart,
                    cues.durationUs
                )
            )
        }
    }

    override fun reset() {
        delegate.reset()
    }
}
