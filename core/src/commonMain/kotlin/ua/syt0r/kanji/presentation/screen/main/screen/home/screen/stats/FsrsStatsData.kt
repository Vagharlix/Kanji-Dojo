package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats

import kotlinx.datetime.LocalDate

enum class StatsFilter { All, Letters, Words }

enum class RetentionPeriod { Today, Yesterday, Week, Month, Year, AllTime }

data class PassFail(val passed: Int, val failed: Int) {
    val total: Int get() = passed + failed
    val rate: Double? get() = if (total == 0) null else passed.toDouble() / total

    operator fun plus(other: PassFail) = PassFail(passed + other.passed, failed + other.failed)

    companion object {
        val Empty = PassFail(0, 0)
    }
}

data class TrueRetentionRow(
    val period: RetentionPeriod,
    val young: PassFail,
    val mature: PassFail,
    /** Average FSRS-7 predicted recall probability for the same reviews */
    val predicted: Double?
) {
    val total: PassFail get() = young + mature
}

data class CardCounts(
    val learning: Int,
    val young: Int,
    val mature: Int
) {
    val total: Int get() = learning + young + mature
}

enum class StabilityBucket { LessThanDay, Week, ThreeWeeks, ThreeMonths, Year, MoreThanYear }

data class FsrsStats(
    val desiredRetention: Double,
    val cardCounts: CardCounts,
    /** Mean current probability of recall over all studied cards */
    val averageRetrievability: Double?,
    /** Sum of current recall probabilities, i.e. the expected number of items you'd recall now */
    val estimatedKnown: Double,
    /** Mean long-term stability in days */
    val averageStability: Double?,
    /** Mean difficulty mapped to 0..1 */
    val averageDifficulty: Double?,
    val trueRetention: List<TrueRetentionRow>,
    val overdue: Int,
    /** Cards due on each of the following days, index 0 is today (overdue included) */
    val forecast: List<Int>,
    val stabilityDistribution: Map<StabilityBucket, Int>,
    /** 10 buckets: 0-10%, 10-20%, ... 90-100% */
    val difficultyDistribution: List<Int>,
    /** 10 buckets: 0-10%, 10-20%, ... 90-100% */
    val retrievabilityDistribution: List<Int>
)

data class HeatmapSummary(
    val dateToReviewCount: Map<LocalDate, Int>,
    /** Average reviews on days you studied */
    val dailyAverage: Int,
    /** Share of days since the first review on which you studied, 0..1 */
    val daysLearnedRatio: Double,
    val longestStreak: Int,
    val currentStreak: Int
)
