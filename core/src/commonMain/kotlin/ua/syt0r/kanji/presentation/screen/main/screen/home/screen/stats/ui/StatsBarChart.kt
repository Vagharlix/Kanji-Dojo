package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.stats.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Simple interactive bar chart: tap a bar to see its label and value.
 *
 * @param selectionText text shown for the selected bar
 * @param axisLabels labels spread evenly under the chart (e.g. first, middle, last)
 */
@Composable
fun StatsBarChart(
    values: List<Int>,
    selectionText: (index: Int) -> String,
    hint: String,
    axisLabels: List<String>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    selectedBarColor: Color = MaterialTheme.colorScheme.tertiary,
    emptyBarColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    var selectedIndex by remember(values) { mutableStateOf<Int?>(null) }
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = selectedIndex?.let(selectionText) ?: hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (selectedIndex == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )

        Canvas(
            modifier = Modifier.fillMaxWidth()
                .height(chartHeight)
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        if (values.isEmpty()) return@detectTapGestures
                        val index = (offset.x / size.width * values.size).toInt()
                            .coerceIn(0, values.size - 1)
                        selectedIndex = if (selectedIndex == index) null else index
                    }
                }
        ) {
            if (values.isEmpty()) return@Canvas
            val slotWidth = size.width / values.size
            val gap = (slotWidth * 0.2f).coerceAtMost(4.dp.toPx())
            val barWidth = slotWidth - gap
            val minBarHeight = 2.dp.toPx()

            values.forEachIndexed { index, value ->
                val barHeight = when (value) {
                    0 -> minBarHeight
                    else -> (size.height * value / maxValue).coerceAtLeast(minBarHeight)
                }
                val color = when {
                    selectedIndex == index -> selectedBarColor
                    value == 0 -> emptyBarColor
                    else -> barColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * slotWidth + gap / 2, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }

        if (axisLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                axisLabels.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = when (index) {
                            0 -> TextAlign.Start
                            axisLabels.lastIndex -> TextAlign.End
                            else -> TextAlign.Center
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
