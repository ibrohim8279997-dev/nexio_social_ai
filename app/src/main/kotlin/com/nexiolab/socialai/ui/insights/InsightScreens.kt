package com.nexiolab.socialai.ui.insights

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.ai.ExpenseSummary
import com.nexiolab.socialai.domain.model.CostConfidence
import com.nexiolab.socialai.domain.model.ExpenseRecord
import com.nexiolab.socialai.domain.model.MonthlyBudget
import com.nexiolab.socialai.domain.model.RequestType
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.BarChart
import com.nexiolab.socialai.ui.components.ChartValue
import com.nexiolab.socialai.ui.components.DonutChart
import com.nexiolab.socialai.ui.components.EmptyState
import com.nexiolab.socialai.ui.components.LineChart
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.NexioProgressBar
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.components.StatTile
import com.nexiolab.socialai.ui.components.StatusChip
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import com.nexiolab.socialai.work.Jobs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle

enum class Period(@StringRes val label: Int) {
    DAY(R.string.insights_period_day),
    WEEK(R.string.insights_period_week),
    MONTH(R.string.insights_period_month),
    YEAR(R.string.insights_period_year),
}

class ExpensesViewModel(private val app: AppContainer) : BaseViewModel() {
    private val zone = ZoneId.systemDefault()
    val period = MutableStateFlow(Period.MONTH)
    private val month = YearMonth.now()

    private fun start(p: Period): Long = when (p) {
        Period.DAY -> LocalDate.now()
        Period.WEEK -> LocalDate.now().minusDays(6)
        Period.MONTH -> month.atDay(1)
        Period.YEAR -> LocalDate.now().withDayOfYear(1)
    }.atStartOfDay(zone).toInstant().toEpochMilli()

    val rows = period.flatMapLatest { app.expenses.observeSince(start(it)) }.state(emptyList())
    val summary = combine(rows, period) { list, _ -> ExpenseSummary.of(list) }.state(ExpenseSummary.of(emptyList()))
    val budget = app.expenses.observeBudget(month.toString()).state(null)
    val monthRows = app.expenses.observeSince(start(Period.MONTH)).state(emptyList())

    /** Cumulative spend per day, so the line reads against the budget. */
    fun trend(list: List<ExpenseRecord>, p: Period): List<ChartValue> {
        if (list.isEmpty()) return emptyList()
        val first = Instant.ofEpochMilli(start(p)).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(first, LocalDate.now()).toInt()
        var sum = 0.0
        return (0..days).map { offset ->
            val day = first.plusDays(offset.toLong())
            sum += list.filter { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == day }.sumOf { it.amount }
            ChartValue(day.toString(), sum)
        }
    }

    fun saveBudget(budget: Double, warning: Int, max: Double?) = act(app.context.getString(R.string.insights_budget_saved)) {
        app.expenses.saveBudget(MonthlyBudget(MonthlyBudget.DEFAULT_KEY, budget, warning.coerceIn(1, 100), max))
    }
}

