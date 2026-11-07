package com.nexiolab.socialai.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * The app's records. They double as Room entities: the annotations are compile-only metadata and
 * keep a second, hand-mapped copy of nineteen types out of a single-user MVP. See docs/ARCHITECTURE.md.
 */

@Serializable
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Long = 1,
    val displayName: String = "",
    val createdAt: Long = 0,
)

@Serializable
@Entity(tableName = "brand_profile")
data class BrandProfile(
    @PrimaryKey val id: Long = 1,
    val businessName: String = "",
    val category: String = "",
    val description: String = "",
    val products: String = "",
    val services: String = "",
    val targetAudience: String = "",
    val demographics: String = "",
    val brandIdentity: String = "",
    val brandColors: String = "",
    val fonts: String = "",
    val logoMediaId: Long? = null,
    val brandVoice: String = "",
    val languages: String = "",
    val marketingGoals: String = "",
    val competitors: String = "",
    val platforms: String = "",
    val contentFrequency: String = "",
    val contentFormats: String = "",
    val monthlyMarketingBudget: String = "",
    val voiceoverPreferences: String = "",
    val visualPreferences: String = "",
    val interviewCompletedAt: Long? = null,
    /** Last month (yyyy-MM) the "what changed?" check-in was answered. */
    val lastMonthlyReview: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
@Entity(tableName = "ai_provider")
data class AIProvider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    /** Order tried when another provider fails; lower runs first. */
    val fallbackOrder: Int = 0,
    val lastTestAt: Long? = null,
    val lastTestOk: Boolean? = null,
    val lastError: String? = null,
)

/** Identifiers, capabilities and prices all come from configuration; none are assumed. */
@Serializable
@Entity(
    tableName = "ai_model",
    foreignKeys = [ForeignKey(AIProvider::class, ["id"], ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["providerId", "modelId"], unique = true)],
)
data class AIModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val displayName: String = modelId,
    /** Discovered models stay out of routing until the user selects them. */
    val selected: Boolean = true,
    val supportsText: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsImage: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsSpeech: Boolean = false,
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null,
    val pricePerImage: Double? = null,
    val pricePerVideoSecond: Double? = null,
    val pricePerMChar: Double? = null,
    val currency: String = "USD",
    val priceUpdatedAt: Long? = null,
)

