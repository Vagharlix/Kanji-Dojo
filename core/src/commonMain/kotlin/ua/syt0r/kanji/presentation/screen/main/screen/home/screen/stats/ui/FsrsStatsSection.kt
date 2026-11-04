package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.syt0r.kanji.presentation.common.resources.string.StatsStrings
import ua.syt0r.kanji.presentation.common.resources.string.formatDecimal
import ua.syt0r.kanji.presentation.common.resources.string.formatPercent
import ua.syt0r.kanji.presentation.common.theme.extraColorScheme
import ua.syt0r.kanji.presentation.common.ui.AutoSizeText
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.FsrsStats
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.PassFail
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StabilityBucket
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsData
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.StatsFilter

fun LazyStaggeredGridScope.fsrsStatsItems(
    statsData: StatsData,
    strings: StatsStrings
) {
    val filter = statsData.selectedFilter.value
    val stats = statsData.fsrsStats.getValue(filter)

    item(span = StaggeredGridItemSpan.FullLine) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.fsrsTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.fsrsDesiredRetention(formatPercent(stats.desiredRetention, 0)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            StatsFilter.entries.forEach {
                FilterChip(
                    selected = it == filter,
                    onClick = { statsData.selectedFilter.value = it },
                    label = {
                        Text(
                            when (it) {
                                StatsFilter.All -> strings.fsrsFilterAll
                                StatsFilter.Letters -> strings.fsrsFilterLetters
                                StatsFilter.Words -> strings.fsrsFilterWords
                            }
                        )
                    }
                )
            }
        }
    }

    if (stats.cardCounts.total == 0) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Card {
                Text(
                    text = strings.fsrsNoData,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    item {
        SmallInfoCard(
            title = formatPercent(stats.averageRetrievability),
            subtitle = strings.fsrsAverageRetrievability
        )
    }
    item {
        SmallInfoCard(
            title = formatDecimal(stats.estimatedKnown, 0),
            subtitle = strings.fsrsEstimatedKnown
        )
    }
    item {
        SmallInfoCard(
            title = stats.averageStability?.let(strings.fsrsFormattedDays) ?: "-",
            subtitle = strings.fsrsAverageStability
        )
    }
    item {
        SmallInfoCard(
            title = formatPercent(stats.averageDifficulty, 0),
            subtitle = strings.fsrsAverageDifficulty
        )
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        CardCountsCard(stats, strings)
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        TrueRetentionCard(stats, strings)
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        ChartCard(title = strings.fsrsForecastTitle) {
            val average = stats.forecast.average().takeIf { !it.isNaN() } ?: 0.0
            Text(
                text = strings.fsrsForecastSummary(
                    stats.overdue,
                    stats.forecast.getOrElse(1) { 0 },
                    formatDecimal(average, 1)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            StatsBarChart(
                values = stats.forecast,
                selectionText = { index ->
                    strings.fsrsChartSelection(strings.fsrsForecastDay(index), stats.forecast[index])
                },
                hint = strings.fsrsChartHint,
                axisLabels = listOf(
                    strings.fsrsForecastDay(0),
                    strings.fsrsForecastDay(stats.forecast.size / 2),
                    strings.fsrsForecastDay(stats.forecast.size - 1)
                )
            )
        }
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        ChartCard(title = strings.fsrsRetrievabilityTitle) {
            StatsBarChart(
                values = stats.retrievabilityDistribution,
                selectionText = { index ->
                    strings.fsrsChartSelection(
                        "${index * 10}-${(index + 1) * 10}%",
                        stats.retrievabilityDistribution[index]
                    )
                },
                hint = strings.fsrsChartHint,
                axisLabels = listOf("0%", "50%", "100%"),
                barColor = MaterialTheme.colorScheme.secondary
            )
        }
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        ChartCard(title = strings.fsrsStabilityTitle) {
            val buckets = StabilityBucket.entries
            val values = buckets.map { stats.stabilityDistribution.getValue(it) }
            StatsBarChart(
                values = values,
                selectionText = { index ->
                    strings.fsrsChartSelection(strings.fsrsStabilityBucket(buckets[index]), values[index])
                },
                hint = strings.fsrsChartHint,
                axisLabels = buckets.map { strings.fsrsStabilityBucket(it) }
            )
        }
    }

    item(span = StaggeredGridItemSpan.FullLine) {
        ChartCard(title = strings.fsrsDifficultyTitle) {
            StatsBarChart(
                values = stats.difficultyDistribution,
                selectionText = { index ->
                    strings.fsrsChartSelection(
                        "${index * 10}-${(index + 1) * 10}%",
                        stats.difficultyDistribution[index]
                    )
                },
                hint = strings.fsrsChartHint,
                axisLabels = listOf("0%", "50%", "100%"),
                barColor = MaterialTheme.colorScheme.error
            )
        }
    }

}

@Composable
private fun SmallInfoCard(title: String, subtitle: String) {
    Card {
        Column(Modifier.padding(16.dp)) {
            AutoSizeText(
                text = title,
                style = MaterialTheme.typography.headlineMedium
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun CardCountsCard(stats: FsrsStats, strings: StatsStrings) {
    val counts = stats.cardCounts
    val learningColor = MaterialTheme.extraColorScheme.due
    val youngColor = MaterialTheme.extraColorScheme.new
    val matureColor = MaterialTheme.extraColorScheme.success

    ChartCard(title = strings.fsrsCardsTitle) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            listOf(
                counts.learning to learningColor,
                counts.young to youngColor,
                counts.mature to matureColor
            ).filter { it.first > 0 }.forEach { (count, color) ->
                Box(
                    modifier = Modifier.weight(count.toFloat())
                        .height(16.dp)
                        .background(color)
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LegendItem(learningColor, strings.fsrsLearning, counts.learning, counts.total)
            LegendItem(youngColor, strings.fsrsYoung, counts.young, counts.total)
            LegendItem(matureColor, strings.fsrsMature, counts.mature, counts.total)
        }
        Text(
            text = "${strings.fsrsTotal}: ${counts.total}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String, count: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = "$count (${formatPercent(count.toDouble() / total.coerceAtLeast(1), 0)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrueRetentionCard(stats: FsrsStats, strings: StatsStrings) {
    ChartCard(title = strings.fsrsTrueRetentionTitle) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RetentionTableRow(
                cells = listOf(
                    "",
                    strings.fsrsYoung,
                    strings.fsrsMature,
                    strings.fsrsTotal,
                    strings.fsrsPredicted
                ),
                isHeader = true
            )
            stats.trueRetention.forEach { row ->
                RetentionTableRow(
                    cells = listOf(
                        strings.fsrsPeriod(row.period),
                        row.young.cellText(),
                        row.mature.cellText(),
                        row.total.cellText(),
                        formatPercent(row.predicted)
                    ),
                    isHeader = false
                )
            }
        }
        Text(
            text = strings.fsrsTrueRetentionHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun PassFail.cellText(): String = when (total) {
    0 -> "-"
    else -> "${formatPercent(rate)}\n($total)"
}

@Composable
private fun RetentionTableRow(cells: List<String>, isHeader: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { index, text ->
            Text(
                text = text,
                style = if (isHeader) MaterialTheme.typography.labelSmall
                else MaterialTheme.typography.bodySmall,
                maxLines = if (isHeader) 1 else 2,
                softWrap = !isHeader,
                fontWeight = if (isHeader || index == 0) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.weight(if (index == 0) 1.3f else 1f)
            )
        }
    }
}
