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
    private val firstFlushMinChars: Int = 10,
    private val firstFlushMaxTokens: Int = 4,
    private val maxTokensBeforeFlush: Int = 40,
    private val earlyFlushMinChars: Int = 45,
    private val earlyFlushHardCap: Int = 120,
    private val absoluteHardCap: Int = 180,
    private val minCharsBeforeFlush: Int = 20
) {
    private val tag = "SentenceBatcher"
    private val buffer = StringBuilder()
    private var tokenCount = 0
    private val fullResponse = StringBuilder()
    private var pendingFlush = false
    private var hasFlushed = false

    fun addToken(token: String) {
        buffer.append(token)
        fullResponse.append(token)
        tokenCount++
        Log.d(tag, "addToken token=${token.take(80)} tokenCount=$tokenCount bufferChars=${buffer.length}")

        val tokenEndsBoundary = token.isNotBlank() && (
            token.last() in ModelConfig.SENTENCE_ENDINGS ||
                token.last() in setOf(',', ';', ':', '–', '—') ||
                token.last().isWhitespace()
            )
        val tokenEndsClause = token.isNotBlank() && token.last() in ModelConfig.SENTENCE_ENDINGS
        val shouldFlush = (!hasFlushed
            && tokenCount >= firstFlushMaxTokens
            && buffer.length >= firstFlushMinChars
            && tokenEndsBoundary
            && buffer.any { it.isLetterOrDigit() })
            || tokenEndsClause
            || (tokenCount >= maxTokensBeforeFlush && tokenEndsBoundary)
            || (buffer.length >= earlyFlushMinChars
                && buffer.length >= minCharsBeforeFlush
                && tokenEndsBoundary)

        if (shouldFlush) {
            Log.d(tag, "flushing sentence chars=${buffer.length} fullChars=${fullResponse.length}")
            flush()
            pendingFlush = false
            return
        }
        if (tokenCount >= maxTokensBeforeFlush && !tokenEndsBoundary) {
            pendingFlush = true
        }
        if (!pendingFlush && buffer.length >= earlyFlushHardCap && !tokenEndsBoundary) {
            pendingFlush = true
        }
        if (pendingFlush && tokenEndsBoundary && buffer.length >= earlyFlushHardCap) {
            Log.d(tag, "pending flush resolved chars=${buffer.length} fullChars=${fullResponse.length}")
            flush()
            pendingFlush = false
            return
        }
        if (buffer.length >= absoluteHardCap && tokenEndsBoundary) {
            Log.d(tag, "absolute cap flush chars=${buffer.length} fullChars=${fullResponse.length}")
            flush()
            pendingFlush = false
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
        if (sentence.isNotEmpty() && sentence.any { it.isLetterOrDigit() }) {
            Log.d(tag, "sentenceReady=${sentence.take(120)}")
            onSentenceReady(sentence)
            hasFlushed = true
        }
        buffer.clear()
        tokenCount = 0
    }

    fun reset() {
        buffer.clear()
        fullResponse.clear()
        tokenCount = 0
        pendingFlush = false
        hasFlushed = false
    }

    fun getFullResponse(): String = fullResponse.toString().trim()
}
