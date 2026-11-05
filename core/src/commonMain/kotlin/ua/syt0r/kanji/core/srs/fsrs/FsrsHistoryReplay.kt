package ua.syt0r.kanji.core.srs.fsrs

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** A single logged review, as stored in review history. */
data class FsrsReviewLog(
    val timestamp: Instant,
    val grade: Int
)

/** A review together with what FSRS knew right before it happened. */
data class FsrsReplayedReview(
    val log: FsrsReviewLog,
    /** Null for the very first review of a card. */
    val elapsed: Duration?,
    /** Predicted recall probability right before the review, null for the first review. */
    val predictedRetrievability: Double?,
    /** Card state right before the review. */
    val cardBefore: FsrsCard,
    /** Card state right after the review. */
    val cardAfter: FsrsCard
)

/**
 * Rebuilds FSRS-7 card states by replaying a card's review log from scratch.
 */
class FsrsHistoryReplay(
    private val fsrs: Fsrs7 = Fsrs7(),
    private val scheduler: FsrsScheduler = DefaultFsrsScheduler(fsrs)
) {

    fun replay(
        logs: List<FsrsReviewLog>,
        desiredRetention: Double = DEFAULT_DESIRED_RETENTION
    ): List<FsrsReplayedReview> {
        val result = ArrayList<FsrsReplayedReview>(logs.size)
        var card = scheduler.newCard()
        logs.sortedBy { it.timestamp }.forEach { log ->
            val rating = FsrsReviewRating.entries.firstOrNull { it.grade == log.grade }
                ?: return@forEach
            val params = card.params as? FsrsCardParams.Existing
            val elapsed = params?.let { log.timestamp - it.reviewTime }
            val predictedRetrievability = params?.let {
                fsrs.retrievability(
                    it.memoryState,
                    elapsed!!.toDouble(DurationUnit.DAYS)
                )
            }
            val answers = scheduler.schedule(card, log.timestamp, desiredRetention)
            val next = when (rating) {
                FsrsReviewRating.Again -> answers.again
                FsrsReviewRating.Hard -> answers.hard
                FsrsReviewRating.Good -> answers.good
                FsrsReviewRating.Easy -> answers.easy
            }
            result.add(
                FsrsReplayedReview(
                    log = log,
                    elapsed = elapsed,
                    predictedRetrievability = predictedRetrievability,
                    cardBefore = card,
                    cardAfter = next
                )
            )
            card = next
        }
        return result
    }

}

/**
 * Splits a card's review logs at its progress resets: FSRS treats every reset as a fresh
 * start, so each segment is an independent learning sequence. The last segment is the
 * card's current one. Segments can be empty (e.g. reset and not reviewed since).
 */
fun splitAtResets(logs: List<FsrsReviewLog>, resets: List<Instant>): List<List<FsrsReviewLog>> {
    val sorted = logs.sortedBy { it.timestamp }
    if (resets.isEmpty()) return listOf(sorted)
    val boundaries = resets.sorted()
    return (0..boundaries.size).map { index ->
        val from = boundaries.getOrNull(index - 1)
        val to = boundaries.getOrNull(index)
        sorted.filter { log ->
            (from == null || log.timestamp > from) && (to == null || log.timestamp <= to)
        }
    }
}

/** Reviews since the card's last reset, the ones that define its current memory state. */
fun reviewsSinceLastReset(logs: List<FsrsReviewLog>, resets: List<Instant>): List<FsrsReviewLog> {
    val lastReset = resets.maxOrNull() ?: return logs.sortedBy { it.timestamp }
    return logs.filter { it.timestamp > lastReset }.sortedBy { it.timestamp }
}
