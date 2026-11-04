import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import ua.syt0r.kanji.core.srs.fsrs.DefaultFsrsScheduler
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.FsrsAnswers
import ua.syt0r.kanji.core.srs.fsrs.FsrsCard
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardParams
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus
import ua.syt0r.kanji.core.srs.fsrs.FsrsHistoryReplay
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewLog
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Again
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Easy
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Good
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating.Hard
import ua.syt0r.kanji.core.srs.fsrs.retrievability
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

class Fsrs7Test {

    private val fsrs = Fsrs7()
    private val scheduler = DefaultFsrsScheduler(fsrs)
    private val start: Instant = LocalDateTime(2025, 1, 1, 12, 0).toInstant(TimeZone.UTC)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    @Test
    fun initialStateMatchesParameters() {
        val state = fsrs.initialState(Good)
        assertClose(3.9221, state.longStability)
        assertClose(3.9221 * 0.8, state.shortStability)
        assertClose(3.5307239813, state.difficulty)
    }

    /**
     * Expected values calculated independently from the FSRS-7 formulas of the reference
     * implementation (srs-benchmark models/fsrs_v7.py) with the default parameters.
     */
    @Test
    fun stateTransitionsMatchReferenceFormulas() {
        var state = fsrs.initialState(Good)
        val steps = listOf(
            Triple(10.0 / 1440, Good, doubleArrayOf(0.9734484662, 5.6914894568, 32.2381559865, 3.4977167432)),
            Triple(1.0, Good, doubleArrayOf(0.9575567788, 9.8599282802, 197.3838183326, 3.4650395775)),
            Triple(3.5, Again, doubleArrayOf(0.9600872440, 2.3089584560, 0.7684991756, 9.0423638159)),
            Triple(2.0, Easy, doubleArrayOf(0.7899372664, 4.7694951902, 33.9455945017, 8.5665153141)),
        )
        steps.forEach { (elapsed, rating, expected) ->
            assertClose(expected[0], fsrs.retrievability(state, elapsed), 1e-8)
            state = fsrs.nextState(state, elapsed, rating)
            assertClose(expected[1], state.longStability, 1e-6)
            assertClose(expected[2], state.shortStability, 1e-6)
            assertClose(expected[3], state.difficulty, 1e-6)
        }
        assertClose(1.23317933, fsrs.interval(state, 0.9), 1e-5)
    }

    @Test
    fun intervalHitsDesiredRetention() {
        var state = fsrs.initialState(Good)
        listOf(0.9, 0.8, 0.95, 0.97).forEach { retention ->
            repeat(6) {
                val interval = fsrs.interval(state, retention)
                assertClose(retention, fsrs.retrievability(state, interval), 1e-4)
                state = fsrs.nextState(state, interval, Good)
            }
        }
    }

    @Test
    fun retrievabilityIsOneRightAfterReviewAndDecreases() {
        val state = fsrs.initialState(Hard)
        assertClose(1.0, fsrs.retrievability(state, 0.0), 1e-9)
        var previous = 1.0
        listOf(0.01, 0.1, 1.0, 10.0, 100.0).forEach {
            val r = fsrs.retrievability(state, it)
            assertTrue(r < previous)
            previous = r
        }
    }

    @Test
    fun higherRetentionGivesShorterIntervals() {
        val state = fsrs.nextState(fsrs.initialState(Good), 3.0, Good)
        assertTrue(fsrs.interval(state, 0.95) < fsrs.interval(state, 0.9))
        assertTrue(fsrs.interval(state, 0.9) < fsrs.interval(state, 0.8))
    }

    @Test
    fun answersAreOrdered() {
        var card = scheduler.newCard()
        var time = start
        val ratings = listOf(Good, Good, Hard, Good, Again, Good, Easy, Good)
        ratings.forEach { rating ->
            val answers = scheduler.schedule(card, time)
            assertTrue(answers.again.interval <= answers.hard.interval)
            assertTrue(answers.hard.interval <= answers.good.interval)
            assertTrue(answers.good.interval <= answers.easy.interval)
            card = answers.get(rating)
            time += card.interval
        }
    }

    @Test
    fun goodReviewsGrowIntervals() {
        var card = scheduler.newCard()
        var time = start
        val intervals = mutableListOf<Double>()
        repeat(8) {
            card = scheduler.schedule(card, time).good
            intervals.add(card.interval.toDouble(DurationUnit.DAYS))
            time += card.interval
        }
        println("Good intervals (days): $intervals")
        intervals.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "intervals $intervals") }
        assertEquals(FsrsCardStatus.Review, card.status)
    }

    @Test
    fun shortIntervalsAreRoundedToMinutesAndLongOnesToDays() {
        val answers = scheduler.schedule(scheduler.newCard(), start)
        answers.toList().forEach { card ->
            val interval = card.interval
            if (interval < 1.days) {
                assertTrue(interval >= 1.minutes)
                assertEquals(0L, interval.inWholeSeconds % 60)
            } else {
                assertEquals(0L, interval.inWholeHours % 24)
            }
        }
    }

    @Test
    fun lapsesOnlyCountForgottenReviewCards() {
        var card = scheduler.newCard()
        var time = start
        // Get the card to review state
        while (card.status != FsrsCardStatus.Review) {
            card = scheduler.schedule(card, time).good
            time += card.interval
        }
        val lapsesBefore = card.lapses

        val good = scheduler.schedule(card, time).good
        assertEquals(lapsesBefore, good.lapses)

        val again = scheduler.schedule(card, time).again
        assertEquals(lapsesBefore + 1, again.lapses)
        assertEquals(FsrsCardStatus.Relearning, again.status)
        assertTrue(again.interval < 1.days)
    }

    @Test
    fun replayMatchesManualScheduling() {
        val logs = listOf(
            FsrsReviewLog(start, Good.grade),
            FsrsReviewLog(start + 10.minutes, Good.grade),
            FsrsReviewLog(start + 2.days, Again.grade),
            FsrsReviewLog(start + 2.days + 5.minutes, Good.grade),
            FsrsReviewLog(start + 5.days, Easy.grade),
        )
        val replayed = FsrsHistoryReplay(fsrs, scheduler).replay(logs.shuffled())
        assertEquals(logs.size, replayed.size)
        assertEquals(null, replayed.first().predictedRetrievability)

        var card = scheduler.newCard()
        logs.forEach { log ->
            card = scheduler.schedule(card, log.timestamp).get(FsrsReviewRating.fromGrade(log.grade))
        }
        assertEquals(card, replayed.last().cardAfter)

        val params = card.params as FsrsCardParams.Existing
        assertClose(1.0, fsrs.retrievability(params, params.reviewTime), 1e-9)
    }

    private fun FsrsAnswers.get(rating: FsrsReviewRating): FsrsCard = when (rating) {
        Again -> again
        Hard -> hard
        Good -> good
        Easy -> easy
    }

    private fun FsrsAnswers.toList() = listOf(again, hard, good, easy)

}
