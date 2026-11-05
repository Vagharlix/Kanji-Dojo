package ua.syt0r.kanji.presentation.preview.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.datetime.LocalDate
import ua.syt0r.kanji.presentation.common.theme.AppTheme
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.CardCounts
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.DayStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.FsrsStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.HeatmapSummary
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.PassFail
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.RetentionPeriod
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StabilityBucket
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsFilter
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.TrueRetentionRow
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.RefreshableStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsData
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsScreenContract
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsScreenUI
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.TimePeriodStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.TotalStats
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val fakeData = StatsScreenContract.ScreenState.Loaded(
    StatsData(
        todayStats = DayStats(
            date = LocalDate(2023, 11, 21),
            reviews = 4,
            timeSpent = 5.minutes
        ),
        yearStats = RefreshableStats(
            currentTimePeriod = 2023,
            selectedTimePeriodState = mutableStateOf(2023),
            isLoading = mutableStateOf(false),
            statsState = mutableStateOf(
                TimePeriodStats(
                    dateToReviewsMap = emptyMap()
                )
            )
        ),
        totalStats = TotalStats(
            reviews = 200,
            timeSpent = 1.hours,
            uniqueLettersStudied = Random.nextInt(0, 200),
            uniqueWordsStudied = Random.nextInt(0, 200)
        ),
        heatmapSummary = HeatmapSummary(
            dateToReviewCount = emptyMap(),
            dailyAverage = 12,
            daysLearnedRatio = 0.8,
            longestStreak = 30,
            currentStreak = 5
        ),
        fsrsStats = StatsFilter.entries.associateWith {
            FsrsStats(
                desiredRetention = 0.9,
                cardCounts = CardCounts(learning = 5, young = 40, mature = 120),
                averageRetrievability = 0.92,
                estimatedKnown = 150.0,
                averageStability = 45.0,
                averageDifficulty = 0.5,
                trueRetention = RetentionPeriod.entries.map {
                    TrueRetentionRow(it, PassFail(40, 5), PassFail(90, 6), 0.9)
                },
                overdue = 3,
                forecast = List(30) { Random.nextInt(0, 30) },
                stabilityDistribution = StabilityBucket.entries.associateWith { Random.nextInt(0, 50) },
                difficultyDistribution = List(10) { Random.nextInt(0, 30) },
                retrievabilityDistribution = List(10) { Random.nextInt(0, 30) }
            )
        },
        selectedFilter = mutableStateOf(StatsFilter.All)
    )
)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ScreenPreview() {
    AppTheme {
        StatsScreenUI(rememberUpdatedState(fakeData))
    }
}

@Preview(showSystemUi = true, device = Devices.PIXEL_C)
@Composable
private fun TabletPreview() {
    ScreenPreview()
}
