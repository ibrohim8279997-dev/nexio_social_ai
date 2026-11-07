package com.nexiolab.socialai.data.repository

import androidx.room.withTransaction
import com.nexiolab.socialai.data.db.NexioDatabase
import com.nexiolab.socialai.domain.ai.ModelHeuristics
import com.nexiolab.socialai.domain.model.*
import com.nexiolab.socialai.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomBrandRepository(private val db: NexioDatabase) : BrandRepository {
    override fun observe() = db.brand().observe()
    override suspend fun get() = db.brand().get()
    override suspend fun save(profile: BrandProfile) = db.brand().save(profile.copy(updatedAt = System.currentTimeMillis()))
}

class RoomSettingsRepository(private val db: NexioDatabase) : SettingsRepository {
    override fun observe(key: String) = db.settings().observe(key)
    override suspend fun get(key: String) = db.settings().get(key)
    override suspend fun set(key: String, value: String) = db.settings().set(AppSetting(key, value))
}

class RoomProviderRepository(
    private val db: NexioDatabase,
    private val credentials: CredentialStore,
) : ProviderRepository {
    private val dao = db.providers()
    override fun observeProviders() = dao.observeProviders()
    override fun observeModels() = dao.observeModels()
    override suspend fun provider(id: Long) = dao.provider(id)
    override suspend fun model(id: Long) = dao.model(id)
    override suspend fun providers() = dao.providers()
    override suspend fun modelsOf(providerId: Long) = dao.modelsOf(providerId)

    override suspend fun saveProvider(provider: AIProvider): Long {
        val returned = dao.upsertProvider(provider)
        return if (provider.id != 0L) provider.id else returned
    }

    override suspend fun removeProvider(provider: AIProvider) {
        dao.deleteProvider(provider)
        credentials.delete(CredentialStore.provider(provider.id))
    }

    override suspend fun setDefault(providerId: Long) = dao.setDefault(providerId)

    override suspend fun saveModel(model: AIModel): Long {
        val returned = dao.upsertModel(model)
        return if (model.id != 0L) model.id else returned
    }

    override suspend fun deleteModel(model: AIModel) = dao.deleteModel(model)

    override suspend fun addDiscoveredModels(providerId: Long, modelIds: List<String>) =
        dao.insertModels(
            modelIds.map { id ->
                val can = ModelHeuristics.capabilitiesOf(id)
                AIModel(
                    providerId = providerId, modelId = id, selected = false,
                    supportsText = can.text, supportsImage = can.image, supportsVideo = can.video, supportsSpeech = can.speech,
                )
            },
        )
}

class RoomAgentRepository(private val db: NexioDatabase) : AgentRepository {
    private val dao = db.agents()
    override fun observeAgents() = dao.observeAgents()
    override fun observeTasks(limit: Int) = dao.observeTasks(limit)
    override fun observeTasks(role: AgentRole, limit: Int) = dao.observeTasks(role, limit)
    override fun observeActive() = dao.observeActive()
    override suspend fun agent(role: AgentRole) = dao.agent(role) ?: Agent(role)
    override suspend fun saveAgent(agent: Agent) = dao.saveAgent(agent)
    override suspend fun createTask(task: AgentTask) = dao.insertTask(task)
    override suspend fun updateTask(task: AgentTask) = dao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
    override suspend fun task(id: Long) = dao.task(id)

    override suspend fun setStatus(id: Long, status: TaskStatus, output: String?, notes: String?) {
        db.withTransaction {
            val task = dao.task(id) ?: return@withTransaction
            dao.updateTask(
                task.copy(
                    status = status,
                    output = output ?: task.output,
                    reviewNotes = notes ?: task.reviewNotes,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun recent(role: AgentRole, limit: Int) = dao.recent(role, limit)
}

class RoomContentRepository(private val db: NexioDatabase) : ContentRepository {
    private val dao = db.content()
    override fun observePlans() = dao.observePlans()
    override fun observeBetween(fromMillis: Long, toMillis: Long) = dao.observeBetween(fromMillis, toMillis)
    override fun observeAll() = dao.observeAll()
    override fun observeStatusCounts(): Flow<Map<ContentStatus, Int>> =
        dao.observeStatusCounts().map { rows -> rows.associate { it.status to it.total } }
    override fun observeUpcoming(now: Long, limit: Int) = dao.observeUpcoming(now, limit)
    override suspend fun item(id: Long) = dao.item(id)
    override suspend fun planFor(yearMonth: String) = dao.planFor(yearMonth)
    override suspend fun savePlan(plan: ContentPlan) = dao.upsertPlan(plan)
    override suspend fun savePlanWithItems(plan: ContentPlan, items: List<ContentItem>) =
        db.withTransaction { dao.replacePlan(plan, items) }

    override suspend fun save(item: ContentItem): Long {
        val stamped = item.copy(updatedAt = System.currentTimeMillis())
        val returned = dao.upsert(stamped)
        return if (item.id != 0L) item.id else returned
    }

    override suspend fun delete(item: ContentItem) = dao.delete(item)
    override suspend fun carouselMedia(itemId: Long) = dao.carouselMedia(itemId)
    override suspend fun setCarouselMedia(itemId: Long, mediaIds: List<Long>) = dao.setCarousel(itemId, mediaIds)
    override suspend fun dueForPublishing(now: Long) = dao.dueForPublishing(now)
    override suspend fun recentCaptions(limit: Int) = dao.recentCaptions(limit)
}

class RoomExpenseRepository(private val db: NexioDatabase) : ExpenseRepository {
    private val dao = db.expenses()
    override fun observeSince(from: Long) = dao.observeSince(from)
    override fun observeBudget(yearMonth: String) = dao.observeBudget(yearMonth)
    override fun observeProviderBudgets() = dao.observeProviderBudgets()
    override suspend fun record(expense: ExpenseRecord): Long = dao.insert(expense)
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun spentSince(from: Long) = dao.spentSince(from)
    override suspend fun spentSince(from: Long, providerId: Long) = dao.spentSince(from, providerId)
    override suspend fun budgetFor(yearMonth: String) = dao.budgetFor(yearMonth)
    override suspend fun saveBudget(budget: MonthlyBudget) = dao.saveBudget(budget)
    override suspend fun providerBudget(providerId: Long) = dao.providerBudget(providerId)
    override suspend fun saveProviderBudget(budget: ProviderBudget) = dao.saveProviderBudget(budget)
    override suspend fun deleteProviderBudget(providerId: Long) = dao.deleteProviderBudget(providerId)
}

class RoomActivityRepository(private val db: NexioDatabase) : ActivityRepository {
    private val dao = db.activity()
    override fun observeActivity(limit: Int) = dao.observeActivity(limit)
    override fun observeNotices() = dao.observeNotices()
    override suspend fun log(title: String, detail: String) =
        dao.log(ActivityEntry(title = title, detail = detail, createdAt = System.currentTimeMillis()))

    override suspend fun notice(dedupeKey: String, severity: NoticeSeverity, title: String, body: String): Boolean =
        dao.notice(AppNotice(dedupeKey = dedupeKey, severity = severity, title = title, body = body, createdAt = System.currentTimeMillis())) != -1L

    override suspend fun dismiss(id: Long) = dao.dismiss(id, System.currentTimeMillis())
}
