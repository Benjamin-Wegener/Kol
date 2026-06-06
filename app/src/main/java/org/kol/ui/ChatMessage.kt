package org.kol.ui

/**
 * Describes chat message values.
 */
data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)
