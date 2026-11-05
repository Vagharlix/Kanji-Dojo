import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import ua.syt0r.kanji.core.srs.fsrs.DefaultFsrsScheduler
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus
import ua.syt0r.kanji.core.srs.fsrs.FsrsSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FsrsStepsTest {

    private val start = LocalDateTime(2025, 1, 1, 12, 0).toInstant(TimeZone.UTC)
    private val scheduler = DefaultFsrsScheduler(
        fsrs = Fsrs7(),
        learningSteps = listOf(1.minutes, 10.minutes),
        relearningSteps = listOf(10.minutes)
    )

    @Test
    fun parsesAndFormatsSteps() {
        assertEquals(listOf(1.minutes, 10.minutes, 1.hours, 1.days, 30.seconds),
            FsrsSteps.parse("1m 10 1h 1d 30s"))
        assertEquals(listOf(90.minutes), FsrsSteps.parse("1.5h"))
        assertEquals(emptyList(), FsrsSteps.parse("   "))
        assertNull(FsrsSteps.parse("1m ten"))
        assertNull(FsrsSteps.parse("0m"))
        assertEquals("1m 10m 1h 2d 30s",
            FsrsSteps.format(listOf(1.minutes, 10.minutes, 1.hours, 2.days, 30.seconds)))
    }

    @Test
    fun newCardFollowsLearningSteps() {
        val answers = scheduler.schedule(scheduler.newCard(), start)
        assertEquals(1.minutes, answers.again.interval)
        assertEquals(330.seconds, answers.hard.interval) // between the first two steps
        assertEquals(10.minutes, answers.good.interval)
        assertEquals(FsrsCardStatus.Learning, answers.good.status)
        assertEquals(1, answers.good.step)
        assertEquals(FsrsCardStatus.Review, answers.easy.status)
        assertTrue(answers.easy.interval >= 1.days)
    }

    @Test
    fun goodOnLastStepGraduates() {
        var card = scheduler.schedule(scheduler.newCard(), start).good // step 1 (10m)
        val time = start + 10.minutes
        val answers = scheduler.schedule(card, time)
        assertEquals(10.minutes, answers.hard.interval) // repeats current step
        assertEquals(1, answers.hard.step)
        assertEquals(1.minutes, answers.again.interval)
        assertEquals(0, answers.again.step)
        card = answers.good
        assertEquals(FsrsCardStatus.Review, card.status)
        assertTrue(card.interval >= 1.days)
        assertTrue(answers.easy.interval > card.interval)
    }

    @Test
    fun lapseGoesThroughRelearningSteps() {
        var card = scheduler.schedule(scheduler.newCard(), start).easy
        var time = start + card.interval
        val again = scheduler.schedule(card, time).again
        assertEquals(FsrsCardStatus.Relearning, again.status)
        assertEquals(10.minutes, again.interval)
        assertEquals(card.lapses + 1, again.lapses)

        time += 10.minutes
        card = scheduler.schedule(again, time).good
        assertEquals(FsrsCardStatus.Review, card.status)
        assertTrue(card.interval >= 1.days)
    }

    @Test
    fun withoutStepsFsrsSchedulesSameDayReviews() {
        val plain = DefaultFsrsScheduler(Fsrs7())
        val withSteps = scheduler.schedule(scheduler.newCard(), start)
        val answers = plain.schedule(plain.newCard(), start)
        // Memory states are the same, only the intervals differ
        assertEquals(withSteps.good.params, answers.good.params)
        assertEquals(0, answers.good.step)
    }

    @Test
    fun passedReviewCardsStayReviewCardsLikeAnki() {
        var card = scheduler.schedule(scheduler.newCard(), start).easy
        var time = start
        repeat(5) {
            // Review much too early: FSRS-7 alone could propose a sub-day interval
            time += 1.minutes
            val answers = scheduler.schedule(card, time)
            listOf(answers.hard, answers.good, answers.easy).forEach {
                assertEquals(FsrsCardStatus.Review, it.status)
                assertTrue(it.interval >= 1.days)
            }
            card = answers.good
        }
    }

}
