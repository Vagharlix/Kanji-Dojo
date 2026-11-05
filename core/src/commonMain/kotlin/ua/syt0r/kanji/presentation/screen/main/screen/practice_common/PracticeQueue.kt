package ua.syt0r.kanji.presentation.screen.main.screen.practice_common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import ua.syt0r.kanji.core.analytics.AnalyticsManager
import ua.syt0r.kanji.core.debounceFirst
import ua.syt0r.kanji.core.srs.AnkiQueueOrder
import ua.syt0r.kanji.core.srs.SrsAnswers
import ua.syt0r.kanji.core.srs.SrsCard
import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.SrsCardRepository
import ua.syt0r.kanji.core.srs.SrsScheduler
import ua.syt0r.kanji.core.time.TimeUtils
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryItem
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days


interface PracticeQueue<State, Descriptor> {

    val state: StateFlow<State>

    suspend fun initialize(items: List<Descriptor>)
    suspend fun submitAnswer(answer: PracticeAnswer)
    fun immediateFinish()

}

interface PracticeQueueItem<T : PracticeQueueItem<T>> {

    val srsCardKey: SrsCardKey
    val srsCard: SrsCard
    val deckId: Long
    val repeats: Int
    val totalMistakes: Int
    val data: Deferred<Any>

    fun copyForRepeat(answer: PracticeAnswer): T

}

data class PracticeQueueProgress(
    val pending: Int,
    val repeats: Int,
    val completed: Int
)

interface PracticeSummaryItem {
    val totalReviews: Deferred<Int>
    val nextInterval: Duration
}

