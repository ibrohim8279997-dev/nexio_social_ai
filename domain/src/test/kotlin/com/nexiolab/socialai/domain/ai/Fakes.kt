package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.model.*
import com.nexiolab.socialai.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

class FakeProviders : ProviderRepository {
    val providers = LinkedHashMap<Long, AIProvider>()
    val models = LinkedHashMap<Long, AIModel>()
    override fun observeProviders() = flowOf(providers.values.toList())
    override fun observeModels() = flowOf(models.values.toList())
    override suspend fun provider(id: Long) = providers[id]
    override suspend fun model(id: Long) = models[id]
    override suspend fun providers() = providers.values.toList()
    override suspend fun modelsOf(providerId: Long) = models.values.filter { it.providerId == providerId }
    override suspend fun saveProvider(provider: AIProvider): Long { providers[provider.id] = provider; return provider.id }
    override suspend fun removeProvider(provider: AIProvider) { providers.remove(provider.id) }
    override suspend fun setDefault(providerId: Long) {}
    override suspend fun saveModel(model: AIModel): Long { models[model.id] = model; return model.id }
    override suspend fun deleteModel(model: AIModel) { models.remove(model.id) }
    override suspend fun addDiscoveredModels(providerId: Long, modelIds: List<String>) {}
}

class FakeCredentials(private val keys: MutableMap<String, String> = HashMap()) : CredentialStore {
    override suspend fun put(alias: String, secret: String) { keys[alias] = secret }
    override suspend fun get(alias: String) = keys[alias]
    override suspend fun has(alias: String) = alias in keys
    override suspend fun delete(alias: String) { keys.remove(alias) }
    override suspend fun masked(alias: String) = keys[alias]?.let { "****" }
}

class FakeAgents : AgentRepository {
    val agents = HashMap<AgentRole, Agent>()
    val tasks = LinkedHashMap<Long, AgentTask>()
    private var next = 1L
    override fun observeAgents() = flowOf(agents.values.toList())
    override fun observeTasks(limit: Int) = flowOf(tasks.values.toList())
    override fun observeTasks(role: AgentRole, limit: Int) = flowOf(tasks.values.filter { it.role == role })
    override fun observeActive(): Flow<List<AgentTask>> = emptyFlow()
    override suspend fun agent(role: AgentRole) = agents[role] ?: Agent(role)
    override suspend fun saveAgent(agent: Agent) { agents[agent.role] = agent }
    override suspend fun createTask(task: AgentTask): Long { val id = next++; tasks[id] = task.copy(id = id); return id }
    override suspend fun updateTask(task: AgentTask) { tasks[task.id] = task }
    override suspend fun task(id: Long) = tasks[id]
    override suspend fun setStatus(id: Long, status: TaskStatus, output: String?, notes: String?) {
        val t = tasks.getValue(id)
        tasks[id] = t.copy(status = status, output = output ?: t.output, reviewNotes = notes ?: t.reviewNotes)
    }
    override suspend fun recent(role: AgentRole, limit: Int) = tasks.values.filter { it.role == role && it.status == TaskStatus.COMPLETED }.takeLast(limit)
}

class FakeExpenses : ExpenseRepository {
    val rows = ArrayList<ExpenseRecord>()
    var budget: MonthlyBudget? = null
    var providerLimit: ProviderBudget? = null
    override fun observeSince(from: Long) = flowOf(rows.toList())
    override fun observeBudget(yearMonth: String) = MutableStateFlow(budget)
    override fun observeProviderBudgets() = flowOf(listOfNotNull(providerLimit))
    private var nextId = 1L
    override suspend fun record(expense: ExpenseRecord): Long {
        val id = if (expense.id != 0L) expense.id else nextId++
        rows += expense.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
    override suspend fun spentSince(from: Long) = rows.filter { it.createdAt >= from }.sumOf { it.amount }
    override suspend fun spentSince(from: Long, providerId: Long) = rows.filter { it.createdAt >= from && it.providerId == providerId }.sumOf { it.amount }
    override suspend fun budgetFor(yearMonth: String) = budget
    override suspend fun saveBudget(budget: MonthlyBudget) { this.budget = budget }
    override suspend fun providerBudget(providerId: Long) = providerLimit?.takeIf { it.providerId == providerId }
    override suspend fun saveProviderBudget(budget: ProviderBudget) { providerLimit = budget }
    override suspend fun deleteProviderBudget(providerId: Long) { providerLimit = null }
}

class FakeActivity : ActivityRepository {
    val notices = LinkedHashMap<String, String>()
    val log = ArrayList<String>()
    override fun observeActivity(limit: Int): Flow<List<ActivityEntry>> = emptyFlow()
    override fun observeNotices(): Flow<List<AppNotice>> = emptyFlow()
    override suspend fun log(title: String, detail: String) { log += title }
    override suspend fun notice(dedupeKey: String, severity: NoticeSeverity, title: String, body: String): Boolean {
        if (dedupeKey in notices) return false
        notices[dedupeKey] = title
        return true
    }
    override suspend fun dismiss(id: Long) {}
}
