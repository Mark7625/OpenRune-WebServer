package dev.openrune.server

/**
 * Enum for SSE event types
 */
enum class SseEventType {
    STATUS,
    ZIP_PROGRESS,
    DECODE_PROGRESS
}

/**
 * Generic SSE event wrapper containing the event type and serialized data
 */
data class SseEvent(
    val type: SseEventType,
    val data: Any
)
