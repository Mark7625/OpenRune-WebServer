package dev.openrune.api

enum class SseEventType {
    STATUS,
    ZIP_PROGRESS,
    DECODE_PROGRESS,
}

/** One server-sent event: `{"type": …, "data": …}` on the `/sse` stream. */
data class SseEvent(val type: SseEventType, val data: Any)