@Composable
fun ExpensesScreen() {
    val vm = appViewModel { ExpensesViewModel(it) }
    Messages(vm)
    val period by vm.period.collectAsState()
    val summary by vm.summary.collectAsState()
    val rows by vm.rows.collectAsState()
    val budget by vm.budget.collectAsState()
    val monthRows by vm.monthRows.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val money = { v: Double -> Format.money(v) }
    ScreenColumn {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { FilterChip(it == period, { vm.period.value = it }, { Text(stringResource(it.label)) }) }
            }
        }
        item {
            NexioCard {
                Text(stringResource(R.string.insights_spent), style = MaterialTheme.typography.titleSmall, color = Nexio.colors.textMuted)
                Text(money(summary.total), style = MaterialTheme.typography.headlineMedium, color = Nexio.colors.text)
                Text(
                    stringResource(R.string.insights_measured_estimated, money(summary.measured), money(summary.estimated)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nexio.colors.textMuted,
                )
                if (summary.unpricedRequests > 0) {
                    Gap(4)
                    Text(
                        stringResource(R.string.insights_unpriced_requests, summary.unpricedRequests),
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.Warning,
                    )
                }
                if (rows.filter { it.amount != 0.0 }.map { it.currency }.distinct().size > 1) {
                    Gap(4)
                    Text(
                        stringResource(R.string.insights_mixed_currencies),
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.Warning,
                    )
                }
                Gap(4)
                Text(stringResource(R.string.insights_measured_explainer), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
            }
        }
        item {
            val spent = monthRows.sumOf { it.amount }
            NexioCard(onClick = { editing = true }) {
                SectionHeader(stringResource(R.string.insights_monthly_budget), action = stringResource(R.string.insights_edit)) { editing = true }
                val current = budget
                if (current == null) {
                    Text(stringResource(R.string.insights_no_budget), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                } else {
                    Gap(4)
                    NexioProgressBar((spent / current.budget).toFloat())
                    Gap(4)
                    Text(
                        stringResource(R.string.insights_budget_progress, money(spent), money(current.budget), current.warningPercent) +
                            (current.maxSpending?.let { " " + stringResource(R.string.insights_requests_stop_at, money(it)) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Nexio.colors.textMuted,
                    )
                }
            }
        }
        if (rows.isEmpty()) {
            item { EmptyState(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.insights_no_ai_requests), stringResource(R.string.insights_every_request_listed)) }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.insights_cost_per_image), summary.costPerImage?.let(money) ?: stringResource(R.string.insights_not_available), Icons.Filled.Image, Modifier.weight(1f))
                    StatTile(stringResource(R.string.insights_cost_per_video), summary.costPerVideo?.let(money) ?: stringResource(R.string.insights_not_available), Icons.Filled.PlayCircle, Modifier.weight(1f), Brand.Primary)
                    StatTile(stringResource(R.string.insights_requests), summary.requests.toString(), Icons.Filled.AttachMoney, Modifier.weight(1f), Brand.Success)
                }
            }
            item {
                NexioCard {
                    SectionHeader(stringResource(R.string.insights_spending_over_time))
                    Gap(4)
                    LineChart(vm.trend(rows, period), format = money)
                }
            }
            item { Breakdown(stringResource(R.string.insights_by_provider), summary.byProvider, money) }
            item { Breakdown(stringResource(R.string.insights_by_model), summary.byModel, money) }
            item { Breakdown(stringResource(R.string.insights_by_agent), summary.byAgent, money) }
            item {
                NexioCard {
                    SectionHeader(stringResource(R.string.insights_usage))
                    Gap(4)
                    listOf(
                        stringResource(R.string.insights_input_tokens) to Format.number(summary.inputTokens),
                        stringResource(R.string.insights_output_tokens) to Format.number(summary.outputTokens),
                        stringResource(R.string.insights_images) to summary.images.toString(),
                        stringResource(R.string.insights_videos) to stringResource(R.string.insights_videos_value, summary.videos, summary.videoSeconds.toInt()),
                        stringResource(R.string.insights_failed_requests) to summary.failedRequests.toString(),
                    ).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                            Text(value, style = MaterialTheme.typography.labelMedium, color = Nexio.colors.text)
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.insights_recent_requests)) }
            val recent = rows.take(RECENT)
            items(recent.size, key = { recent[it].id }) { i -> RequestRow(recent[i]) }
        }
    }
    if (editing) BudgetDialog(budget, { editing = false }) { b, w, m ->
        vm.saveBudget(b, w, m)
        editing = false
    }
}

@Composable
private fun Breakdown(title: String, values: Map<String, Double>, format: (Double) -> String) {
    NexioCard {
        SectionHeader(title)
        Gap(4)
        DonutChart(values.map { ChartValue(it.key, it.value) }, format = format)
    }
}

@Composable
private fun RequestRow(row: ExpenseRecord) {
    NexioCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(row.modelId, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    requestSubtitle(row),
                    style = MaterialTheme.typography.labelSmall,
                    color = Nexio.colors.textMuted,
                )
            }
            when {
                !row.succeeded -> StatusChip(stringResource(R.string.insights_failed_status, row.errorCode.orEmpty()), Brand.Danger)
                row.confidence == CostConfidence.ESTIMATED -> StatusChip(stringResource(R.string.insights_estimated_status, Format.money(row.amount, row.currency)), Brand.Warning)
                row.confidence == CostConfidence.UNPRICED -> StatusChip(stringResource(R.string.insights_no_price), Brand.Warning)
                else -> Text(Format.money(row.amount, row.currency), style = MaterialTheme.typography.labelMedium, color = Nexio.colors.text)
            }
        }
    }
}

@Composable
private fun requestTypeLabel(type: RequestType): String = stringResource(
    when (type) {
        RequestType.TEXT -> R.string.insights_request_type_text
        RequestType.VISION -> R.string.insights_request_type_vision
        RequestType.IMAGE -> R.string.insights_request_type_image
        RequestType.VIDEO -> R.string.insights_request_type_video
        RequestType.SPEECH -> R.string.insights_request_type_speech
        RequestType.CONNECTION_TEST -> R.string.insights_request_type_connection_test
    },
)

