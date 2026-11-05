package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import ua.syt0r.kanji.core.srs.LetterPracticeType
import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.VocabPracticeType
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.FsrsCard
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardParams
import ua.syt0r.kanji.core.srs.fsrs.FsrsHistoryReplay
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewLog
import ua.syt0r.kanji.core.srs.fsrs.retrievability
import ua.syt0r.kanji.core.srs.fsrs.splitAtResets
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryItem
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit

/**
 * Computes FSRS based statistics (true retention, forecast, memory state distributions)
 * from review history and current card states. Pure logic, no IO.
 */
class FsrsStatsCalculator(
    private val fsrs: Fsrs7 = Fsrs7()
) {

    class Input(
        val reviews: List<ReviewHistoryItem>,
        val cards: Map<SrsCardKey, FsrsCard>,
        val now: Instant,
        val today: LocalDate,
        val toSrsDate: (Instant) -> LocalDate,
        val desiredRetention: Double,
        val forecastDays: Int = 30,
        /** Progress reset times per card; each reset starts a new learning sequence */
        val resets: Map<SrsCardKey, List<Instant>> = emptyMap()
    )

    private class CountedReview(
        val practiceType: Long,
        val date: LocalDate,
        val elapsedDays: Double,
        val passed: Boolean,
        val predicted: Double
    )

    fun calculate(input: Input): Map<StatsFilter, FsrsStats> {
        val countedReviews = countedReviews(input)
        return StatsFilter.entries.associateWith { filter ->
            val matches = filter.predicate()
            calculateForFilter(
                input = input,
                cards = input.cards.filterKeys { matches(it.practiceType) },
                countedReviews = countedReviews.filter { matches(it.practiceType) }
            )
        }
    }

    fun heatmap(
        reviews: List<ReviewHistoryItem>,
        today: LocalDate,
        toSrsDate: (Instant) -> LocalDate
    ): HeatmapSummary {
        val dateToCount = reviews.groupingBy { toSrsDate(it.timestamp) }.eachCount()
        if (dateToCount.isEmpty()) {
            return HeatmapSummary(emptyMap(), 0, 0.0, 0, 0)
        }

        val studiedDates = dateToCount.keys.sorted()
        val firstDate = studiedDates.first()
        val totalDays = (firstDate.daysUntil(today) + 1).coerceAtLeast(1)

        var longest = 0
        var running = 0
        var previous: LocalDate? = null
        studiedDates.forEach { date ->
            running = if (previous != null && previous!!.plus(1, DateTimeUnit.DAY) == date) {
                running + 1
            } else 1
            longest = maxOf(longest, running)
            previous = date
        }

        // Streak continues through today even if today's reviews aren't done yet
        var current = 0
        var cursor = if (dateToCount.containsKey(today)) today else today.minus(1, DateTimeUnit.DAY)
        while (dateToCount.containsKey(cursor)) {
            current++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }

        return HeatmapSummary(
            dateToReviewCount = dateToCount,
            dailyAverage = (reviews.size.toDouble() / studiedDates.size).let { kotlin.math.round(it).toInt() },
            daysLearnedRatio = studiedDates.size.toDouble() / totalDays,
            longestStreak = longest,
            currentStreak = current
        )
    }

    /**
     * Reviews that count for true retention: every review of a card except its first one,
     * and only the first review on a given day (same-day repeats are excluded, like Anki's
     * true retention).
     */
    private fun countedReviews(input: Input): List<CountedReview> {
        val replay = FsrsHistoryReplay(fsrs)
        return input.reviews
            .groupBy { it.key to it.practiceType }
            .flatMap { (key, items) ->
                val logs = items.map { FsrsReviewLog(it.timestamp, it.grade) }
                val resets = input.resets[SrsCardKey(key.first, key.second)].orEmpty()
                val replayed = splitAtResets(logs, resets).flatMap { replay.replay(it) }
                replayed.mapNotNull { review ->
                    val previousReview = (review.cardBefore.params as? FsrsCardParams.Existing)
                        ?.reviewTime
                        ?: return@mapNotNull null
                    val date = input.toSrsDate(review.log.timestamp)
                    if (input.toSrsDate(previousReview) == date) return@mapNotNull null
                    CountedReview(
                        practiceType = key.second,
                        date = date,
                        elapsedDays = review.elapsed!!.toDouble(DurationUnit.DAYS),
                        passed = review.log.grade > 1,
                        predicted = review.predictedRetrievability!!
                    )
                }
            }
    }

    private fun calculateForFilter(
        input: Input,
        cards: Map<SrsCardKey, FsrsCard>,
        countedReviews: List<CountedReview>
    ): FsrsStats {
        val reviewedCards = cards.values.mapNotNull { card ->
            (card.params as? FsrsCardParams.Existing)?.let { card to it }
        }

        val retrievabilities = reviewedCards.map { (_, params) ->
            fsrs.retrievability(params, input.now).coerceIn(0.0, 1.0)
        }

        val cardCounts = CardCounts(
            learning = reviewedCards.count { (card, _) -> card.interval < 1.days },
            young = reviewedCards.count { (card, _) -> card.interval >= 1.days && card.interval < MATURE_DAYS.days },
            mature = reviewedCards.count { (card, _) -> card.interval >= MATURE_DAYS.days }
        )

        var overdue = 0
        val forecast = IntArray(input.forecastDays)
        reviewedCards.forEach { (card, params) ->
            val dueDate = input.toSrsDate(params.reviewTime + card.interval)
            val dayIndex = input.today.daysUntil(dueDate)
            if (dayIndex < 0) overdue++
            val index = dayIndex.coerceAtLeast(0)
            if (index < forecast.size) forecast[index]++
        }

        val stabilityDistribution = StabilityBucket.entries.associateWith { 0 }.toMutableMap()
        reviewedCards.forEach { (_, params) ->
            val bucket = stabilityBucket(params.stability)
            stabilityDistribution[bucket] = stabilityDistribution.getValue(bucket) + 1
        }

        val difficultyDistribution = IntArray(10)
        reviewedCards.forEach { (_, params) ->
            difficultyDistribution[bucketIndex(normalizedDifficulty(params.difficulty))]++
        }

        val retrievabilityDistribution = IntArray(10)
        retrievabilities.forEach { retrievabilityDistribution[bucketIndex(it)]++ }

        return FsrsStats(
            desiredRetention = input.desiredRetention,
            cardCounts = cardCounts,
            averageRetrievability = retrievabilities.takeIf { it.isNotEmpty() }?.average(),
            estimatedKnown = retrievabilities.sum(),
            averageStability = reviewedCards.takeIf { it.isNotEmpty() }
                ?.map { it.second.stability }?.average(),
            averageDifficulty = reviewedCards.takeIf { it.isNotEmpty() }
                ?.map { normalizedDifficulty(it.second.difficulty) }?.average(),
            trueRetention = RetentionPeriod.entries.map { period ->
                trueRetentionRow(period, input.today, countedReviews)
            },
            overdue = overdue,
            forecast = forecast.toList(),
            stabilityDistribution = stabilityDistribution,
            difficultyDistribution = difficultyDistribution.toList(),
            retrievabilityDistribution = retrievabilityDistribution.toList()
        )
    }

    private fun trueRetentionRow(
        period: RetentionPeriod,
        today: LocalDate,
        reviews: List<CountedReview>
    ): TrueRetentionRow {
        val range: (LocalDate) -> Boolean = when (period) {
            RetentionPeriod.Today -> { date -> date == today }
            RetentionPeriod.Yesterday -> { date -> date == today.minus(1, DateTimeUnit.DAY) }
            RetentionPeriod.Week -> lastDays(today, 7)
            RetentionPeriod.Month -> lastDays(today, 30)
            RetentionPeriod.Year -> lastDays(today, 365)
            RetentionPeriod.AllTime -> { _ -> true }
        }
        val periodReviews = reviews.filter { range(it.date) }
        val (young, mature) = periodReviews.partition { it.elapsedDays < MATURE_DAYS }
        return TrueRetentionRow(
            period = period,
            young = young.toPassFail(),
            mature = mature.toPassFail(),
            predicted = periodReviews.takeIf { it.isNotEmpty() }?.map { it.predicted }?.average()
        )
    }

    private fun lastDays(today: LocalDate, days: Int): (LocalDate) -> Boolean {
        val start = today.minus(days - 1, DateTimeUnit.DAY)
        return { date -> date >= start && date <= today }
    }

    private fun List<CountedReview>.toPassFail() = PassFail(
        passed = count { it.passed },
        failed = count { !it.passed }
    )

    private fun StatsFilter.predicate(): (Long) -> Boolean = when (this) {
        StatsFilter.All -> { _ -> true }
        StatsFilter.Letters -> { type -> type in LetterPracticeType.srsPracticeTypeValues }
        StatsFilter.Words -> { type -> type in VocabPracticeType.srsPracticeTypeValues }
    }

    private fun stabilityBucket(stabilityDays: Double): StabilityBucket = when {
        stabilityDays < 1 -> StabilityBucket.LessThanDay
        stabilityDays < 7 -> StabilityBucket.Week
        stabilityDays < 21 -> StabilityBucket.ThreeWeeks
        stabilityDays < 90 -> StabilityBucket.ThreeMonths
        stabilityDays < 365 -> StabilityBucket.Year
        else -> StabilityBucket.MoreThanYear
    }

    /** Maps FSRS difficulty (1..10) to 0..1 the same way Anki displays it. */
    private fun normalizedDifficulty(difficulty: Double): Double {
        return ((difficulty - Fsrs7.D_MIN) / (Fsrs7.D_MAX - Fsrs7.D_MIN)).coerceIn(0.0, 1.0)
    }

    private fun bucketIndex(value: Double): Int = (value * 10).toInt().coerceIn(0, 9)

    companion object {
        const val MATURE_DAYS = 21
    }

}
