import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import ua.syt0r.kanji.core.srs.AnkiQueueOrder
import ua.syt0r.kanji.core.srs.SrsCard
import ua.syt0r.kanji.core.srs.fsrs.FsrsCard
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardParams
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class AnkiQueueOrderTest {

    private val now: Instant = LocalDateTime(2025, 5, 1, 12, 0).toInstant(TimeZone.UTC)
    private val learnAhead = 20.minutes

    private fun newCard() = SrsCard(
        FsrsCard(FsrsCardStatus.New, FsrsCardParams.New, Duration.ZERO, 0, 0)
    )

    /** Card reviewed [ago] before now with the given [interval]. */
    private fun reviewed(ago: Duration, interval: Duration) = SrsCard(
        FsrsCard(
            status = if (interval < 1.days) FsrsCardStatus.Learning else FsrsCardStatus.Review,
            params = FsrsCardParams.Existing(5.0, 1.0, 1.0, now - ago),
            interval = interval,
            lapses = 0,
            repeats = 1
        )
    )

    @Test
    fun dueLearningCardsComeFirst() {
        val cards = listOf(
            newCard(),
            reviewed(ago = 5.minutes, interval = 10.minutes), // due in 5 min
            reviewed(ago = 3.minutes, interval = 1.minutes),  // due 2 min ago
            reviewed(ago = 12.minutes, interval = 10.minutes) // due 2 min ago, but later
        )
        assertEquals(2, AnkiQueueOrder.nextIndex(cards, now, learnAhead))
    }

    @Test
    fun otherCardsBeforeLearningCardsThatAreNotDueYet() {
        val cards = listOf(
            reviewed(ago = 1.minutes, interval = 10.minutes), // waiting
            reviewed(ago = 10.days, interval = 7.days),       // due review
            newCard()
        )
        assertEquals(1, AnkiQueueOrder.nextIndex(cards, now, learnAhead))
    }

    @Test
    fun learnAheadOnlyWhenNothingElseIsLeft() {
        val withinLimit = listOf(
            reviewed(ago = 1.minutes, interval = 30.minutes), // due in 29 min
            reviewed(ago = 1.minutes, interval = 10.minutes)  // due in 9 min
        )
        assertEquals(1, AnkiQueueOrder.nextIndex(withinLimit, now, learnAhead))

        val beyondLimit = listOf(reviewed(ago = 1.minutes, interval = 1.days - 1.minutes))
        assertNull(AnkiQueueOrder.nextIndex(beyondLimit, now, learnAhead))
        assertNull(AnkiQueueOrder.nextIndex(emptyList(), now, learnAhead))
    }

    @Test
    fun waitingCardsAreNotCountedAsDue() {
        assertTrue(AnkiQueueOrder.isWaiting(reviewed(1.minutes, 3.days / 4), now, learnAhead))
        assertFalse(AnkiQueueOrder.isWaiting(reviewed(1.minutes, 10.minutes), now, learnAhead))
        assertFalse(AnkiQueueOrder.isWaiting(reviewed(1.days, 2.days), now, learnAhead))
        assertFalse(AnkiQueueOrder.isWaiting(newCard(), now, learnAhead))
    }

}
