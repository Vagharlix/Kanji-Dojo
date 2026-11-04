package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.RefreshableStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.TimePeriodStats
import kotlin.math.ceil

private const val HEATMAP_LEVELS = 4

/**
 * Review heatmap for a year: color intensity reflects the number of reviews relative to
 * the all-time daily average, tapping a day selects it.
 */
@Composable
fun StatsYearCalendar(
    today: LocalDate,
    stats: RefreshableStats<Int, TimePeriodStats>,
    dailyAverage: Int,
    selectedDate: LocalDate?,
    onDateClick: (LocalDate?) -> Unit
) {

    val selectedYear = stats.selectedTimePeriodState.value
    val reviewDates = stats.statsState.value.dateToReviewsMap

    val yearStartDate = LocalDate(selectedYear, 1, 1)
    val yearEndDate = LocalDate(selectedYear + 1, 1, 1).minus(1, DateTimeUnit.DAY)

    val daysInYear = yearEndDate.dayOfYear
    val daysInFirstWeekLastYear = yearStartDate.dayOfWeek.isoDayNumber - 1
    val daysInLastWeekNextYear = 7 - yearEndDate.dayOfWeek.isoDayNumber
    val totalDays = daysInYear + daysInFirstWeekLastYear + daysInLastWeekNextYear
    val gridStartDate = yearStartDate.minus(daysInFirstWeekLastYear, DateTimeUnit.DAY)

    val weeks = totalDays / 7

    val boxSize = 12.dp
    val margins = 2.dp

    val calendarWidth = boxSize * weeks + margins * (weeks - 1)

    val initialScrollDp = calendarWidth * (today.dayOfYear / totalDays.toFloat()) - 200.dp
    val initialScrollPx = with(LocalDensity.current) { initialScrollDp.roundToPx() }

    val defaultColor = MaterialTheme.colorScheme.surfaceVariant
    val levelColors = heatmapLevelColors()
    val todayColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectionColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = Modifier.horizontalScroll(rememberScrollState(initialScrollPx))
            .size(
                width = calendarWidth,
                height = boxSize * 7 + margins * 6
            )
            .pointerInput(selectedYear, selectedDate) {
                detectTapGestures { offset ->
                    val cell = (boxSize + margins).toPx()
                    val weekIndex = (offset.x / cell).toInt()
                    val dayIndex = (offset.y / cell).toInt().coerceIn(0, 6)
                    val date = gridStartDate.plus(weekIndex * 7 + dayIndex, DateTimeUnit.DAY)
                    onDateClick(
                        if (date.year != selectedYear || date == selectedDate) null else date
                    )
                }
            }
    ) {

        val size = Size(boxSize.toPx(), boxSize.toPx())
        var weekStart = yearStartDate

        do {
            val weekIndex = ceil(yearStartDate.daysUntil(weekStart) / 7f)
            val weekDays = (weekStart.dayOfWeek.isoDayNumber..7)
                .mapIndexed { index, i -> weekStart.plus(index, DateTimeUnit.DAY) }

            for (day in weekDays) {
                if (day.year != selectedYear) continue
                val dayIndex = day.dayOfWeek.isoDayNumber - 1
                val dayOffset = Offset(
                    x = boxSize.toPx() * weekIndex + margins.toPx() * weekIndex,
                    y = boxSize.toPx() * dayIndex + margins.toPx() * dayIndex
                )
                val count = reviewDates[day]?.size ?: 0
                drawRoundRect(
                    color = when (count) {
                        0 -> defaultColor
                        else -> levelColors[heatmapLevel(count, dailyAverage)]
                    },
                    topLeft = dayOffset,
                    size = size,
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                if (day == today) {
                    val centerOffset = boxSize.toPx() / 2
                    drawCircle(
                        color = todayColor,
                        radius = size.minDimension / 4,
                        center = dayOffset.plus(Offset(centerOffset, centerOffset))
                    )
                }
                if (day == selectedDate) {
                    drawRoundRect(
                        color = selectionColor,
                        topLeft = dayOffset,
                        size = size,
                        cornerRadius = CornerRadius(2.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            weekStart = weekStart.plus(weekDays.size, DateTimeUnit.DAY)
        } while (weekStart.year == selectedYear)

    }

}

@Composable
fun StatsHeatmapLegend(lessLabel: String, moreLabel: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(lessLabel, style = MaterialTheme.typography.labelSmall)
        (listOf(MaterialTheme.colorScheme.surfaceVariant) + heatmapLevelColors()).forEach {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(it))
        }
        Text(moreLabel, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun heatmapLevelColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    return listOf(0.3f, 0.55f, 0.8f, 1f).map { primary.copy(alpha = it) }
}

/** Level relative to the daily average: <=50%, <=100%, <=150%, more. */
private fun heatmapLevel(count: Int, dailyAverage: Int): Int {
    if (dailyAverage <= 0) return HEATMAP_LEVELS - 1
    val ratio = count.toDouble() / dailyAverage
    return when {
        ratio <= 0.5 -> 0
        ratio <= 1.0 -> 1
        ratio <= 1.5 -> 2
        else -> 3
    }
}