@Serializable
@Entity(
    tableName = "agent",
    foreignKeys = [
        ForeignKey(AIModel::class, ["id"], ["modelId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(AIModel::class, ["id"], ["fallbackModelId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("modelId"), Index("fallbackModelId")],
)
data class Agent(
    @PrimaryKey val role: AgentRole,
    val enabled: Boolean = true,
    val modelId: Long? = null,
    val fallbackModelId: Long? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val extraInstructions: String = "",
)

@Serializable
@Entity(
    tableName = "agent_task",
    foreignKeys = [ForeignKey(AgentTask::class, ["id"], ["parentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("parentId"), Index("role"), Index("status")],
)
data class AgentTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val role: AgentRole,
    val title: String,
    val input: String,
    val output: String = "",
    val status: TaskStatus = TaskStatus.QUEUED,
    val attempts: Int = 0,
    val reviewNotes: String = "",
    val modelUsed: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "content_plan", indices = [Index(value = ["yearMonth"], unique = true)])
data class ContentPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyy-MM */
    val yearMonth: String,
    val title: String,
    val strategy: String = "",
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "content_item",
    foreignKeys = [
        ForeignKey(ContentPlan::class, ["id"], ["planId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(MediaFile::class, ["id"], ["mediaId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("planId"), Index("mediaId"), Index("scheduledAt"), Index("status")],
)
data class ContentItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long? = null,
    val title: String,
    val description: String = "",
    val caption: String = "",
    val hashtags: String = "",
    val type: ContentType = ContentType.IMAGE_POST,
    /** Publication date and time, epoch millis. */
    val scheduledAt: Long? = null,
    val status: ContentStatus = ContentStatus.DRAFT,
    val mediaId: Long? = null,
    val agentRole: AgentRole = AgentRole.CONTENT,
    val qualityNotes: String = "",
    val approvedAt: Long? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One image of a carousel post, in the owner's order. A single-image, Reel or Story post keeps using
 * [ContentItem.mediaId] and has no rows here. Deleting a media file removes its rows, so the carousel
 * then falls under the 2..10 rule again and cannot be approved or published until it is fixed.
 */
@Serializable
@Entity(
    tableName = "content_item_media",
    primaryKeys = ["contentItemId", "position"],
    foreignKeys = [
        ForeignKey(ContentItem::class, ["id"], ["contentItemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaFile::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("mediaId")],
)
data class ContentItemMedia(val contentItemId: Long, val mediaId: Long, val position: Int)

@Serializable
@Entity(tableName = "media_file", indices = [Index(value = ["relativePath"], unique = true), Index("sha256")])
data class MediaFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: MediaKind,
    /** Path under the app's media root. Files stay on disk; only this reference lives in Room. */
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val source: MediaSource = MediaSource.UPLOADED,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "video_project",
    foreignKeys = [
        ForeignKey(MediaFile::class, ["id"], ["outputMediaId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(MediaFile::class, ["id"], ["musicMediaId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(MediaFile::class, ["id"], ["voiceoverMediaId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("outputMediaId"), Index("musicMediaId"), Index("voiceoverMediaId")],
)
data class VideoProject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mode: VideoMode,
    val objective: String = "",
    val script: String = "",
    val visualDirection: String = "",
    val storyboard: String = "",
    val aspectWidth: Int = 9,
    val aspectHeight: Int = 16,
    val durationSeconds: Int = 15,
    val shortSidePx: Int = 720,
    val frameRate: Int = 30,
    val transition: VideoTransition = VideoTransition.FADE,
    val captionText: String = "",
    val voiceoverText: String = "",
    val musicMediaId: Long? = null,
    val voiceoverMediaId: Long? = null,
    val outputMediaId: Long? = null,
    val providerJobId: String? = null,
    val status: VideoStatus = VideoStatus.DRAFT,
    val qualityNotes: String = "",
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "video_project_photo",
    primaryKeys = ["projectId", "mediaId"],
    foreignKeys = [
        ForeignKey(VideoProject::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaFile::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("mediaId")],
)
data class VideoProjectPhoto(val projectId: Long, val mediaId: Long, val position: Int)

@Serializable
@Entity(tableName = "instagram_account")
data class InstagramAccount(
    @PrimaryKey val igUserId: String,
    val username: String,
    val accountType: String = "",
    val tokenExpiresAt: Long? = null,
    val connectedAt: Long,
    val needsReconnect: Boolean = false,
)

@Serializable
@Entity(
    tableName = "instagram_post",
    foreignKeys = [ForeignKey(ContentItem::class, ["id"], ["contentItemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["idempotencyKey"], unique = true), Index("contentItemId")],
)
data class InstagramPost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentItemId: Long,
    val igUserId: String,
    /** One key per publish intent; the unique index makes a second attempt collide, not repost. */
    val idempotencyKey: String,
    val state: PublishState = PublishState.PENDING,
    val hostedMediaUrl: String? = null,
    val containerId: String? = null,
    /** Carousel posts only: per-image progress as JSON (see CarouselProgress), so a restart never redoes a finished image. */
    val carouselProgress: String? = null,
    val publishedMediaId: String? = null,
    val permalink: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val publishedAt: Long? = null,
)

@Serializable
@Entity(tableName = "analytics_record", indices = [Index("mediaId"), Index("capturedAt")])
data class AnalyticsRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Instagram media id, or null for account-level metrics. */
    val mediaId: String? = null,
    val metric: String,
    val value: Double,
    val capturedAt: Long,
)

/** Prices are snapshotted on the row so later price edits never rewrite history. */
@Serializable
@Entity(tableName = "expense_record", indices = [Index("createdAt"), Index("agentRole")])
data class ExpenseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long?,
    val providerName: String,
    val modelId: String,
    val agentRole: AgentRole?,
    val requestType: RequestType,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val videoSeconds: Double = 0.0,
    val characters: Long = 0,
    /** Set only when the figure comes from measured usage. */
    val actualCost: Double? = null,
    val estimatedCost: Double? = null,
    val confidence: CostConfidence,
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null,
    val unitPrice: Double? = null,
    val succeeded: Boolean = true,
    val errorCode: String? = null,
    val currency: String = "USD",
    val createdAt: Long,
) {
    val amount: Double get() = actualCost ?: estimatedCost ?: 0.0
}

@Serializable
@Entity(tableName = "monthly_budget")
data class MonthlyBudget(
    /** yyyy-MM; the row with DEFAULT_KEY applies to months without their own row. */
    @PrimaryKey val yearMonth: String,
    val budget: Double,
    val warningPercent: Int = 80,
    /** Hard stop: new paid requests are refused at this amount. */
    val maxSpending: Double? = null,
    val currency: String = "USD",
) {
    companion object {
        const val DEFAULT_KEY = "default"
    }
}

@Serializable
@Entity(
    tableName = "provider_budget",
    foreignKeys = [ForeignKey(AIProvider::class, ["id"], ["providerId"], onDelete = ForeignKey.CASCADE)],
)
data class ProviderBudget(@PrimaryKey val providerId: Long, val monthlyLimit: Double)

@Serializable
@Entity(tableName = "app_setting")
data class AppSetting(@PrimaryKey val key: String, val value: String)

@Serializable
@Entity(tableName = "activity_entry", indices = [Index("createdAt")])
data class ActivityEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String = "",
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "app_notice", indices = [Index(value = ["dedupeKey"], unique = true)])
data class AppNotice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupeKey: String,
    val severity: NoticeSeverity,
    val title: String,
    val body: String,
    val createdAt: Long,
    val readAt: Long? = null,
)

@Entity(tableName = "migration_session")
data class MigrationSession(
    @PrimaryKey val id: String,
    val role: MigrationRole,
    val status: MigrationStatus,
    val peerName: String = "",
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Entity(
    tableName = "migration_manifest",
    foreignKeys = [ForeignKey(MigrationSession::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class MigrationManifestRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val manifestJson: String,
    val createdAt: Long,
)

@Entity(tableName = "backup_record")
data class BackupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val schemaVersion: Int,
    val createdAt: Long,
)
