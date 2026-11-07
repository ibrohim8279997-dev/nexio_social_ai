package com.nexiolab.socialai.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.model.ActivityEntry
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.AgentTask
import com.nexiolab.socialai.domain.model.AppNotice
import com.nexiolab.socialai.domain.model.BrandProfile
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.CostConfidence
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.model.MediaSource
import com.nexiolab.socialai.domain.model.NoticeSeverity
import com.nexiolab.socialai.domain.model.TaskStatus
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.BarChart
import com.nexiolab.socialai.ui.components.ChartValue
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.NexioProgressBar
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.components.StatTile
import com.nexiolab.socialai.ui.components.StatusChip
import com.nexiolab.socialai.ui.navigation.Routes
import com.nexiolab.socialai.ui.navigation.Section
import com.nexiolab.socialai.ui.navigation.Tab
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class DashboardState(
    val brand: BrandProfile? = null,
    val active: List<AgentTask> = emptyList(),
    val hasProvider: Boolean = true,
    val counts: Map<ContentStatus, Int> = emptyMap(),
    val monthItems: List<ContentItem> = emptyList(),
    val upcoming: List<ContentItem> = emptyList(),
    val videos: Int = 0,
    val images: Int = 0,
    val monthSpend: Double = 0.0,
    /** Successful requests this month whose model had no price; they are not in [monthSpend]. */
    val unpricedRequests: Int = 0,
    val budget: Double? = null,
    val currency: String = "USD",
    val reachByDay: List<ChartValue> = emptyList(),
    val activity: List<ActivityEntry> = emptyList(),
    val notices: List<AppNotice> = emptyList(),
)

class DashboardViewModel(private val app: AppContainer) : BaseViewModel() {
    private val zone = ZoneId.systemDefault()
    private val month = YearMonth.now()
    private val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val weekStart = LocalDate.now().minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()

    private val content = combine(
        app.content.observeStatusCounts(),
        app.content.observeBetween(monthStart, monthEnd),
        app.content.observeUpcoming(System.currentTimeMillis(), 3),
    ) { counts, items, upcoming -> Triple(counts, items, upcoming) }

    private data class MonthSpend(val total: Double, val budget: Double?, val currency: String, val unpriced: Int)

    private val spend = combine(app.expenses.observeSince(monthStart), app.expenses.observeBudget(month.toString())) { rows, budget ->
        MonthSpend(
            rows.sumOf { it.amount }, budget?.budget, rows.firstOrNull()?.currency ?: budget?.currency ?: "USD",
            rows.count { it.succeeded && it.confidence == CostConfidence.UNPRICED },
        )
    }

    private val reach = app.db.analytics().observeSince(weekStart)

    private val head = combine(app.brand.observe(), app.agents.observeActive(), app.providers.observeProviders()) { brand, active, providers ->
        Triple(brand, active, providers.isNotEmpty())
    }

    private val feed = combine(app.media.observeAll(), app.activity.observeActivity(6), app.activity.observeNotices()) { media, log, notices ->
        Triple(media, log, notices)
    }

    val state = combine(head, content, spend, feed, reach) { h, c, s, f, records ->
        val days = (6L downTo 0).map(LocalDate.now()::minusDays)
        val reachByDay = if (records.isEmpty()) {
            emptyList()
        } else {
            days.map { day ->
                val total = records.filter { it.metric == "reach" && Instant.ofEpochMilli(it.capturedAt).atZone(zone).toLocalDate() == day }.sumOf { it.value }
                ChartValue(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), total)
            }
        }
        DashboardState(
            brand = h.first, active = h.second, hasProvider = h.third,
            counts = c.first, monthItems = c.second, upcoming = c.third,
            monthSpend = s.total, unpricedRequests = s.unpriced, budget = s.budget, currency = s.currency,
            videos = f.first.count { it.kind == MediaKind.VIDEO && it.source != MediaSource.UPLOADED },
            images = f.first.count { it.kind == MediaKind.IMAGE && it.source == MediaSource.AI_GENERATED },
            activity = f.second, notices = f.third, reachByDay = reachByDay,
        )
    }.state(DashboardState())

    fun dismiss(notice: AppNotice) = act { app.activity.dismiss(notice.id) }

    /** True only the first time, so the permission prompt is shown once. */
    suspend fun firstNotificationRequest(): Boolean {
        if (app.settings.get(NOTIFICATIONS_ASKED) != null) return false
        app.settings.set(NOTIFICATIONS_ASKED, "true")
        return true
    }

    private companion object {
        const val NOTIFICATIONS_ASKED = "notifications_asked"
    }
}

