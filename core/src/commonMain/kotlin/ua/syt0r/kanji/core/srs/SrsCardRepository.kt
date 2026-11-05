package ua.syt0r.kanji.core.srs

import kotlinx.datetime.Instant
import ua.syt0r.kanji.core.user_data.database.FsrsCardRepository
import ua.syt0r.kanji.core.user_data.database.ObservableRepository

interface SrsCardRepository : ObservableRepository {
    suspend fun get(key: SrsCardKey): SrsCard?
    suspend fun getAll(): Map<SrsCardKey, SrsCard>
    suspend fun update(key: SrsCardKey, card: SrsCard)
    suspend fun updateAll(cards: Map<SrsCardKey, SrsCard>)
    suspend fun reset(keys: Collection<SrsCardKey>, time: Instant)
    suspend fun getResets(): Map<SrsCardKey, List<Instant>>
}

class DefaultSrsCardRepository(
    private val fsrsCardRepository: FsrsCardRepository
) : SrsCardRepository,
    ObservableRepository by fsrsCardRepository {

    override suspend fun get(key: SrsCardKey): SrsCard? {
        return fsrsCardRepository.get(key)?.let { SrsCard(it) }
    }

    override suspend fun getAll(): Map<SrsCardKey, SrsCard> {
        return fsrsCardRepository.getAll().mapValues { SrsCard(it.value) }
    }

    override suspend fun update(key: SrsCardKey, card: SrsCard) {
        fsrsCardRepository.update(key, card.fsrsCard)
    }

    override suspend fun reset(keys: Collection<SrsCardKey>, time: Instant) {
        fsrsCardRepository.reset(keys, time)
    }

    override suspend fun getResets(): Map<SrsCardKey, List<Instant>> {
        return fsrsCardRepository.getResets()
    }

    override suspend fun updateAll(cards: Map<SrsCardKey, SrsCard>) {
        fsrsCardRepository.updateAll(cards.mapValues { it.value.fsrsCard })
    }

}
/**
 * First review of the card's current learning sequence: after a progress reset the card is
 * new again, so reviews before the last reset don't count.
 */
suspend fun ua.syt0r.kanji.core.user_data.database.ReviewHistoryRepository.firstReviewSinceReset(
    key: SrsCardKey,
    card: SrsCard?,
    firstReview: Instant?,
    resets: List<Instant>?
): Instant? {
    val lastReset = resets?.maxOrNull() ?: return firstReview
    if (firstReview != null && firstReview > lastReset) return firstReview
    if (card == null) return null
    return getFirstReviewTimeAfter(key.itemKey, key.practiceType, lastReset)
}
