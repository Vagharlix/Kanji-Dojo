package ua.syt0r.kanji.core.srs

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Anki's study order for intraday learning cards (cards whose next review is less than a
 * day after the previous one):
 * 1. learning cards that are due now come first (earliest due first)
 * 2. then other cards (new and review) in their queue order
 * 3. when only learning cards that aren't due yet remain, the earliest one is shown if it is
 *    due within the learn ahead limit; otherwise the session is done
 */
object AnkiQueueOrder {

    /** Due time of an intraday learning card, null for new cards and day-based reviews. */
    fun intradayDue(card: SrsCard): Instant? {
        val lastReview = card.lastReview ?: return null
        return if (card.interval < 1.days) lastReview + card.interval else null
    }

    /** True while an intraday learning card can't be shown yet, even with learn ahead. */
    fun isWaiting(card: SrsCard, now: Instant, learnAhead: Duration): Boolean {
        val due = intradayDue(card) ?: return false
        return due > now + learnAhead
    }

    /** Index of the card to show next, null when only waiting learning cards remain. */
    fun nextIndex(cards: List<SrsCard>, now: Instant, learnAhead: Duration): Int? {
        val dues = cards.map { intradayDue(it) }

        dues.withIndex()
            .filter { (_, due) -> due != null && due <= now }
            .minByOrNull { (_, due) -> due!! }
            ?.let { return it.index }

        dues.indexOfFirst { it == null }
            .takeIf { it >= 0 }
            ?.let { return it }

        return dues.withIndex()
            .filter { (_, due) -> due != null && due <= now + learnAhead }
            .minByOrNull { (_, due) -> due!! }
            ?.index
    }

}
