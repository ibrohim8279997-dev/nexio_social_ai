package com.nexiolab.socialai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.R
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio

/** Categorical order validated against the card surface for colour-vision deficiency. Fixed, never cycled. */
private val Categorical = listOf(Color(0xFF2496EE), Color(0xFFC47A12), Color(0xFF9670E8), Color(0xFF149482))
private val OtherSlice = Color(0xFF6B7F99)

data class ChartValue(val label: String, val value: Double)

/** Beyond four categories the rest fold into Other, so no generated hue ever appears. */
private fun foldOther(values: List<ChartValue>, otherLabel: String): List<ChartValue> {
    if (values.size <= Categorical.size) return values
    val top = values.take(Categorical.size)
    return top + ChartValue(otherLabel, values.drop(Categorical.size).sumOf { it.value })
}

@Composable
fun BarChart(values: List<ChartValue>, modifier: Modifier = Modifier, format: (Double) -> String = { "%.0f".format(it) }) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val max = (values.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1e-9)
    val grid = Nexio.colors.outline
    val tapHint = stringResource(R.string.common_chart_tap_bar)
    Column(modifier.fillMaxWidth()) {
        Text(
            selected?.let { "${values[it].label}: ${format(values[it].value)}" } ?: tapHint,
            style = MaterialTheme.typography.labelSmall,
            color = Nexio.colors.textMuted,
        )
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp)
                .semantics { contentDescription = values.joinToString { "${it.label} ${format(it.value)}" } }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        selected = (offset.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val width = (slot - 2.dp.toPx()).coerceAtMost(28.dp.toPx())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            values.forEachIndexed { i, item ->
                val height = (item.value / max * size.height).toFloat()
                if (height > 0f) {
                    drawRoundRect(
                        color = if (selected == null || selected == i) Brand.Primary else Brand.Primary.copy(alpha = 0.45f),
                        topLeft = Offset(slot * i + (slot - width) / 2, size.height - height),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            values.forEach {
                Text(
                    it.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Nexio.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun DonutChart(values: List<ChartValue>, modifier: Modifier = Modifier, format: (Double) -> String) {
    val slices = foldOther(values.filter { it.value > 0 }, stringResource(R.string.common_chart_other))
    val total = slices.sumOf { it.value }.coerceAtLeast(1e-9)
    val gap = Nexio.colors.card
    fun color(i: Int) = Categorical.getOrElse(i) { OtherSlice }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, slice ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(color(i), RoundedCornerShape(3.dp)))
                    Text(
                        slice.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Nexio.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(format(slice.value), style = MaterialTheme.typography.labelMedium, color = Nexio.colors.text)
                }
            }
        }
        Canvas(Modifier.size(96.dp).semantics { contentDescription = slices.joinToString { "${it.label} ${format(it.value)}" } }) {
            val stroke = 16.dp.toPx()
            var start = -90f
            slices.forEachIndexed { i, slice ->
                val sweep = (slice.value / total * 360).toFloat()
                drawArc(
                    color = color(i),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke),
                )
                if (slices.size > 1) {
                    drawArc(gap, start - 1f, 2f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                }
                start += sweep
            }
        }
    }
}

@Composable
fun LineChart(values: List<ChartValue>, modifier: Modifier = Modifier, format: (Double) -> String) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val max = (values.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1e-9)
    val grid = Nexio.colors.outline
    val ring = Nexio.colors.card
    Column(modifier.fillMaxWidth()) {
        Text(
            (selected ?: values.lastIndex.takeIf { it >= 0 })?.let { "${values[it].label}: ${format(values[it].value)}" }.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = Nexio.colors.textMuted,
        )
        Canvas(
            Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp)
                .semantics { contentDescription = values.joinToString { "${it.label} ${format(it.value)}" } }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        if (values.size > 1) {
                            selected = (offset.x / size.width * (values.size - 1) + 0.5f).toInt().coerceIn(0, values.lastIndex)
                        }
                    }
                },
        ) {
            if (values.isEmpty()) return@Canvas
            val step = if (values.size > 1) size.width / (values.size - 1) else 0f
            fun point(i: Int) = Offset(step * i, size.height - (values[i].value / max * size.height).toFloat())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            val line = Path().apply {
                moveTo(point(0).x, point(0).y)
                values.indices.drop(1).forEach { lineTo(point(it).x, point(it).y) }
            }
            drawPath(line, Brand.Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val marker = selected ?: values.lastIndex
            drawCircle(ring, 6.dp.toPx(), point(marker))
            drawCircle(Brand.Accent, 4.dp.toPx(), point(marker))
        }
    }
}
