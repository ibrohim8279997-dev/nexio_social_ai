package com.nexiolab.socialai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nexiolab.socialai.domain.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BrandDao {
    @Query("SELECT * FROM brand_profile WHERE id = 1") fun observe(): Flow<BrandProfile?>
    @Query("SELECT * FROM brand_profile WHERE id = 1") suspend fun get(): BrandProfile?
    @Upsert suspend fun save(profile: BrandProfile)
    @Upsert suspend fun saveUser(profile: UserProfile)
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM app_setting WHERE `key` = :key") fun observe(key: String): Flow<String?>
    @Query("SELECT value FROM app_setting WHERE `key` = :key") suspend fun get(key: String): String?
    @Upsert suspend fun set(setting: AppSetting)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_file ORDER BY createdAt DESC") fun observeAll(): Flow<List<MediaFile>>
    @Query("SELECT * FROM media_file") suspend fun all(): List<MediaFile>
    @Query("SELECT * FROM media_file WHERE id = :id") suspend fun byId(id: Long): MediaFile?
    @Query("SELECT * FROM media_file WHERE sha256 = :sha256 LIMIT 1") suspend fun bySha(sha256: String): MediaFile?
    @Insert suspend fun insert(media: MediaFile): Long
    @Delete suspend fun delete(media: MediaFile)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM ai_provider ORDER BY isDefault DESC, fallbackOrder, id") fun observeProviders(): Flow<List<AIProvider>>
    @Query("SELECT * FROM ai_provider ORDER BY isDefault DESC, fallbackOrder, id") suspend fun providers(): List<AIProvider>
    @Query("SELECT * FROM ai_model ORDER BY providerId, displayName") fun observeModels(): Flow<List<AIModel>>
    @Query("SELECT * FROM ai_provider WHERE id = :id") suspend fun provider(id: Long): AIProvider?
    @Query("SELECT * FROM ai_model WHERE id = :id") suspend fun model(id: Long): AIModel?
    @Query("SELECT * FROM ai_model WHERE providerId = :providerId ORDER BY displayName") suspend fun modelsOf(providerId: Long): List<AIModel>
    @Upsert suspend fun upsertProvider(provider: AIProvider): Long
    @Delete suspend fun deleteProvider(provider: AIProvider)
    @Upsert suspend fun upsertModel(model: AIModel): Long
    @Delete suspend fun deleteModel(model: AIModel)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertModels(models: List<AIModel>)
    @Query("UPDATE ai_provider SET isDefault = (id = :id)") suspend fun setDefault(id: Long)
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent") fun observeAgents(): Flow<List<Agent>>
    @Query("SELECT * FROM agent WHERE role = :role") suspend fun agent(role: AgentRole): Agent?
    @Upsert suspend fun saveAgent(agent: Agent)
    @Query("SELECT * FROM agent_task ORDER BY id DESC LIMIT :limit") fun observeTasks(limit: Int): Flow<List<AgentTask>>
    @Query("SELECT * FROM agent_task WHERE role = :role ORDER BY id DESC LIMIT :limit") fun observeTasks(role: AgentRole, limit: Int): Flow<List<AgentTask>>
    @Query("SELECT * FROM agent_task WHERE status IN ('QUEUED','RUNNING','NEEDS_CORRECTION','WAITING_APPROVAL') ORDER BY id DESC") fun observeActive(): Flow<List<AgentTask>>
    @Query("SELECT * FROM agent_task WHERE id = :id") suspend fun task(id: Long): AgentTask?
    @Insert suspend fun insertTask(task: AgentTask): Long
    @Upsert suspend fun updateTask(task: AgentTask)
    @Query("SELECT * FROM agent_task WHERE role = :role AND status = 'COMPLETED' ORDER BY id DESC LIMIT :limit") suspend fun recent(role: AgentRole, limit: Int): List<AgentTask>
}

data class StatusCount(val status: ContentStatus, val total: Int)