/** Agent, request type, usage that applies to this request, then the date. */
@Composable
private fun requestSubtitle(row: ExpenseRecord): String {
    val parts = buildList {
        add(row.agentRole?.title ?: row.providerName)
        add(requestTypeLabel(row.requestType))
        if (row.inputTokens > 0 || row.outputTokens > 0) {
            add(stringResource(R.string.insights_tokens_in_out, Format.number(row.inputTokens), Format.number(row.outputTokens)))
        }
        if (row.imageCount > 0) add(stringResource(R.string.insights_row_images, row.imageCount))
        if (row.videoSeconds > 0) add(stringResource(R.string.insights_row_video_seconds, row.videoSeconds.toInt()))
        if (row.characters > 0) add(stringResource(R.string.insights_row_characters, Format.number(row.characters)))
        add(Format.dateTime(row.createdAt))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun BudgetDialog(current: MonthlyBudget?, dismiss: () -> Unit, save: (Double, Int, Double?) -> Unit) {
    var budget by remember { mutableStateOf(current?.budget?.toString().orEmpty()) }
    var warning by remember { mutableStateOf((current?.warningPercent ?: 80).toString()) }
    var max by remember { mutableStateOf(current?.maxSpending?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.insights_monthly_budget)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Field(stringResource(R.string.insights_monthly_budget), budget, { budget = it })
                Field(stringResource(R.string.insights_warn_at_percent), warning, { warning = it })
                Field(stringResource(R.string.insights_stop_paid_requests), max, { max = it })
            }
        },
        confirmButton = {
            TextButton(
                { save(budget.toDouble(), warning.toIntOrNull() ?: 80, max.toDoubleOrNull()) },
                enabled = (budget.toDoubleOrNull() ?: 0.0) > 0,
            ) { Text(stringResource(R.string.insights_save)) }
        },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.insights_cancel)) } },
    )
}

data class PostInsight(val mediaId: String, val metrics: Map<String, Double>)

class AnalyticsViewModel(private val app: AppContainer) : BaseViewModel() {
    private val since = LocalDate.now().minusDays(WINDOW_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Instagram reports running totals, so only each post's latest reading counts. */
    val posts = app.db.analytics().observeSince(since).let { flow ->
        combine(flow, MutableStateFlow(Unit)) { rows, _ ->
            rows.filter { it.mediaId != null }.groupBy { it.mediaId!! }.map { (id, list) ->
                PostInsight(id, list.groupBy { it.metric }.mapValues { (_, values) -> values.maxBy { it.capturedAt }.value })
            }
        }
    }.state(emptyList())

    val connected = app.instagram.observeAccount().state(null)

    fun refresh() = act(app.context.getString(R.string.insights_fetching_figures)) { Jobs.analytics(app.context) }

    fun analyse(totals: Map<String, Double>) = act(app.context.getString(R.string.insights_agent_on_it)) {
        val figures = totals.entries.joinToString { "${it.key}: ${it.value.toLong()}" }
        val id = app.engine.createInstructionTask(
            "Analyse our Instagram performance and recommend next steps. Totals across ${posts.value.size} recent posts: $figures. Use only these figures.",
        )
        Jobs.instruction(app.context, id)
    }

    private companion object {
        const val WINDOW_DAYS = 90L
    }
}

@Composable
fun AnalyticsScreen() {
    val vm = appViewModel { AnalyticsViewModel(it) }
    Messages(vm)
    val posts by vm.posts.collectAsState()
    val busy by vm.busy.collectAsState()
    var metric by rememberSaveable { mutableStateOf("reach") }
    val totals = posts.flatMap { it.metrics.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    ScreenColumn {
        item { BusyButton(stringResource(R.string.insights_get_latest_figures), busy) { vm.refresh() } }
        if (posts.isEmpty()) {
            item { EmptyState(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.insights_no_figures_yet), stringResource(R.string.insights_figures_appear_here)) }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    totals.keys.sorted().take(MAX_CHIPS).forEach { key ->
                        FilterChip(key == metric, { metric = key }, { Text(metricName(key)) })
                    }
                }
            }
            item {
                NexioCard {
                    SectionHeader(stringResource(R.string.insights_metric_by_post, metricName(metric)))
                    Gap(4)
                    BarChart(posts.takeLast(MAX_BARS).mapIndexed { i, p -> ChartValue("#${i + 1}", p.metrics[metric] ?: 0.0) })
                }
            }
            item {
                NexioCard {
                    SectionHeader(stringResource(R.string.insights_totals))
                    totals.toSortedMap().forEach { (name, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(metricName(name), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                            Text(Format.number(value.toLong()), style = MaterialTheme.typography.labelMedium, color = Nexio.colors.text)
                        }
                    }
                }
            }
            item { OutlinedButton({ vm.analyse(totals) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.insights_ask_analytics_agent)) } }
        }
    }
}

/** Localized Instagram metric name; unknown metrics fall back to the raw key, capitalised. */
@Composable
private fun metricName(key: String): String = when (key) {
    "reach" -> stringResource(R.string.insights_metric_reach)
    "likes" -> stringResource(R.string.insights_metric_likes)
    "comments" -> stringResource(R.string.insights_metric_comments)
    "saved" -> stringResource(R.string.insights_metric_saved)
    "shares" -> stringResource(R.string.insights_metric_shares)
    "views" -> stringResource(R.string.insights_metric_views)
    else -> key.replaceFirstChar(Char::uppercase)
}

private const val RECENT = 20
private const val MAX_CHIPS = 4
private const val MAX_BARS = 12
