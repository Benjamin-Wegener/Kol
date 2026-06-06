package org.kol.tts

/**
 * Represents the tts turn coordinator component.
 */
internal class TtsTurnCoordinator {

    /**
     * Describes turn values.
     */
    data class Turn(
        val turnId: Long,
        val ticket: Long,
        val language: String,
        val chunksQueued: Int
    )

    private var nextTicket = 0L
    private var currentTurn: Turn? = null

    @Synchronized
    /**
     * Returns begin.
     * @param turnId Supplies the turn id value.
     * @param language Supplies the language value.
     * @return The begin result.
     */
    fun begin(turnId: Long, language: String): Turn {
        nextTicket += 1
        return Turn(
            turnId = turnId,
            ticket = nextTicket,
            language = language,
            chunksQueued = 0
        ).also { currentTurn = it }
    }

    @Synchronized
    /**
     * Updates language.
     * @param language Supplies the language value.
     * @return The update language result.
     */
    fun updateLanguage(language: String): Turn? {
        val current = currentTurn ?: return null
        return current.copy(language = language).also { currentTurn = it }
    }

    @Synchronized
    /**
     * Returns mark chunk queued.
     * @return The mark chunk queued result.
     */
    fun markChunkQueued(): Turn? {
        val current = currentTurn ?: return null
        return current.copy(chunksQueued = current.chunksQueued + 1).also { currentTurn = it }
    }

    @Synchronized
    /**
     * Returns current.
     * @return The current result.
     */
    fun current(): Turn? = currentTurn

    @Synchronized
    /**
     * Returns whether current.
     * @param ticket Supplies the ticket value.
     * @return The is current result.
     */
    fun isCurrent(ticket: Long): Boolean = currentTurn?.ticket == ticket

    @Synchronized
    /**
     * Returns needs full response fallback.
     * @param responseText Supplies the response text value.
     * @return The needs full response fallback result.
     */
    fun needsFullResponseFallback(responseText: String): Boolean {
        return currentTurn?.chunksQueued == 0 && responseText.isNotBlank()
    }

    @Synchronized
    /**
     * Handles invalidate.
     */
    fun invalidate() {
        nextTicket += 1
        currentTurn = null
    }
}