@Composable
fun DashboardScreen(open: (String) -> Unit) {
    val vm = appViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val interviewed = state.brand?.interviewCompletedAt != null
    LaunchedEffect(interviewed) {
        val missing = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (interviewed && missing && vm.firstNotificationRequest()) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    ScreenColumn {
        setupCard(state, open)
        item { AgentStatus(state, open) }
        items(state.notices.size, key = { "n" + state.notices[it].id }) { i -> Notice(state.notices[i]) { vm.dismiss(state.notices[i]) } }
        item { Stats(state, open) }
        item { Progress(state) }
        item { Spending(state, open) }
        if (state.reachByDay.isNotEmpty()) {
            item {
                NexioCard {
                    Text(stringResource(R.string.shell_reach_7_days), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                    Gap()
                    BarChart(state.reachByDay)
                }
            }
        }
        item { Upcoming(state, open) }
        item { QuickActions(open) }
        item { Recent(state) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.setupCard(state: DashboardState, open: (String) -> Unit) {
    val profile = state.brand
    val thisMonth = YearMonth.now().toString()
    val card: Triple<Int, Int, String>? = when {
        profile?.interviewCompletedAt == null -> Triple(R.string.shell_setup_interview_title, R.string.shell_setup_interview_text, Routes.INTERVIEW)
        !state.hasProvider -> Triple(R.string.shell_setup_provider_title, R.string.shell_setup_provider_text, Section.Api.route)
        profile.lastMonthlyReview != thisMonth -> Triple(R.string.shell_setup_monthly_title, R.string.shell_setup_monthly_text, Routes.MONTHLY)
        else -> null
    }
    if (card != null) {
        item {
            NexioCard(onClick = { open(card.third) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(card.first), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                        Text(stringResource(card.second), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Brand.Accent)
                }
            }
        }
    }
}

@Composable
private fun AgentStatus(state: DashboardState, open: (String) -> Unit) {
    val running = state.active.firstOrNull { it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED || it.status == TaskStatus.NEEDS_CORRECTION }
    val waiting = state.active.count { it.status == TaskStatus.WAITING_APPROVAL }
    val (title, detail, color) = when {
        running != null -> Triple(stringResource(R.string.shell_agent_working, running.role.title), running.title, Brand.Success)
        waiting > 0 -> Triple(stringResource(R.string.shell_agent_needs_review), stringResource(R.string.shell_agent_waiting, waiting), Brand.Warning)
        else -> Triple(stringResource(R.string.shell_agent_idle, AgentRole.MAIN.title), stringResource(R.string.shell_agent_no_task), Nexio.colors.textMuted)
    }
    NexioCard(onClick = { open(Tab.Agents.route) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.nexio_mark), null, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Nexio.colors.textMuted)
        }
    }
}

@Composable
private fun Notice(notice: AppNotice, dismiss: () -> Unit) {
    val color = when (notice.severity) {
        NoticeSeverity.INFO -> Brand.Accent
        NoticeSeverity.WARNING -> Brand.Warning
        NoticeSeverity.ERROR -> Brand.Danger
    }
    NexioCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                StatusChip(
                    stringResource(
                        when (notice.severity) {
                            NoticeSeverity.INFO -> R.string.shell_severity_info
                            NoticeSeverity.WARNING -> R.string.shell_severity_warning
                            NoticeSeverity.ERROR -> R.string.shell_severity_error
                        },
                    ),
                    color,
                )
                Gap(4)
                Text(notice.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                Text(notice.body, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
            }
            IconButton(dismiss) { Icon(Icons.Filled.Close, stringResource(R.string.shell_dismiss), tint = Nexio.colors.textMuted) }
        }
    }
}

@Composable
private fun Stats(state: DashboardState, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stringResource(R.string.shell_stat_videos), state.videos.toString(), Icons.Filled.PlayCircle, Modifier.weight(1f), Brand.Primary) { open(Tab.Video.route) }
            StatTile(stringResource(R.string.shell_stat_images), state.images.toString(), Icons.Filled.Image, Modifier.weight(1f)) { open(Section.Media.route) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stringResource(R.string.shell_stat_published), (state.counts[ContentStatus.PUBLISHED] ?: 0).toString(), Icons.Filled.CheckCircle, Modifier.weight(1f), Brand.Success) { open(Tab.Calendar.route) }
            StatTile(stringResource(R.string.shell_stat_scheduled), (state.counts[ContentStatus.SCHEDULED] ?: 0).toString(), Icons.Filled.Schedule, Modifier.weight(1f), Brand.Warning) { open(Tab.Calendar.route) }
        }
    }
}

