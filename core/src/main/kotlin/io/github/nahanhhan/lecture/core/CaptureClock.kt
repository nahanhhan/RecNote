package io.github.nahanhhan.lecture.core

/** A short recording-clock history maps camera EXIF wall time back to actual recorded samples. */
class CaptureClock {
    private val anchors = ArrayDeque<Pair<Long, Long>>()
    @Synchronized fun record(epochMs: Long, samples: Long) {
        if (anchors.isNotEmpty() && epochMs < anchors.last().first) anchors.clear()
        if (anchors.isEmpty() || epochMs - anchors.last().first >= 200) anchors.addLast(epochMs to samples)
        while (anchors.isNotEmpty() && epochMs - anchors.first().first > 30 * 60 * 1000) anchors.removeFirst()
    }
    @Synchronized fun samplesAt(epochMs: Long, fallback: Long): Long =
        anchors.lastOrNull { it.first <= epochMs }?.second ?: fallback
    @Synchronized fun clear() = anchors.clear()
}
