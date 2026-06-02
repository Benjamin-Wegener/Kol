package com.voiceassistant.llm

import com.voiceassistant.ModelConfig

/**
 * Accumulates streamed LLM tokens and flushes complete sentences to TTS.
 *
 * A sentence is flushed when:
 *  - a sentence-ending punctuation is detected (.?!。？！…), OR
 *  - the token count exceeds maxTokensBeforeFlush (avoids long waits for
 *    comma-heavy text), OR
 *  - done() is called (flushes any remaining text)
 */
class SentenceBatcher(
    private val onSentenceReady: (String) -> Unit,
    private val maxTokensBeforeFlush: Int = 40
) {
    private val buffer = StringBuilder()
    private var tokenCount = 0
    private val fullResponse = StringBuilder()

    fun addToken(token: String) {
        buffer.append(token)
        fullResponse.append(token)
        tokenCount++

        // Check for sentence-ending punctuation
        val lastChar = buffer.lastOrNull()
        val shouldFlush = lastChar != null && lastChar in ModelConfig.SENTENCE_ENDINGS
            || tokenCount >= maxTokensBeforeFlush

        if (shouldFlush) {
            flush()
        }
    }

    /** Call when LLM generation is done — flushes remaining text */
    fun done() {
        if (buffer.isNotBlank()) {
            flush()
        }
    }

    private fun flush() {
        val sentence = buffer.toString().trim()
        if (sentence.isNotEmpty()) {
            onSentenceReady(sentence)
        }
        buffer.clear()
        tokenCount = 0
    }

    fun reset() {
        buffer.clear()
        fullResponse.clear()
        tokenCount = 0
    }

    fun getFullResponse(): String = fullResponse.toString().trim()
}