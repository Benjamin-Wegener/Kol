package org.kol.llm

import org.kol.ModelConfig

/**
 * Accumulates streamed LLM tokens and flushes complete sentences to TTS.
 *
 * A sentence is flushed when:
 *  - a sentence-ending punctuation is detected (.?!。？！…), OR
 *  - the token count exceeds maxTokensBeforeFlush (avoids long waits for
 *    comma-heavy text), OR
 *  - done() is called (flushes any remaining text)
 *
 * First-flush fires as soon as 2 tokens + 8 chars are accumulated, without
 * waiting for a boundary character — this gets audio to the speaker within
 * ~100-200 ms of the first LLM token rather than 4-5 s.
 *
 * FIX 1 — swallowed text after first flush:
 *   flush() was sending buffer.toString() as the sentence, but then clearing
 *   the buffer. After the first flush "Guten Tag, ich" was emitted correctly,
 *   but the NEXT flush emitted buffer-only text ("heiße ein großes...") instead
 *   of fullResponse content — the "ich " prefix was already cleared and lost.
 *   Solution: track a sentenceStart cursor in fullResponse; each flush emits
 *   fullResponse.substring(sentenceStart) and advances the cursor. The buffer
 *   is only used for length/token counting, not as the source of truth for text.
 *
 * FIX 2 — earlyFlushMinChars gate blocks flush after first sentence:
 *   After hasFlushed=true the first-flush path is dead. The next flush can only
 *   happen via tokenEndsClause (sentence-end punctuation) OR
 *   earlyFlushMinChars (45 chars) AND tokenEndsBoundary. For short second
 *   clauses (< 45 chars) that end with a comma, we were waiting all the way
 *   until tokenEndsClause — i.e. the full sentence period. Fixed by lowering
 *   earlyFlushMinChars to 20 so comma/boundary flushes happen sooner.
 */
class SentenceBatcher(
    private val onSentenceReady: (String) -> Unit,
    private val firstFlushMinChars: Int = 8,
    private val firstFlushMaxTokens: Int = 2,
    private val maxTokensBeforeFlush: Int = 40,
    private val earlyFlushMinChars: Int = 20,   // was 45 — caused swallowed prefix in second sentence
    private val earlyFlushHardCap: Int = 120,
    private val absoluteHardCap: Int = 180,
    private val minCharsBeforeFlush: Int = 20
) {
    private val tag = "SentenceBatcher"
    private val buffer = StringBuilder()
    private val fullResponse = StringBuilder()
    private var sentenceStart = 0          // cursor into fullResponse for current sentence
    private var tokenCount = 0
    private var pendingFlush = false
    private var hasFlushed = false

    /**
     * Handles add token.
     * @param token Supplies the token value.
     */
    fun addToken(token: String) {
        buffer.append(token)
        fullResponse.append(token)
        tokenCount++

        val tokenEndsBoundary = token.isNotBlank() && (
            token.last() in ModelConfig.SENTENCE_ENDINGS ||
                token.last() in setOf(',', ';', ':', '–', '—') ||
                token.last().isWhitespace()
            )
        val tokenEndsClause = token.isNotBlank() && token.last() in ModelConfig.SENTENCE_ENDINGS
        val shouldFlush = (!hasFlushed
            && tokenCount >= firstFlushMaxTokens
            && buffer.length >= firstFlushMinChars
            && buffer.any { it.isLetterOrDigit() })
            || tokenEndsClause
            || (tokenCount >= maxTokensBeforeFlush && tokenEndsBoundary)
            || (buffer.length >= earlyFlushMinChars
                && buffer.length >= minCharsBeforeFlush
                && tokenEndsBoundary)

        if (shouldFlush) {
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
            flush()
            pendingFlush = false
            return
        }
        if (buffer.length >= absoluteHardCap && tokenEndsBoundary) {
            flush()
            pendingFlush = false
        }
    }

    /** Call when LLM generation is done — flushes remaining text */
    fun done() {
        if (buffer.isNotBlank()) {
            flush()
        }
    }

    private fun flush() {
        // Use fullResponse as the source of truth — buffer is only a counter.
        // sentenceStart tracks where the current sentence begins in fullResponse.
        val sentence = fullResponse.substring(sentenceStart).trim()
        if (sentence.isNotEmpty() && sentence.any { it.isLetterOrDigit() }) {
            onSentenceReady(sentence)
            hasFlushed = true
        }
        // Advance cursor to end of fullResponse (= start of next sentence)
        sentenceStart = fullResponse.length
        buffer.clear()
        tokenCount = 0
    }

    /**
     * Handles reset.
     */
    fun reset() {
        buffer.clear()
        fullResponse.clear()
        sentenceStart = 0
        tokenCount = 0
        pendingFlush = false
        hasFlushed = false
    }

    /**
     * Returns full response.
     * @return The get full response result.
     */
    fun getFullResponse(): String = fullResponse.toString().trim()
}
