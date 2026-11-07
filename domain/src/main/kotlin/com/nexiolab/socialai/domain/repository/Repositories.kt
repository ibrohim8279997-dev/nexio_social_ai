package com.nexiolab.socialai.domain.repository

import com.nexiolab.socialai.domain.model.ActivityEntry
import com.nexiolab.socialai.domain.model.Agent
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.AgentTask
import com.nexiolab.socialai.domain.model.AIModel
import com.nexiolab.socialai.domain.model.AIProvider
import com.nexiolab.socialai.domain.model.AppNotice
import com.nexiolab.socialai.domain.model.BrandProfile
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentPlan
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.ExpenseRecord
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.model.MediaSource
import com.nexiolab.socialai.domain.model.MonthlyBudget
import com.nexiolab.socialai.domain.model.NoticeSeverity
import com.nexiolab.socialai.domain.model.ProviderBudget
import com.nexiolab.socialai.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

interface BrandRepository {
    fun observe(): Flow<BrandProfile?>
    suspend fun get(): BrandProfile?
    suspend fun save(profile: BrandProfile)
}

interface SettingsRepository {
    fun observe(key: String): Flow<String?>
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
}

interface MediaRepository {
    fun observeAll(): Flow<List<MediaFile>>
    suspend fun byId(id: Long): MediaFile?
    /** Copies the content at [uri] into private storage; returns the existing row for identical bytes. */
    suspend fun import(uri: String, source: MediaSource = MediaSource.UPLOADED): MediaFile
    suspend fun store(bytes: ByteArray, kind: MediaKind, mimeType: String, name: String, source: MediaSource): MediaFile
    suspend fun registerFile(path: String, kind: MediaKind, mimeType: String, name: String, source: MediaSource): MediaFile
    fun absolutePath(media: MediaFile): String
    suspend fun delete(media: MediaFile)
}

interface ProviderRepository {
    fun observeProviders(): Flow<List<AIProvider>>
    fun observeModels(): Flow<List<AIModel>>
    suspend fun provider(id: Long): AIProvider?
    suspend fun model(id: Long): AIModel?
    suspend fun providers(): List<AIProvider>
    suspend fun modelsOf(providerId: Long): List<AIModel>
    suspend fun saveProvider(provider: AIProvider): Long
    suspend fun removeProvider(provider: AIProvider)
    suspend fun setDefault(providerId: Long)
    suspend fun saveModel(model: AIModel): Long
    suspend fun deleteModel(model: AIModel)
    /** Adds identifiers the provider reported; rows the user already configured stay untouched. */
    suspend fun addDiscoveredModels(providerId: Long, modelIds: List<String>)
}

/** Secrets never enter Room; this is the only path to them. */
interface CredentialStore {
    suspend fun put(alias: String, secret: String)
    suspend fun get(alias: String): String?
    suspend fun has(alias: String): Boolean
    suspend fun delete(alias: String)
    /** Replaces the secret with a form safe to display. */
    suspend fun masked(alias: String): String?

    companion object {
        fun provider(id: Long) = "provider.$id"
        const val INSTAGRAM_TOKEN = "instagram.token"
        const val META_APP_SECRET = "meta.app.secret"
        const val HOSTING_SECRET = "hosting.secret"
    }
}

interface AgentRepository {
    fun observeAgents(): Flow<List<Agent>>
    fun observeTasks(limit: Int = 200): Flow<List<AgentTask>>
    fun observeTasks(role: AgentRole, limit: Int = 100): Flow<List<AgentTask>>
    fun observeActive(): Flow<List<AgentTask>>
    suspend fun agent(role: AgentRole): Agent
    suspend fun saveAgent(agent: Agent)
    suspend fun createTask(task: AgentTask): Long
    suspend fun updateTask(task: AgentTask)
    suspend fun task(id: Long): AgentTask?
    suspend fun setStatus(id: Long, status: TaskStatus, output: String? = null, notes: String? = null)
    /** Recent finished work for one role, used as that agent's memory. */
    suspend fun recent(role: AgentRole, limit: Int): List<AgentTask>
}

interface ContentRepository {
    fun observePlans(): Flow<List<ContentPlan>>
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<ContentItem>>
    fun observeAll(): Flow<List<ContentItem>>
    fun observeStatusCounts(): Flow<Map<ContentStatus, Int>>
    fun observeUpcoming(now: Long, limit: Int): Flow<List<ContentItem>>
    suspend fun item(id: Long): ContentItem?
    suspend fun planFor(yearMonth: String): ContentPlan?
    suspend fun savePlan(plan: ContentPlan): Long
    /** Plan and items are stored together or not at all. */
    suspend fun savePlanWithItems(plan: ContentPlan, items: List<ContentItem>): Long
    suspend fun save(item: ContentItem): Long
    suspend fun delete(item: ContentItem)
    /** The images of a carousel post in the owner's order; empty for every other type. */
    suspend fun carouselMedia(itemId: Long): List<MediaFile>
    /** Replaces the carousel's images with [mediaIds], in that order. */
    suspend fun setCarouselMedia(itemId: Long, mediaIds: List<Long>)
    suspend fun dueForPublishing(now: Long): List<ContentItem>
    suspend fun recentCaptions(limit: Int): List<String>
}

interface ExpenseRepository {
    fun observeSince(from: Long): Flow<List<ExpenseRecord>>
    fun observeBudget(yearMonth: String): Flow<MonthlyBudget?>
    fun observeProviderBudgets(): Flow<List<ProviderBudget>>
    /** Returns the id of the new row. */
    suspend fun record(expense: ExpenseRecord): Long
    suspend fun delete(id: Long)
    suspend fun spentSince(from: Long): Double
    suspend fun spentSince(from: Long, providerId: Long): Double
    suspend fun budgetFor(yearMonth: String): MonthlyBudget?
    suspend fun saveBudget(budget: MonthlyBudget)
    suspend fun providerBudget(providerId: Long): ProviderBudget?
    suspend fun saveProviderBudget(budget: ProviderBudget)
    suspend fun deleteProviderBudget(providerId: Long)
}

interface ActivityRepository {
    fun observeActivity(limit: Int = 20): Flow<List<ActivityEntry>>
    fun observeNotices(): Flow<List<AppNotice>>
    suspend fun log(title: String, detail: String = "")
    /** The key keeps a recurring condition from raising a notice more than once. */
    suspend fun notice(dedupeKey: String, severity: NoticeSeverity, title: String, body: String): Boolean
    suspend fun dismiss(id: Long)
}