@Composable
private fun Progress(state: DashboardState) {
    val total = state.monthItems.size
    val done = state.monthItems.count { it.status == ContentStatus.PUBLISHED }
    val fraction = if (total > 0) done.toFloat() / total else 0f
    NexioCard {
        Row {
            Text(stringResource(R.string.shell_monthly_content), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.shell_monthly_published, done, total), style = MaterialTheme.typography.labelMedium, color = Nexio.colors.textMuted)
        }
        Gap()
        NexioProgressBar(fraction)
    }
}

@Composable
private fun Spending(state: DashboardState, open: (String) -> Unit) {
    NexioCard(onClick = { open(Section.Expenses.route) }) {
        Row {
            Text(stringResource(R.string.shell_expenses_month), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text, modifier = Modifier.weight(1f))
            Text(Format.money(state.monthSpend, state.currency), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
        }
        if (state.unpricedRequests > 0) {
            Gap(4)
            Text(stringResource(R.string.shell_unpriced_requests, state.unpricedRequests), style = MaterialTheme.typography.labelSmall, color = Brand.Warning)
        }
        state.budget?.takeIf { it > 0 }?.let { budget ->
            Gap()
            NexioProgressBar((state.monthSpend / budget).toFloat())
            Gap(4)
            Text(stringResource(R.string.shell_of_budget, Format.money(budget, state.currency)), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
        }
    }
}

@Composable
private fun Upcoming(state: DashboardState, open: (String) -> Unit) {
    NexioCard {
        SectionHeader(stringResource(R.string.shell_upcoming_posts), action = stringResource(R.string.shell_see_all)) { open(Tab.Calendar.route) }
        if (state.upcoming.isEmpty()) {
            Text(stringResource(R.string.shell_nothing_scheduled), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
        }
        state.upcoming.forEach { item ->
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.scheduledAt?.let { Text(Format.dateTime(it), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted) }
                }
                StatusChip(item.status.label, Brand.Accent)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(open: (String) -> Unit) {
    val actions = listOf(
        Triple(R.string.shell_action_create_content, Icons.Filled.AutoAwesome, Routes.content(0)),
        Triple(R.string.shell_action_generate_video, Icons.Filled.PlayCircle, Tab.Video.route),
        Triple(R.string.shell_action_open_calendar, Icons.Filled.CalendarMonth, Tab.Calendar.route),
        Triple(R.string.shell_action_ai_agents, Icons.Filled.SmartToy, Tab.Agents.route),
        Triple(R.string.shell_action_view_expenses, Icons.Filled.AccountBalanceWallet, Section.Expenses.route),
        Triple(R.string.shell_action_publish_instagram, Icons.Filled.PhotoCamera, Section.Instagram.route),
    )
    NexioCard {
        Text(stringResource(R.string.shell_quick_actions), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (label, icon, route) ->
                AssistChip(onClick = { open(route) }, label = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
            }
        }
    }
}

@Composable
private fun Recent(state: DashboardState) {
    if (state.activity.isEmpty()) return
    NexioCard {
        Text(stringResource(R.string.shell_recent_activity), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
        state.activity.forEach { entry ->
            Gap(6)
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
            if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Format.dateTime(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
        }
    }
}
