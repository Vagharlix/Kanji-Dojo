import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.SrsPracticeType
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewLog
import ua.syt0r.kanji.core.srs.fsrs.reviewsSinceLastReset
import ua.syt0r.kanji.core.srs.fsrs.splitAtResets
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.FsrsStatsCalculator
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.PassFail
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.RetentionPeriod
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class SrsResetTest {

    private val zone = TimeZone.UTC
    private val start = LocalDateTime(2025, 6, 1, 9, 0).toInstant(zone)
    private fun log(day: Int, grade: Int = 3) = FsrsReviewLog(start + day.days, grade)

    @Test
    fun splitsHistoryAtResets() {
        val logs = listOf(log(0), log(1), log(5), log(10), log(20))
        val resets = listOf(start + 7.days, start + 3.days)

        val segments = splitAtResets(logs.shuffled(), resets)

        assertEquals(listOf(listOf(log(0), log(1)), listOf(log(5)), listOf(log(10), log(20))), segments)
        assertEquals(listOf(log(10), log(20)), reviewsSinceLastReset(logs, resets))
        assertEquals(listOf(logs), splitAtResets(logs, emptyList()))
        assertEquals(emptyList(), reviewsSinceLastReset(logs, listOf(start + 30.days)))
    }

    @Test
    fun firstReviewAfterResetIsNotCountedForTrueRetention() {
        val type = SrsPracticeType.LetterWriting
        val reviews = listOf(0, 2, 6, 15).map { day ->
            ReviewHistoryItem("字", type.value, start + day.days, 5.seconds, 3, 0, 1)
        }
        val toSrsDate: (Instant) -> LocalDate = { it.toLocalDateTime(zone).date }
        val now = start + 16.days

        fun allTime(resets: Map<SrsCardKey, List<Instant>>) = FsrsStatsCalculator()
            .calculate(
                FsrsStatsCalculator.Input(
                    reviews = reviews,
                    cards = emptyMap(),
                    now = now,
                    today = toSrsDate(now),
                    toSrsDate = toSrsDate,
                    desiredRetention = 0.9,
                    resets = resets
                )
            )
            .getValue(StatsFilter.All)
            .trueRetention.first { it.period == RetentionPeriod.AllTime }
            .total

        // Without resets: 3 counted reviews (all but the very first)
        assertEquals(PassFail(3, 0), allTime(emptyMap()))
        // Reset between day 2 and 6: the review on day 6 starts a new sequence
        val resets = mapOf(SrsCardKey("字", type.value) to listOf(start + 4.days))
        assertEquals(PassFail(2, 0), allTime(resets))
    }

}