abstract class BasePracticeQueue<State, Descriptor, QueueItem, SummaryItem>(
    private val practiceScope: CoroutineScope,
    protected val timeUtils: TimeUtils,
    protected val srsScheduler: SrsScheduler,
    protected val srsCardRepository: SrsCardRepository,
    private val reviewHistoryRepository: ReviewHistoryRepository,
    analyticsManager: AnalyticsManager
) : PracticeQueue<State, Descriptor>
        where QueueItem : PracticeQueueItem<QueueItem>,
              SummaryItem : PracticeSummaryItem {

    protected open lateinit var queue: MutableList<QueueItem>
    protected val summaryItems = mutableMapOf<SrsCardKey, SummaryItem>()

    protected lateinit var practiceStartInstant: Instant
    private lateinit var currentReviewStartInstant: Instant

    private val submittedAnswersChannel = Channel<PracticeAnswer>()

    private val _state: MutableStateFlow<State> = MutableStateFlow(value = this.getLoadingState())
    override val state: StateFlow<State> = _state

    private val reviewReporter = PracticeReviewReporter(analyticsManager)

    init {
        submittedAnswersChannel.consumeAsFlow()
            .debounceFirst()
            .onEach { handleAnswer(it) }
            .launchIn(practiceScope)
    }

    protected abstract suspend fun Descriptor.toQueueItem(): QueueItem
    protected abstract fun createSummaryItem(
        queueItem: QueueItem,
        totalReviews: Deferred<Int>
    ): SummaryItem

    protected abstract fun getLoadingState(): State
    protected abstract suspend fun getReviewState(item: QueueItem, answers: PracticeAnswers): State
    protected abstract fun getSummaryState(): State

    override suspend fun initialize(items: List<Descriptor>) {
        practiceStartInstant = timeUtils.now()
        queue = items.map { it.toQueueItem() }.toMutableList()
        updateState()
    }

    override suspend fun submitAnswer(answer: PracticeAnswer) {
        submittedAnswersChannel.send(answer)
    }

    override fun immediateFinish() {
        val isLoading = summaryItems.any { it.value.totalReviews.isCompleted.not() }
        if (isLoading) {
            _state.value = getLoadingState()
            practiceScope.launch {
                summaryItems.forEach { it.value.totalReviews.await() }
                _state.value = getSummaryState()
            }
        } else _state.value = getSummaryState()
    }

    protected fun getProgress(): PracticeQueueProgress {
        return PracticeQueueProgress(
            pending = queue.count { it.repeats == 0 },
            repeats = queue.count { it.repeats > 0 },
            completed = summaryItems.filter { it.value.nextInterval >= 1.days }.size
        )
    }

    private fun getAnswers(answers: SrsAnswers): PracticeAnswers {
        return PracticeAnswers(
            again = PracticeAnswer(answers.again),
            hard = PracticeAnswer(answers.hard),
            good = PracticeAnswer(answers.good),
            easy = PracticeAnswer(answers.easy),
            passFailMode = answers.passFailMode
        )
    }

    private suspend fun handleAnswer(answer: PracticeAnswer) {
        val item = queue.removeFirstOrNull() ?: return
        val updatedItem = item.copyForRepeat(answer)

        saveSummaryData(updatedItem)

        val instant = timeUtils.now()
        val reviewDuration = instant - currentReviewStartInstant

        if (answer.srsAnswer.card.interval < 1.days) {
            // Comes back once it's due, see AnkiQueueOrder
            queue.add(updatedItem)
        }

        updateState()

        srsCardRepository.update(item.srsCardKey, answer.srsAnswer.card)
        saveReviewHistory(item, answer, instant, reviewDuration)
        reviewReporter.reportReview(updatedItem, answer, reviewDuration)
    }

    private suspend fun updateState() {
        val item = if (moveNextItemToFront()) queue.getOrNull(0) else null
        if (item == null) {
            // Done; learning cards that aren't due yet come back as due later, like in Anki
            immediateFinish()
        } else {
            if (!item.data.isCompleted) {
                _state.value = getLoadingState()
            }
            val time = timeUtils.now()
            val srsAnswers = srsScheduler.answers(item.srsCard, time)

            item.data.await()
            currentReviewStartInstant = timeUtils.now()

            _state.value = getReviewState(item, getAnswers(srsAnswers))

            queue.getOrNull(1)?.apply {
                data.start()
            }
        }
    }

    /** Puts the card Anki would show next at the front, false if none can be shown now. */
    private suspend fun moveNextItemToFront(): Boolean {
        if (queue.isEmpty()) return false
        val index = AnkiQueueOrder.nextIndex(
            cards = queue.map { it.srsCard },
            now = timeUtils.now(),
            learnAhead = srsScheduler.learnAheadLimit()
        ) ?: return false
        if (index != 0) queue.add(0, queue.removeAt(index))
        return true
    }

    private fun saveSummaryData(queueItem: QueueItem) {
        val summaryItem = createSummaryItem(
            queueItem = queueItem,
            totalReviews = practiceScope.async {
                queueItem.srsCardKey.run {
                    reviewHistoryRepository.getTotalReviewCount(itemKey, practiceType)
                        .toInt()
                        .plus(1) // To count current review before its saved
                }
            }
        )
        summaryItems[queueItem.srsCardKey] = summaryItem
    }

    private suspend fun saveReviewHistory(
        queueItem: QueueItem,
        answer: PracticeAnswer,
        reviewStart: Instant,
        reviewDuration: Duration
    ) {
        val item = ReviewHistoryItem(
            key = queueItem.srsCardKey.itemKey,
            practiceType = queueItem.srsCardKey.practiceType,
            timestamp = reviewStart,
            duration = reviewDuration,
            grade = answer.srsAnswer.grade,
            mistakes = answer.mistakes,
            deckId = queueItem.deckId
        )
        reviewHistoryRepository.addReview(item)
    }


}

private class PracticeReviewReporter<T : PracticeQueueItem<*>>(
    private val analyticsManager: AnalyticsManager
) {

    fun reportReview(
        item: T,
        answer: PracticeAnswer,
        reviewDuration: Duration
    ) {
        analyticsManager.sendEvent("review") {
            put("key", item.srsCardKey.itemKey)
            put("practice_type", item.srsCardKey.practiceType)
            put("duration", reviewDuration.inWholeMilliseconds)
            put("mistakes", answer.mistakes)
            put("repeats", item.srsCard.fsrsCard.repeats)
            put("lapses", item.srsCard.fsrsCard.lapses)
        }
    }

}
