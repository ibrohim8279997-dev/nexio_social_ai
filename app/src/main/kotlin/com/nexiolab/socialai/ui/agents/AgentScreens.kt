package com.nexiolab.socialai.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.model.AIModel
import com.nexiolab.socialai.domain.model.Agent
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.AgentTask
import com.nexiolab.socialai.domain.model.ProviderType
import com.nexiolab.socialai.domain.model.TaskStatus
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.EmptyState
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.components.StatusChip
import com.nexiolab.socialai.ui.navigation.Routes
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import com.nexiolab.socialai.work.Jobs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.COMPLETED -> Brand.Success
    TaskStatus.RUNNING, TaskStatus.QUEUED -> Brand.Accent
    TaskStatus.WAITING_APPROVAL, TaskStatus.NEEDS_CORRECTION -> Brand.Warning
    TaskStatus.FAILED -> Brand.Danger
    TaskStatus.CANCELLED -> Color(0xFF91A4BC)
}

@Composable
fun TaskStatus.label(): String = stringResource(
    when (this) {
        TaskStatus.COMPLETED -> R.string.plan_status_completed
        TaskStatus.RUNNING -> R.string.plan_status_running
        TaskStatus.QUEUED -> R.string.plan_status_queued
        TaskStatus.WAITING_APPROVAL -> R.string.plan_status_waiting_approval
        TaskStatus.NEEDS_CORRECTION -> R.string.plan_status_needs_correction
        TaskStatus.FAILED -> R.string.plan_status_failed
        TaskStatus.CANCELLED -> R.string.plan_status_cancelled
    },
)

data class AgentRow(val agent: Agent, val modelName: String?)

class AgentsViewModel(private val app: AppContainer) : BaseViewModel() {
    val rows = combine(app.agents.observeAgents(), app.providers.observeModels()) { agents, models ->
        AgentRole.entries.mapNotNull { role ->
            agents.firstOrNull { it.role == role }?.let { a -> AgentRow(a, models.firstOrNull { it.id == a.modelId }?.displayName) }
        }
    }.state(emptyList())

    val tasks = app.agents.observeTasks(40).state(emptyList())

    fun send(instruction: String, onQueued: () -> Unit) = act {
        val text = instruction.trim()
        if (text.isEmpty()) return@act
        if (app.providers.providers().isEmpty()) {
            throw AiException(AiError.NOT_CONFIGURED, app.context.getString(R.string.plan_no_provider))
        }
        val id = app.engine.createInstructionTask(text)
        Jobs.instruction(app.context, id)
        onQueued()
    }

}

@Composable
fun AgentsScreen(open: (String) -> Unit) {
    val vm = appViewModel { AgentsViewModel(it) }
    Messages(vm)
    val busy by vm.busy.collectAsState()
    val rows by vm.rows.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var instruction by rememberSaveable { mutableStateOf("") }
    ScreenColumn {
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.plan_agents_ask_title))
                Gap(4)
                Field(stringResource(R.string.plan_agents_instruction), instruction, { instruction = it }, singleLine = false, supporting = stringResource(R.string.plan_agents_instruction_hint))
                Gap(4)
                BusyButton(stringResource(R.string.plan_agents_send), busy, enabled = instruction.isNotBlank()) { vm.send(instruction) { instruction = "" } }
            }
        }
        item { SectionHeader(stringResource(R.string.plan_agents_header)) }
        items(rows.size, key = { rows[it].agent.role.name }) { i ->
            val row = rows[i]
            NexioCard(onClick = { open(Routes.agent(row.agent.role)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.agent.role.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                        Text(row.agent.role.summary, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(row.modelName ?: stringResource(R.string.plan_agents_default_model), style = MaterialTheme.typography.labelSmall, color = Brand.Accent)
                    }
                    if (!row.agent.enabled) StatusChip(stringResource(R.string.plan_agents_off), Nexio.colors.textMuted)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.plan_agents_history_title)) }
        if (tasks.isEmpty()) {
            item { EmptyState(Icons.Outlined.AutoAwesome, stringResource(R.string.plan_agents_empty_title), stringResource(R.string.plan_agents_empty_msg)) }
        }
        val top = tasks.filter { it.role != AgentRole.QUALITY_CONTROL }
        items(top.size, key = { "t" + top[it].id }) { i -> TaskRow(top[i]) { open(Routes.task(top[i].id)) } }
    }
}

@Composable
fun TaskRow(task: AgentTask, onClick: () -> Unit) {
    NexioCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${task.role.title} · ${Format.dateTime(task.createdAt)}", style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
            }
            StatusChip(task.status.label(), statusColor(task.status))
        }
    }
}

class TaskViewModel(private val app: AppContainer, private val id: Long) : BaseViewModel() {
    val live = app.agents.observeTasks(200).state(emptyList())

    fun accept() = act(app.context.getString(R.string.plan_task_accepted)) { app.agents.setStatus(id, TaskStatus.COMPLETED) }

    fun retry() = act(app.context.getString(R.string.plan_task_retried)) {
        app.agents.setStatus(id, TaskStatus.QUEUED)
        Jobs.instruction(app.context, id)
    }
}

