import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.SrsPracticeType
import ua.syt0r.kanji.core.srs.fsrs.FsrsHistoryReplay
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewLog
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.FsrsStatsCalculator
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.PassFail
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.RetentionPeriod
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FsrsStatsCalculatorTest {

    private val calculator = FsrsStatsCalculator()
    private val zone = TimeZone.UTC
    private val toSrsDate: (Instant) -> LocalDate = { it.toLocalDateTime(zone).date }
    private val start = LocalDateTime(2025, 3, 1, 10, 0).toInstant(zone)

    private fun review(key: String, type: SrsPracticeType, time: Instant, grade: Int) =
        ReviewHistoryItem(
            key = key,
            practiceType = type.value,
            timestamp = time,
            duration = 5.seconds,
            grade = grade,
            mistakes = 0,
            deckId = 1
        )

    @Test
    fun trueRetentionCountsOnlyFirstReviewOfLaterDays() {
        val letter = SrsPracticeType.LetterWriting
        val word = SrsPracticeType.VocabFlashcard
        val reviews = listOf(
            // Letter: new card (not counted), same-day repeat (not counted),
            // then passed after 1 day and failed after 30 days (mature)
            review("あ", letter, start, 3),
            review("あ", letter, start + 10.minutes, 3),
            review("あ", letter, start + 1.days, 3),
            review("あ", letter, start + 31.days, 1),
            review("あ", letter, start + 31.days + 5.minutes, 3),
            // Word: passed after 2 days
            review("1", word, start, 3),
            review("1", word, start + 2.days, 2),
        )
        val now = start + 31.days + 1.days
        val cards = reviews.groupBy { SrsCardKey(it.key, it.practiceType) }
            .mapValues { (_, items) ->
                FsrsHistoryReplay().replay(items.map { FsrsReviewLog(it.timestamp, it.grade) })
                    .last().cardAfter
            }

        val stats = calculator.calculate(
            FsrsStatsCalculator.Input(
                reviews = reviews,
                cards = cards,
                now = now,
                today = toSrsDate(now),
                toSrsDate = toSrsDate,
                desiredRetention = 0.9
            )
        )

        val allTime = stats.getValue(StatsFilter.All).trueRetention
            .first { it.period == RetentionPeriod.AllTime }
        assertEquals(PassFail(passed = 2, failed = 0), allTime.young)
        assertEquals(PassFail(passed = 0, failed = 1), allTime.mature)

        val letters = stats.getValue(StatsFilter.Letters)
        assertEquals(1, letters.cardCounts.total)
        val lettersAllTime = letters.trueRetention.first { it.period == RetentionPeriod.AllTime }
        assertEquals(PassFail(1, 1), lettersAllTime.total)

        val yesterday = stats.getValue(StatsFilter.All).trueRetention
            .first { it.period == RetentionPeriod.Yesterday }
        assertEquals(PassFail(0, 1), yesterday.total)

        val words = stats.getValue(StatsFilter.Words)
        assertEquals(1, words.cardCounts.total)
        assertTrue(words.averageRetrievability!! in 0.0..1.0)
        assertEquals(2, stats.getValue(StatsFilter.All).forecast.sum())
    }

    @Test
    fun heatmapStreaks() {
        val type = SrsPracticeType.LetterReading
        val reviews = listOf(0, 1, 2, 5, 6, 7, 8, 9).flatMap { day ->
            List(day % 3 + 1) { review("k$it", type, start + day.days + it.minutes, 3) }
        }
        val today = toSrsDate(start + 9.days)
        val summary = calculator.heatmap(reviews, today, toSrsDate)
        assertEquals(5, summary.longestStreak)
        assertEquals(5, summary.currentStreak)
        assertEquals(8.0 / 10, summary.daysLearnedRatio)
        assertEquals(3, summary.dateToReviewCount.getValue(toSrsDate(start + 5.days)))
    }

}