@Dao
interface ContentDao {
    @Query("SELECT * FROM content_plan ORDER BY yearMonth DESC") fun observePlans(): Flow<List<ContentPlan>>
    @Query("SELECT * FROM content_plan WHERE yearMonth = :yearMonth") suspend fun planFor(yearMonth: String): ContentPlan?
    @Upsert suspend fun upsertPlan(plan: ContentPlan): Long
    @Query("SELECT * FROM content_item WHERE scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt") fun observeBetween(from: Long, to: Long): Flow<List<ContentItem>>
    @Query("SELECT * FROM content_item ORDER BY COALESCE(scheduledAt, createdAt) DESC") fun observeAll(): Flow<List<ContentItem>>
    @Query("SELECT status, COUNT(*) AS total FROM content_item GROUP BY status") fun observeStatusCounts(): Flow<List<StatusCount>>
    @Query("SELECT * FROM content_item WHERE scheduledAt >= :now AND status IN ('APPROVED','SCHEDULED') ORDER BY scheduledAt LIMIT :limit") fun observeUpcoming(now: Long, limit: Int): Flow<List<ContentItem>>
    @Query("SELECT * FROM content_item WHERE id = :id") suspend fun item(id: Long): ContentItem?
    @Upsert suspend fun upsert(item: ContentItem): Long
    @Insert suspend fun insertAll(items: List<ContentItem>)
    @Delete suspend fun delete(item: ContentItem)
    @Query("SELECT * FROM content_item WHERE status = 'SCHEDULED' AND scheduledAt <= :now ORDER BY scheduledAt") suspend fun dueForPublishing(now: Long): List<ContentItem>
    @Query("SELECT caption FROM content_item WHERE caption != '' ORDER BY id DESC LIMIT :limit") suspend fun recentCaptions(limit: Int): List<String>
    @Query("SELECT m.* FROM media_file m JOIN content_item_media c ON c.mediaId = m.id WHERE c.contentItemId = :itemId ORDER BY c.position") suspend fun carouselMedia(itemId: Long): List<MediaFile>
    @Query("DELETE FROM content_item_media WHERE contentItemId = :itemId") suspend fun clearCarousel(itemId: Long)
    @Insert suspend fun insertCarousel(rows: List<ContentItemMedia>)

    @Transaction
    suspend fun setCarousel(itemId: Long, mediaIds: List<Long>) {
        clearCarousel(itemId)
        insertCarousel(mediaIds.mapIndexed { i, id -> ContentItemMedia(itemId, id, i) })
    }

    /** Posts the owner approved or that went out are never touched by a new plan. */
    @Query("DELETE FROM content_item WHERE planId = :planId AND status IN ('DRAFT','REVIEW')") suspend fun deleteUnapproved(planId: Long)

    @Transaction
    suspend fun replacePlan(plan: ContentPlan, items: List<ContentItem>): Long {
        val existing = planFor(plan.yearMonth)
        val id = if (existing != null) {
            upsertPlan(plan.copy(id = existing.id))
            deleteUnapproved(existing.id)
            existing.id
        } else {
            upsertPlan(plan)
        }
        insertAll(items.map { it.copy(planId = id) })
        return id
    }
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM video_project ORDER BY updatedAt DESC") fun observeAll(): Flow<List<VideoProject>>
    @Query("SELECT * FROM video_project WHERE id = :id") fun observe(id: Long): Flow<VideoProject?>
    @Query("SELECT * FROM video_project WHERE id = :id") suspend fun byId(id: Long): VideoProject?
    @Upsert suspend fun upsert(project: VideoProject): Long
    @Delete suspend fun delete(project: VideoProject)
    @Query("SELECT m.* FROM media_file m JOIN video_project_photo p ON p.mediaId = m.id WHERE p.projectId = :projectId ORDER BY p.position") suspend fun photos(projectId: Long): List<MediaFile>
    @Query("SELECT m.* FROM media_file m JOIN video_project_photo p ON p.mediaId = m.id WHERE p.projectId = :projectId ORDER BY p.position") fun observePhotos(projectId: Long): Flow<List<MediaFile>>
    @Query("DELETE FROM video_project_photo WHERE projectId = :projectId") suspend fun clearPhotos(projectId: Long)
    @Insert suspend fun insertPhotos(photos: List<VideoProjectPhoto>)

    @Transaction
    suspend fun setPhotos(projectId: Long, mediaIds: List<Long>) {
        clearPhotos(projectId)
        insertPhotos(mediaIds.mapIndexed { i, id -> VideoProjectPhoto(projectId, id, i) })
    }
}