@Composable
fun TaskScreen(id: Long, open: (String) -> Unit) {
    val vm = appViewModel(key = "task$id") { TaskViewModel(it, id) }
    Messages(vm)
    val all by vm.live.collectAsState()
    val task = all.firstOrNull { it.id == id } ?: return
    val children = all.filter { it.parentId == id }.reversed()
    ScreenColumn {
        item {
            NexioCard {
                StatusChip(task.status.label(), statusColor(task.status))
                Gap(6)
                Text(task.title, style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                Text(task.role.title + if (task.modelUsed.isNotBlank()) " · ${task.modelUsed}" else "", style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
            }
        }
        if (task.reviewNotes.isNotBlank()) {
            item {
                NexioCard {
                    Text(stringResource(if (task.status == TaskStatus.FAILED) R.string.plan_task_what_went_wrong else R.string.plan_task_reviewer_notes), style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
                    Gap(4)
                    Text(task.reviewNotes, style = MaterialTheme.typography.bodyMedium, color = Nexio.colors.text)
                }
            }
        }
        if (task.output.isNotBlank()) {
            item {
                NexioCard {
                    Text(stringResource(R.string.plan_task_result), style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                    Gap(4)
                    SelectionContainer { Text(task.output, style = MaterialTheme.typography.bodyMedium, color = Nexio.colors.text) }
                }
            }
        }
        if (task.status == TaskStatus.WAITING_APPROVAL) {
            item { BusyButton(stringResource(R.string.plan_task_accept), false) { vm.accept() } }
        }
        if (task.role == AgentRole.MAIN && task.status == TaskStatus.FAILED) {
            item { BusyButton(stringResource(R.string.plan_task_retry), false) { vm.retry() } }
        }
        if (children.isNotEmpty()) item { SectionHeader(stringResource(R.string.plan_task_steps)) }
        items(children.size, key = { children[it].id }) { i -> TaskRow(children[i]) { open(Routes.task(children[i].id)) } }
    }
}

class AgentConfigViewModel(private val app: AppContainer, private val role: AgentRole) : BaseViewModel() {
    val agent = flow { emit(app.agents.agent(role)) }.state(null)
    val models = combine(app.providers.observeModels(), app.providers.observeProviders()) { models, providers ->
        models.filter { it.selected }.map { m -> m to providers.firstOrNull { it.id == m.providerId } }
    }.state(emptyList())
    val history = app.agents.observeTasks(role, 50).state(emptyList())

    fun save(agent: Agent) = act(app.context.getString(R.string.plan_saved)) { app.agents.saveAgent(agent) }
}

@Composable
private fun ModelPicker(label: String, models: List<Pair<AIModel, com.nexiolab.socialai.domain.model.AIProvider?>>, selected: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.plan_agent_model_default)
    val name = models.firstOrNull { it.first.id == selected }?.let { (m, p) -> "${p?.name}: ${m.displayName}" } ?: defaultName
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: $name", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(defaultName) }, { onSelect(null); open = false })
            models.forEach { (m, p) ->
                DropdownMenuItem({ Text("${p?.name}: ${m.displayName}") }, { onSelect(m.id); open = false })
            }
        }
    }
}

@Composable
fun AgentConfigScreen(role: AgentRole, open: (String) -> Unit) {
    val vm = appViewModel(key = role.name) { AgentConfigViewModel(it, role) }
    Messages(vm)
    val loaded by vm.agent.collectAsState()
    val models by vm.models.collectAsState()
    val history by vm.history.collectAsState()
    var draft by remember { mutableStateOf<Agent?>(null) }
    LaunchedEffect(loaded) { if (draft == null) draft = loaded }
    val agent = draft ?: return
    val anthropicOnly = models.firstOrNull { it.first.id == agent.modelId }?.second?.type == ProviderType.ANTHROPIC
    ScreenColumn {
        item {
            NexioCard {
                Text(role.summary, style = MaterialTheme.typography.bodyMedium, color = Nexio.colors.textMuted)
                Gap()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.plan_agent_enabled), Modifier.weight(1f), color = Nexio.colors.text)
                    Switch(agent.enabled, { draft = agent.copy(enabled = it) }, enabled = role != AgentRole.MAIN)
                }
                Gap()
                ModelPicker(stringResource(R.string.plan_agent_model), models, agent.modelId) { draft = agent.copy(modelId = it) }
                Gap(4)
                ModelPicker(stringResource(R.string.plan_agent_fallback), models, agent.fallbackModelId) { draft = agent.copy(fallbackModelId = it) }
                if (models.isEmpty()) {
                    Gap(4)
                    Text(stringResource(R.string.plan_agent_no_models), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                }
                Gap()
                Field(
                    stringResource(R.string.plan_agent_temperature),
                    agent.temperature?.toString().orEmpty(),
                    { draft = agent.copy(temperature = it.toDoubleOrNull()) },
                    supporting = stringResource(if (anthropicOnly) R.string.plan_agent_temperature_unused else R.string.plan_agent_temperature_optional),
                )
                Field(stringResource(R.string.plan_agent_max_tokens), agent.maxTokens?.toString().orEmpty(), { draft = agent.copy(maxTokens = it.toIntOrNull()) }, supporting = stringResource(R.string.plan_optional))
                Field(stringResource(R.string.plan_agent_standing), agent.extraInstructions, { draft = agent.copy(extraInstructions = it) }, singleLine = false, supporting = stringResource(R.string.plan_optional))
                Gap()
                BusyButton(stringResource(R.string.plan_save), false) { vm.save(agent) }
            }
        }
        item { SectionHeader(stringResource(R.string.plan_agent_history)) }
        if (history.isEmpty()) item { Text(stringResource(R.string.plan_agent_nothing_yet), color = Nexio.colors.textMuted, modifier = Modifier.padding(4.dp)) }
        items(history.size, key = { history[it].id }) { i -> TaskRow(history[i]) { open(Routes.task(history[i].id)) } }
    }
}
