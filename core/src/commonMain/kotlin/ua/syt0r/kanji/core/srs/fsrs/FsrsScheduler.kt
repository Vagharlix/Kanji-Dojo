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
const val MIN_DESIRED_RETENTION = 0.70
const val MAX_DESIRED_RETENTION = 0.99

// With the default FSRS-7 parameters intervals explode below ~85% (a new card answered
// Good would wait months) and collapse to seconds above ~97%; the UI warns outside this range
const val RECOMMENDED_MIN_DESIRED_RETENTION = 0.85
const val RECOMMENDED_MAX_DESIRED_RETENTION = 0.97

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
 * so by default there are no fixed learning steps: every interval (including same-day
 * ones) is the time at which predicted recall probability drops to the desired retention.
 *
 * Optional Anki-style [learningSteps] / [relearningSteps] override the intervals of new
 * and lapsed cards until they graduate; memory states are still updated by FSRS on every
 * review.
 */
class DefaultFsrsScheduler(
    private val fsrs: Fsrs7 = Fsrs7(),
    private val maxInterval: Duration = 36500.days,
    private val learningSteps: List<Duration> = emptyList(),
    private val relearningSteps: List<Duration> = emptyList()
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

        fun fsrsPlan(rating: FsrsReviewRating, interval: Duration): Plan {
            // Like Anki: a passed review card stays a review card, at least one day away
            if (card.status == Review && rating != Again) {
                return Plan(interval.coerceIn(1.days, maxInterval), Review, step = 0)
            }
            val status = when {
                rating == Again -> when (card.status) {
                    New, Learning -> Learning
                    Review, Relearning -> Relearning
                }

                interval >= 1.days -> Review
                card.status == Review || card.status == Relearning -> Relearning
                else -> Learning
            }
            return Plan(interval, status, step = 0)
        }

        val fsrsPlans = mapOf(
            Again to fsrsPlan(Again, againInterval),
            Hard to fsrsPlan(Hard, hardInterval),
            Good to fsrsPlan(Good, goodInterval),
            Easy to fsrsPlan(Easy, easyInterval)
        )

        val plans: Map<FsrsReviewRating, Plan> = when {
            // Anki-style (re)learning steps
            card.status != Review && stepsFor(card.status).isNotEmpty() -> {
                val steps = stepsFor(card.status)
                val stepStatus = if (card.status == Relearning) Relearning else Learning
                val index = if (card.status == New) 0 else card.step.coerceIn(0, steps.lastIndex)
                fun graduated(rating: FsrsReviewRating) = rawIntervals.getValue(rating)
                    .coerceIn(1.days, maxInterval)

                val good = when {
                    index + 1 < steps.size -> Plan(steps[index + 1], stepStatus, index + 1)
                    else -> Plan(graduated(Good), Review, 0)
                }
                val easyInterval = when (good.status) {
                    Review -> graduated(Easy).atLeastAfter(good.interval)
                    else -> graduated(Easy)
                }
                mapOf(
                    Again to Plan(steps[0], stepStatus, 0),
                    Hard to Plan(hardStepDelay(steps, index), stepStatus, index),
                    Good to good,
                    Easy to Plan(easyInterval, Review, 0)
                )
            }

            // Lapse goes into relearning steps
            card.status == Review && relearningSteps.isNotEmpty() -> fsrsPlans +
                    (Again to Plan(relearningSteps[0], Relearning, 0))

            else -> fsrsPlans
        }

        fun answer(rating: FsrsReviewRating): FsrsCard {
            val state = states.getValue(rating)
            val plan = plans.getValue(rating)
            val isLapse = rating == Again && card.status == Review
            return FsrsCard(
                status = plan.status,
                params = FsrsCardParams.Existing(
                    difficulty = state.difficulty,
                    stability = state.longStability,
                    shortStability = state.shortStability,
                    reviewTime = reviewTime
                ),
                interval = plan.interval,
                lapses = if (isLapse) card.lapses + 1 else card.lapses,
                repeats = card.repeats + 1,
                step = plan.step
            )
        }

        return FsrsAnswers(
            again = answer(Again),
            hard = answer(Hard),
            good = answer(Good),
            easy = answer(Easy)
        )
    }

    private class Plan(val interval: Duration, val status: FsrsCardStatus, val step: Int)

    private fun stepsFor(status: FsrsCardStatus): List<Duration> = when (status) {
        New, Learning -> learningSteps
        Relearning -> relearningSteps
        Review -> emptyList()
    }

    /** Anki: on the first step Hard is between the first two steps, later it repeats the step. */
    private fun hardStepDelay(steps: List<Duration>, index: Int): Duration = when {
        index > 0 -> steps[index]
        steps.size > 1 -> (steps[0] + steps[1]) / 2
        else -> minOf(steps[0] * 1.5, steps[0] + 1.days)
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
