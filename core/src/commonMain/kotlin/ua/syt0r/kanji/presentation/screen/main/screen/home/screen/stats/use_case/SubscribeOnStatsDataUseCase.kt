package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.use_case

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import ua.syt0r.kanji.core.RefreshableData
import ua.syt0r.kanji.core.logger.Logger
import ua.syt0r.kanji.core.refreshableDataFlow
import ua.syt0r.kanji.core.srs.LetterPracticeType
import ua.syt0r.kanji.core.srs.LetterSrsManager
import ua.syt0r.kanji.core.srs.SrsCardRepository
import ua.syt0r.kanji.core.srs.fsrs.FsrsConfigurationRepository
import ua.syt0r.kanji.core.srs.VocabPracticeType
import ua.syt0r.kanji.core.time.TimeUtils
import ua.syt0r.kanji.core.user_data.database.ReviewHistoryRepository
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract
import ua.syt0r.kanji.presentation.LifecycleState
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.DayStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.FsrsStatsCalculator
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsFilter
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.RefreshableStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsData
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.TimePeriodStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.TotalStats
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

interface SubscribeOnStatsDataUseCase {
    operator fun invoke(lifecycleState: StateFlow<LifecycleState>): Flow<RefreshableData<StatsData>>
}

class DefaultSubscribeOnStatsDataUseCase(
    private val letterSrsManager: LetterSrsManager,
    private val reviewHistoryRepository: ReviewHistoryRepository,
    private val srsCardRepository: SrsCardRepository,
    private val fsrsConfigurationRepository: FsrsConfigurationRepository,
    private val timeUtils: TimeUtils,
    private val appPreferences: PreferencesContract.AppPreferences
) : SubscribeOnStatsDataUseCase {

    private val selectedFilter = mutableStateOf(StatsFilter.All)

    override fun invoke(
        lifecycleState: StateFlow<LifecycleState>
    ): Flow<RefreshableData<StatsData>> {
        return refreshableDataFlow(
            dataChangeFlow = merge(
                letterSrsManager.dataChangeFlow,
                fsrsConfigurationRepository.changes
            ),
            lifecycleState = lifecycleState,
            valueProvider = { getStats(this) }
        )
    }

    private suspend fun getStats(coroutineScope: CoroutineScope): StatsData {
        Logger.logMethod()

        val resetTime = appPreferences.dailyResetTime.get()
        val currentDateTime = timeUtils.getCurrentTime()
        val today = if (currentDateTime.time < resetTime) {
            currentDateTime.date.minus(1, DateTimeUnit.DAY)
        } else {
            currentDateTime.date
        }

        val timeZone = TimeZone.currentSystemDefault()
        val toSrsDate: (Instant) -> LocalDate = { instant ->
            val localDateTime = instant.toLocalDateTime(timeZone)
            if (localDateTime.time < resetTime) localDateTime.date.minus(1, DateTimeUnit.DAY)
            else localDateTime.date
        }

        val allReviews = reviewHistoryRepository.getAllReviews()
        val cards = srsCardRepository.getAll().mapValues { it.value.fsrsCard }
        val fsrsConfiguration = fsrsConfigurationRepository.get()
        val fsrsStatsCalculator = FsrsStatsCalculator(fsrsConfiguration.fsrs)
        val now = timeUtils.now()

        val (heatmapSummary, fsrsStats) = withContext(Dispatchers.Default) {
            fsrsStatsCalculator.heatmap(allReviews, today, toSrsDate) to
                    fsrsStatsCalculator.calculate(
                        FsrsStatsCalculator.Input(
                            reviews = allReviews,
                            cards = cards,
                            now = now,
                            today = today,
                            toSrsDate = toSrsDate,
                            desiredRetention = fsrsConfiguration.desiredRetention
                        )
                    )
        }

        return StatsData(
            todayStats = getDayStats(today, resetTime),
            yearStats = refreshableStats(
                coroutineScope = coroutineScope,
                initialTimePeriod = today.year,
                statsProvider = { getYearStats(it, resetTime) }
            ),
            totalStats = getTotalStats(),
            heatmapSummary = heatmapSummary,
            fsrsStats = fsrsStats,
            selectedFilter = selectedFilter
        )
    }

    private suspend fun getDayStats(
        date: LocalDate,
        resetTime: LocalTime
    ): DayStats {
        val stats = getStatsForPeriod(
            start = date,
            end = date.plus(1, DateTimeUnit.DAY),
            resetTime = resetTime
        )
        val reviews = stats.dateToReviewsMap.entries.firstOrNull()?.value ?: emptyList()
        return DayStats(
            date = date,
            reviews = reviews.size,
            timeSpent = reviews
                .map { it.duration }
                .fold(Duration.ZERO) { acc, duration ->
                    acc.plus(duration.coerceAtMost(SingleReviewDurationLimit))
                }
        )
    }

    private suspend fun <TimePeriod, Stats> refreshableStats(
        coroutineScope: CoroutineScope,
        initialTimePeriod: TimePeriod,
        statsProvider: suspend (TimePeriod) -> Stats
    ): RefreshableStats<TimePeriod, Stats> {

        val timePeriodState = mutableStateOf(initialTimePeriod)
        val statsState = mutableStateOf(statsProvider(initialTimePeriod))
        val isLoading = mutableStateOf(false)

        snapshotFlow { timePeriodState.value }
            .drop(1)
            .onEach {
                isLoading.value = true
                statsState.value = statsProvider(it)
                isLoading.value = false
            }
            .launchIn(coroutineScope)

        return RefreshableStats(
            currentTimePeriod = initialTimePeriod,
            selectedTimePeriodState = timePeriodState,
            isLoading = isLoading,
            statsState = statsState
        )
    }

    private suspend fun getYearStats(
        year: Int,
        resetTime: LocalTime
    ): TimePeriodStats {
        val yearStart = LocalDate(year, 1, 1)
        val yearEnd = LocalDate(year + 1, 1, 1)
        return getStatsForPeriod(yearStart, yearEnd, resetTime)

    }

    private suspend fun getTotalStats(): TotalStats {
        return TotalStats(
            reviews = reviewHistoryRepository
                .getTotalReviewsCount()
                .toInt(),
            timeSpent = reviewHistoryRepository
                .getTotalPracticeTime(SingleReviewDurationLimit.inWholeMilliseconds),
            uniqueLettersStudied = reviewHistoryRepository
                .getUniqueReviewItemsCount(LetterPracticeType.srsPracticeTypeValues)
                .toInt(),
            uniqueWordsStudied = reviewHistoryRepository
                .getUniqueReviewItemsCount(VocabPracticeType.srsPracticeTypeValues)
                .toInt()
        )
    }

    private suspend fun getStatsForPeriod(
        start: LocalDate,
        end: LocalDate,
        resetTime: LocalTime
    ): TimePeriodStats {
        val timeZone = TimeZone.currentSystemDefault()
        val resetTimeOffsetDuration = resetTime.toMillisecondOfDay().milliseconds
        val startInstant = start.atStartOfDayIn(timeZone).plus(resetTimeOffsetDuration)
        val endInstant = end.atStartOfDayIn(timeZone).plus(resetTimeOffsetDuration)

        val reviewsToDateMap = reviewHistoryRepository
            .getReviews(startInstant, endInstant)
            .map { historyItem ->
                val adjustedDateTime = historyItem.timestamp
                    .toLocalDateTime(timeZone)
                    .run {
                        if (time < resetTime) {
                            date.minus(1, DateTimeUnit.DAY)
                        } else {
                            date
                        }
                    }
                historyItem to adjustedDateTime
            }

        val dateToReviewsMap = reviewsToDateMap
            .groupBy { (_, date) -> date }
            .toList()
            .associate { (date, items) ->
                date to items.map { (reviewHistoryItem, date) -> reviewHistoryItem }
            }

        return TimePeriodStats(
            dateToReviewsMap = dateToReviewsMap.mapValues { (_, practices) -> practices },
        )
    }

    companion object {
        private val SingleReviewDurationLimit = 1.minutes
    }

}