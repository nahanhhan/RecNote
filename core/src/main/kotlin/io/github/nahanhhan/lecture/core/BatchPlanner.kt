package io.github.nahanhhan.lecture.core

object BatchPlanner {
    fun plan(lessonId: String, revision: Int, course: String, glossary: List<String>,
        segments: List<Segment>, photos: List<Photo>, maxChars: Int = 6000, maxPhotos: Int = 6,
        includePhotos: Boolean = true, maxSegments: Int = 40): List<Batch> {
        require(maxChars > 0 && maxPhotos > 0 && maxSegments > 0)
        data class Event(val time: Long, val segment: Segment? = null, val photo: Photo? = null)
        val events = segments.flatMap { s -> s.text.chunked(maxChars).map { Event(s.startMs, s.copy(text = it)) } } +
            (if (includePhotos) photos.map { Event(it.audioTimeMs, photo = it) } else emptyList())
        val result = mutableListOf<Batch>()
        val currentSegments = mutableListOf<Segment>()
        val currentPhotos = mutableListOf<Photo>()
        var chars = 0
        fun flush() {
            if (currentSegments.isEmpty() && currentPhotos.isEmpty()) return
            result += Batch(lessonId, "batch_${(result.size + 1).toString().padStart(3, '0')}", revision, course,
                glossary, currentSegments.toList(), currentPhotos.mapIndexed { index, photo -> photo.copy(inputImageIndex = index) })
            currentSegments.clear(); currentPhotos.clear(); chars = 0
        }
        events.sortedBy { it.time }.forEach { event ->
            if ((event.segment != null && (chars + event.segment.text.length > maxChars || currentSegments.size == maxSegments)) ||
                (event.photo != null && currentPhotos.size == maxPhotos)) flush()
            event.segment?.let { currentSegments += it; chars += it.text.length }
            event.photo?.let { currentPhotos += it }
        }
        flush()
        return result
    }
}
