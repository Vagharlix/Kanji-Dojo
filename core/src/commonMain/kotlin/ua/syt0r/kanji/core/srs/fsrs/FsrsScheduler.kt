package ua.syt0r.kanji.core.srs.fsrs

import kotlinx.datetime.Instant
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus.Learning
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus.New
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus.Relearning
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus.Review
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Again
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Easy
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Good
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Hard
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

const val DEFAULT_DESIRED_RETENTION = 0.9
// With the default FSRS-7 parameters intervals explode below ~85% (a new card answered
// Good would wait months) and collapse to seconds above ~97%, so the range is kept narrow
const val MIN_DESIRED_RETENTION = 0.85
const val MAX_DESIRED_RETENTION = 0.97

interface FsrsScheduler {

    fun newCard(): FsrsCard

    fun schedule(
        card: FsrsCard,
        reviewTime: Instant,
        desiredRetention: Double = DEFAULT_DESIRED_RETENTION
    ): FsrsAnswers

}

/**
 * Schedules cards with the FSRS-7 memory model. FSRS-7 models short-term memory itself,
 * so there are no fixed learning steps: every interval (including same-day ones) is the
 * time at which predicted recall probability drops to the desired retention.
 */
class DefaultFsrsScheduler(
    private val fsrs: Fsrs7 = Fsrs7(),
    private val maxInterval: Duration = 36500.days
) : FsrsScheduler {

    override fun newCard() = FsrsCard(
        status = New,
        params = FsrsCardParams.New,
        interval = Duration.ZERO,
        lapses = 0,
        repeats = 0
    )

    override fun schedule(
        card: FsrsCard,
        reviewTime: Instant,
        desiredRetention: Double
    ): FsrsAnswers {
        val retention = desiredRetention.coerceIn(MIN_DESIRED_RETENTION, MAX_DESIRED_RETENTION)

        fun nextState(rating: FsrsReviewRating): Fsrs7.MemoryState {
            return when (val params = card.params) {
                FsrsCardParams.New -> fsrs.initialState(rating)
                is FsrsCardParams.Existing -> {
                    val elapsedDays = (reviewTime - params.reviewTime)
                        .toDouble(DurationUnit.DAYS)
                        .coerceAtLeast(0.0)
                    fsrs.nextState(params.memoryState, elapsedDays, rating)
                }
            }
        }

        val states = FsrsReviewRating.entries.associateWith { nextState(it) }
        val rawIntervals = states.mapValues { (_, state) ->
            toInterval(fsrs.interval(state, retention))
        }

        // Keep the buttons ordered: again <= hard <= good <= easy
        val againInterval = rawIntervals.getValue(Again)
        val goodRaw = rawIntervals.getValue(Good)
        val hardInterval = maxOf(againInterval, minOf(rawIntervals.getValue(Hard), goodRaw))
        val goodInterval = goodRaw.atLeastAfter(hardInterval)
        val easyInterval = rawIntervals.getValue(Easy).atLeastAfter(goodInterval)

        fun answer(rating: FsrsReviewRating, interval: Duration): FsrsCard {
            val state = states.getValue(rating)
            val status = when {
                rating == Again -> when (card.status) {
                    New, Learning -> Learning
                    Review, Relearning -> Relearning
                }

                interval >= 1.days -> Review
                card.status == Review || card.status == Relearning -> Relearning
                else -> Learning
            }
            val isLapse = rating == Again && card.status == Review
            return FsrsCard(
                status = status,
                params = FsrsCardParams.Existing(
                    difficulty = state.difficulty,
                    stability = state.longStability,
                    shortStability = state.shortStability,
                    reviewTime = reviewTime
                ),
                interval = interval,
                lapses = if (isLapse) card.lapses + 1 else card.lapses,
                repeats = card.repeats + 1
            )
        }

        return FsrsAnswers(
            again = answer(Again, againInterval),
            hard = answer(Hard, hardInterval),
            good = answer(Good, goodInterval),
            easy = answer(Easy, easyInterval)
        )
    }

    /** Rounds sub-day intervals to whole minutes and longer ones to whole days. */
    private fun toInterval(days: Double): Duration {
        return when {
            days.isNaN() -> maxInterval
            days < 1.0 -> {
                val minutes = (days * 24 * 60).roundToLong()
                minutes.minutes.coerceAtLeast(1.minutes)
            }

            else -> days.roundToLong().days.coerceIn(1.days, maxInterval)
        }
    }

    /**
     * Makes sure that a better answer never gives a shorter interval; once intervals reach
     * whole days, a better answer gives at least one more day.
     */
    private fun Duration.atLeastAfter(other: Duration): Duration {
        return when {
            other >= 1.days -> maxOf(this, (other + 1.days).coerceAtMost(maxInterval))
            else -> maxOf(this, other)
        }
    }

}

/** Retrievability of a reviewed card at [time] according to FSRS-7. */
fun Fsrs7.retrievability(params: FsrsCardParams.Existing, time: Instant): Double {
    val elapsedDays = (time - params.reviewTime).toDouble(DurationUnit.DAYS)
    return retrievability(params.memoryState, elapsedDays)
}
