package com.sakurafubuki.yume.feature.player.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.SimpleSubtitleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder

@UnstableApi
class AssSubtitleDecoder(
    private val assHandle: Long,
    format: Format,
) : SimpleSubtitleDecoder("AssSubtitleDecoder") {

    init {
        val header = format.initializationData.firstOrNull()
        if (header != null) {
            AssRenderer.nativeCreateAssTrack(assHandle)
            AssRenderer.nativeProcessAssChunk(assHandle, header, header.size, 0, 0)
            AssSubtitleState.embeddedTrackActive = true
        }
    }

    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        if (reset) {
            AssRenderer.nativeFlushEvents(assHandle)
        }

        val (timeMs, durationMs) = parseAssTiming(data, length)
        AssRenderer.nativeProcessAssChunk(assHandle, data, length, timeMs, durationMs)

        return EmptySubtitle
    }

    override fun release() {
        super.release()
        AssSubtitleState.embeddedTrackActive = false
        AssRenderer.nativeProcessAssChunk(assHandle, ByteArray(0), 0, 0, 0)
    }

    companion object {
        private val TIMING_REGEX = Regex(
            """^Dialogue:\s*\d+,\s*(\d+:\d{2}:\d{2}\.\d{2})\s*,\s*(\d+:\d{2}:\d{2}\.\d{2})\s*,""",
        )

        internal fun parseAssTiming(data: ByteArray, length: Int): Pair<Long, Long> {
            val line = String(data, 0, length, Charsets.UTF_8)
            val match = TIMING_REGEX.find(line) ?: return 0L to 0L
            val startMs = assTimestampToMs(match.groupValues[1])
            val endMs = assTimestampToMs(match.groupValues[2])
            return startMs to (endMs - startMs).coerceAtLeast(0)
        }

        private fun assTimestampToMs(timestamp: String): Long {
            val parts = timestamp.split(":", ".")
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val s = parts[2].toLong()
            val cs = parts[3].toLong()
            return h * 3600000 + m * 60000 + s * 1000 + cs * 10
        }
    }
}

@UnstableApi
private object EmptySubtitle : Subtitle {
    override fun getNextEventTimeIndex(timeUs: Long) = C.INDEX_UNSET
    override fun getEventTimeCount() = 0
    override fun getEventTime(index: Int): Long = throw IndexOutOfBoundsException()
    override fun getCues(timeUs: Long) = emptyList<Cue>()
}

@UnstableApi
class AssSubtitleDecoderFactory : SubtitleDecoderFactory {

    private val defaultFactory = SubtitleDecoderFactory.DEFAULT

    override fun supportsFormat(format: Format): Boolean {
        val mime = format.sampleMimeType ?: ""
        if (mime.contains("ass", ignoreCase = true) || mime.contains("ssa", ignoreCase = true)) {
            return false
        }
        return defaultFactory.supportsFormat(format)
    }

    override fun createDecoder(format: Format): SubtitleDecoder {
        val mime = format.sampleMimeType ?: ""
        if (mime.contains("ass", ignoreCase = true) || mime.contains("ssa", ignoreCase = true)) {
            return AssSubtitleDecoder(AssSubtitleState.getOrCreateHandle(), format)
        }
        return defaultFactory.createDecoder(format)
    }
}
