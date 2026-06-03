package com.voiceassistant.llm

import android.util.Log
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
    private val tag = "SentenceBatcher"
    private val buffer = StringBuilder()
    private var tokenCount = 0
    private val fullResponse = StringBuilder()

    fun addToken(token: String) {
        buffer.append(token)
        fullResponse.append(token)
        tokenCount++
        Log.d(tag, "addToken token=${token.take(80)} tokenCount=$tokenCount bufferChars=${buffer.length}")

        // Check for sentence-ending punctuation
        val lastChar = buffer.lastOrNull()
        val shouldFlush = lastChar != null && lastChar in ModelConfig.SENTENCE_ENDINGS
            || tokenCount >= maxTokensBeforeFlush

        if (shouldFlush) {
            Log.d(tag, "flushing sentence chars=${buffer.length} fullChars=${fullResponse.length}")
            flush()
        }
    }

    /** Call when LLM generation is done — flushes remaining text */
    fun done() {
        if (buffer.isNotBlank()) {
            Log.d(tag, "done() flushing tail chars=${buffer.length}")
            flush()
        }
    }

    private fun flush() {
        val sentence = buffer.toString().trim()
        if (sentence.isNotEmpty()) {
            Log.d(tag, "sentenceReady=${sentence.take(120)}")
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
