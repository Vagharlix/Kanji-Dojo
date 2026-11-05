package ua.syt0r.kanji.core.srs.use_case

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ua.syt0r.kanji.core.srs.SrsCard
import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.SrsCardRepository
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7Optimizer
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardParams
import ua.syt0r.kanji.core.srs.fsrs.FsrsConfigurationRepository
import ua.syt0r.kanji.core.srs.fsrs.FsrsHistoryReplay
import ua.syt0r.kanji.core.srs.fsrs.FsrsParameters
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewLog
import ua.syt0r.kanji.core.srs.fsrs.reviewsSinceLastReset
import ua.syt0r.kanji.core.srs.fsrs.splitAtResets
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryRepository
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract
import kotlin.coroutines.coroutineContext

data class FsrsOptimizationResult(
    val parameters: List<Double>,
    val current: Fsrs7Optimizer.Metrics,
    val optimized: Fsrs7Optimizer.Metrics
) {
    /** Like Anki: only worth applying when the new parameters predict reviews better */
    val isImprovement: Boolean get() = optimized.logLoss < current.logLoss
}

class FsrsOptimizer(
    private val reviewHistoryRepository: ReviewHistoryRepository,
    private val srsCardRepository: SrsCardRepository,
    private val fsrsConfigurationRepository: FsrsConfigurationRepository
) {

    private suspend fun trainingItems(): List<Fsrs7Optimizer.TrainingItem> {
        val resets = srsCardRepository.getResets()
        val history = reviewHistoryRepository.getAllReviews()
            .groupBy(
                keySelector = { SrsCardKey(it.key, it.practiceType) },
                valueTransform = { FsrsReviewLog(it.timestamp, it.grade) }
            )
        // Each stretch between resets is an independent learning sequence
        val sequences = history.flatMap { (key, logs) ->
            splitAtResets(logs, resets[key].orEmpty())
        }
        return Fsrs7Optimizer.trainingItems(sequences)
    }

    /**
     * Trains parameters starting from the defaults.
     * @throws Fsrs7Optimizer.NotEnoughDataException when there is too little history
     */
    suspend fun optimize(onProgress: (Float) -> Unit): FsrsOptimizationResult {
        val items = trainingItems()
        val currentParameters = fsrsConfigurationRepository.get().parameters
        val context = coroutineContext
        return withContext(Dispatchers.Default) {
            val optimizer = Fsrs7Optimizer()
            val parameters = optimizer.optimize(
                items = items,
                initialParameters = Fsrs7.DEFAULT_PARAMETERS,
                onProgress = onProgress,
                isCancelled = { context[Job]?.isActive == false }
            )
            context.ensureActive()
            FsrsOptimizationResult(
                parameters = parameters,
                current = optimizer.evaluate(items, currentParameters),
                optimized = optimizer.evaluate(items, parameters)
            )
        }
    }

}

/**
 * Saves FSRS parameters and recalculates every card's memory state from its review
 * history with them. When [reschedule] is true, due dates are recalculated as well (from
 * each card's last review, at the current desired retention), otherwise they are kept.
 */
class ApplyFsrsParametersUseCase(
    private val appPreferences: PreferencesContract.AppPreferences,
    private val fsrsConfigurationRepository: FsrsConfigurationRepository,
    private val reviewHistoryRepository: ReviewHistoryRepository,
    private val srsCardRepository: SrsCardRepository
) {

    /** @param parameters null resets to the default parameters */
    suspend operator fun invoke(parameters: List<Double>?, reschedule: Boolean) {
        appPreferences.fsrsParameters.set(parameters?.let { FsrsParameters.format(it) } ?: "")
        recalculate(reschedule)
    }

    private suspend fun recalculate(reschedule: Boolean) {
        val configuration = fsrsConfigurationRepository.get()
        val history = reviewHistoryRepository.getAllReviews()
            .groupBy(
                keySelector = { SrsCardKey(it.key, it.practiceType) },
                valueTransform = { FsrsReviewLog(it.timestamp, it.grade) }
            )
        val cards = srsCardRepository.getAll()
        val resets = srsCardRepository.getResets()

        val updated: Map<SrsCardKey, SrsCard> = withContext(Dispatchers.Default) {
            val replay = FsrsHistoryReplay(configuration.fsrs, configuration.scheduler())
            cards.mapNotNull { (key, card) ->
                val logs = reviewsSinceLastReset(history[key].orEmpty(), resets[key].orEmpty())
                val replayed = replay.replay(logs, configuration.desiredRetention)
                    .lastOrNull()?.cardAfter ?: return@mapNotNull null
                val newCard = when {
                    reschedule -> replayed
                    else -> {
                        val newParams = replayed.params as FsrsCardParams.Existing
                        val oldParams = card.fsrsCard.params as? FsrsCardParams.Existing
                        card.fsrsCard.copy(
                            params = newParams.copy(
                                reviewTime = oldParams?.reviewTime ?: newParams.reviewTime
                            ),
                            lapses = replayed.lapses,
                            repeats = replayed.repeats
                        )
                    }
                }
                key to SrsCard(newCard)
            }.toMap()
        }

        srsCardRepository.updateAll(updated)
    }

}