@Dao
interface InstagramDao {
    @Query("SELECT * FROM instagram_account LIMIT 1") fun observeAccount(): Flow<InstagramAccount?>
    @Query("SELECT * FROM instagram_account LIMIT 1") suspend fun account(): InstagramAccount?
    @Upsert suspend fun saveAccount(account: InstagramAccount)
    @Query("DELETE FROM instagram_account") suspend fun clearAccount()
    @Query("SELECT * FROM instagram_post ORDER BY id DESC") fun observePosts(): Flow<List<InstagramPost>>
    @Query("SELECT * FROM instagram_post WHERE id = :id") suspend fun post(id: Long): InstagramPost?
    @Query("SELECT * FROM instagram_post WHERE contentItemId = :contentItemId ORDER BY id DESC LIMIT 1") suspend fun latestFor(contentItemId: Long): InstagramPost?
    @Query("SELECT * FROM instagram_post WHERE idempotencyKey = :key") suspend fun byKey(key: String): InstagramPost?
    @Insert suspend fun insert(post: InstagramPost): Long
    @Upsert suspend fun save(post: InstagramPost)
    @Query("SELECT * FROM instagram_post WHERE state = 'PUBLISHED' AND publishedMediaId IS NOT NULL ORDER BY publishedAt DESC LIMIT :limit") suspend fun published(limit: Int): List<InstagramPost>
}

@Dao
interface AnalyticsDao {
    @Query("SELECT * FROM analytics_record WHERE capturedAt >= :from ORDER BY capturedAt") fun observeSince(from: Long): Flow<List<AnalyticsRecord>>
    @Insert suspend fun insertAll(records: List<AnalyticsRecord>)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expense_record WHERE createdAt >= :from ORDER BY createdAt DESC") fun observeSince(from: Long): Flow<List<ExpenseRecord>>
    @Insert suspend fun insert(expense: ExpenseRecord): Long
    @Query("DELETE FROM expense_record WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COALESCE(SUM(COALESCE(actualCost, estimatedCost, 0)), 0) FROM expense_record WHERE createdAt >= :from") suspend fun spentSince(from: Long): Double
    @Query("SELECT COALESCE(SUM(COALESCE(actualCost, estimatedCost, 0)), 0) FROM expense_record WHERE createdAt >= :from AND providerId = :providerId") suspend fun spentSince(from: Long, providerId: Long): Double
    @Query("SELECT * FROM monthly_budget WHERE yearMonth IN (:yearMonth, 'default') ORDER BY yearMonth = 'default' LIMIT 1") fun observeBudget(yearMonth: String): Flow<MonthlyBudget?>
    @Query("SELECT * FROM monthly_budget WHERE yearMonth IN (:yearMonth, 'default') ORDER BY yearMonth = 'default' LIMIT 1") suspend fun budgetFor(yearMonth: String): MonthlyBudget?
    @Upsert suspend fun saveBudget(budget: MonthlyBudget)
    @Query("SELECT * FROM provider_budget") fun observeProviderBudgets(): Flow<List<ProviderBudget>>
    @Query("SELECT * FROM provider_budget WHERE providerId = :providerId") suspend fun providerBudget(providerId: Long): ProviderBudget?
    @Upsert suspend fun saveProviderBudget(budget: ProviderBudget)
    @Query("DELETE FROM provider_budget WHERE providerId = :providerId") suspend fun deleteProviderBudget(providerId: Long)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_entry ORDER BY id DESC LIMIT :limit") fun observeActivity(limit: Int): Flow<List<ActivityEntry>>
    @Insert suspend fun log(entry: ActivityEntry)
    @Query("SELECT * FROM app_notice WHERE readAt IS NULL ORDER BY id DESC") fun observeNotices(): Flow<List<AppNotice>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun notice(notice: AppNotice): Long
    @Query("UPDATE app_notice SET readAt = :at WHERE id = :id") suspend fun dismiss(id: Long, at: Long)
}

@Dao
interface MigrationDao {
    @Upsert suspend fun saveSession(session: MigrationSession)
    @Query("SELECT * FROM migration_session ORDER BY startedAt DESC LIMIT 20") fun observeSessions(): Flow<List<MigrationSession>>
    @Insert suspend fun saveManifest(manifest: MigrationManifestRecord)
    @Insert suspend fun saveBackup(record: BackupRecord)
    @Query("SELECT * FROM backup_record ORDER BY createdAt DESC") fun observeBackups(): Flow<List<BackupRecord>>
}
