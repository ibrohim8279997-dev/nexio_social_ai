package com.nexiolab.socialai.domain.content

import com.nexiolab.socialai.domain.agents.AgentEngine
import com.nexiolab.socialai.domain.agents.LenientJson
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.AgentTask
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentPlan
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.ContentType
import com.nexiolab.socialai.domain.model.TaskStatus
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.ContentRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Clock
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeParseException

@Serializable
internal data class PlannedPost(
    val title: String,
    val description: String = "",
    val caption: String = "",
    val hashtags: String = "",
    val type: String = "IMAGE_POST",
    val day: Int,
    val time: String = "10:00",
)

/** Everything it stores waits in REVIEW: the owner approves before anything can be scheduled. */
class ContentPlanner(
    private val engine: AgentEngine,
    private val content: ContentRepository,
    private val activity: ActivityRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** How many images the media library holds; a carousel is proposed only when there are at least two. */
    private val libraryImageCount: suspend () -> Int = { 0 },
) {
    data class MonthResult(val planId: Long, val items: Int, val flaggedDuplicates: Int, val needsOwnerReview: Boolean)

    suspend fun generateMonth(month: YearMonth, parentId: Long? = null): MonthResult {
        val carousels = libraryImageCount() >= ContentWorkflow.CAROUSEL_MIN
        val strategy = engine.runSpecialist(
            AgentRole.MARKETING,
            Msg.TASK_MARKETING_STRATEGY(month),
            "Write the marketing strategy for $month: objectives, audience focus, content pillars and what to measure. Keep it under 300 words.",
            parentId,
        )
        val calendar = engine.runSpecialist(
            AgentRole.CONTENT,
            Msg.TASK_CONTENT_CALENDAR(month),
            calendarBrief(month, strategy.output, carousels),
            parentId,
            check = { text -> validationProblem(text, month) },
        )
        val posts = parse(calendar.output)
            ?: throw AiException(AiError.SERVER, Msg.CONTENT_CALENDAR_UNREADABLE())
        val existing = content.recentCaptions(RECENT_CAPTIONS)
        val now = clock.millis()
        var flagged = 0
        val seen = existing.toMutableList()
        val items = posts.mapNotNull { post -> toItem(post, month, now, carousels) }.map { item ->
            val duplicate = DuplicateDetector.closestMatch(item.caption, seen) != null
            seen += item.caption
            if (duplicate) flagged++
            if (duplicate) item.copy(qualityNotes = Msg.SIMILAR_CAPTION()) else item
        }
        val plan = ContentPlan(yearMonth = month.toString(), title = Msg.PLAN_TITLE(month), strategy = strategy.output, createdAt = now)
        val planId = content.savePlanWithItems(plan, items)
        activity.log(Msg.LOG_PLAN_READY(), Msg.PLAN_WAITING_REVIEW(items.size, month))
        return MonthResult(
            planId, items.size, flagged,
            needsOwnerReview = strategy.status == TaskStatus.WAITING_APPROVAL || calendar.status == TaskStatus.WAITING_APPROVAL,
        )
    }

    /** One post from an idea. Saved for review, like everything agents write. */
    suspend fun draftPost(idea: String, type: ContentType, scheduledAt: Long?): ContentItem {
        val task: AgentTask = engine.runSpecialist(
            AgentRole.CONTENT,
            Msg.TASK_POST(idea.take(60)),
            "Write one ${type.labelEn} about this idea: $idea\n\n" +
                """Answer with JSON only: {"title":"","description":"","caption":"","hashtags":"#one #two"}""",
        )
        val json = LenientJson.parse(task.output)?.let { LenientJson.decode(DraftPost.serializer(), it) }
            ?: throw AiException(AiError.SERVER, Msg.CONTENT_POST_UNREADABLE())
        val now = clock.millis()
        val similar = DuplicateDetector.closestMatch(json.caption, content.recentCaptions(RECENT_CAPTIONS)) != null
        val notes = listOf(task.reviewNotes, if (similar) Msg.SIMILAR_CAPTION() else "").filter { it.isNotBlank() }.joinToString("\n")
        val item = ContentItem(
            title = json.title.ifBlank { idea.take(60) },
            description = json.description,
            caption = json.caption,
            hashtags = json.hashtags,
            type = type,
            scheduledAt = scheduledAt,
            status = ContentStatus.REVIEW,
            agentRole = AgentRole.CONTENT,
            qualityNotes = notes,
            createdAt = now,
            updatedAt = now,
        )
        return item.copy(id = content.save(item))
    }

    private fun calendarBrief(month: YearMonth, strategy: String, carousels: Boolean) = """Create the content calendar for $month (it has ${month.lengthOfMonth()} days).
Follow this strategy:
<strategy>
$strategy
</strategy>

Match the content frequency and formats in the business facts. Answer with a JSON array only:
[{"title":"","description":"what the post shows","caption":"the full caption","hashtags":"#one #two","type":"${if (carousels) "IMAGE_POST|CAROUSEL|REEL|STORY" else "IMAGE_POST|REEL|STORY"}","day":1,"time":"10:00"}]""" +
        if (carousels) "\nA CAROUSEL is several images in one post; the owner picks 2 to 10 images from the media library before approving." else ""

    internal fun parse(text: String): List<PlannedPost>? =
        LenientJson.parse(text)?.let { LenientJson.decode(ListSerializer(PlannedPost.serializer()), it) }

    private fun validationProblem(text: String, month: YearMonth): String? {
        val posts = parse(text) ?: return "The answer must be the requested JSON array."
        if (posts.isEmpty()) return "The calendar has no posts."
        val bad = posts.filter { it.day !in 1..month.lengthOfMonth() }
        if (bad.isNotEmpty()) return "These posts have a day outside the month: ${bad.joinToString { it.title }}."
        return null
    }

    private fun toItem(post: PlannedPost, month: YearMonth, now: Long, carousels: Boolean): ContentItem? {
        if (post.day !in 1..month.lengthOfMonth()) return null
        val time = try {
            LocalTime.parse(post.time)
        } catch (e: DateTimeParseException) {
            LocalTime.of(10, 0)
        }
        val at = month.atDay(post.day).atTime(time).atZone(clock.zone).toInstant().toEpochMilli()
        return ContentItem(
            title = post.title,
            description = post.description,
            caption = post.caption,
            hashtags = post.hashtags,
            // Like a single post's picture, a carousel's images are chosen by the owner during review; without two library images it stays a single image post.
            type = ContentType.entries.firstOrNull { it.name == post.type }
                ?.takeUnless { it == ContentType.CAROUSEL && !carousels } ?: ContentType.IMAGE_POST,
            scheduledAt = at,
            status = ContentStatus.REVIEW,
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val RECENT_CAPTIONS = 100
    }
}

@Serializable
private data class DraftPost(val title: String = "", val description: String = "", val caption: String = "", val hashtags: String = "")

/** Word-set overlap: catches a caption that was lightly reworded rather than rewritten. */
object DuplicateDetector {
    private const val THRESHOLD = 0.75
    private val word = Regex("\\p{L}+")

    private fun words(text: String) = word.findAll(text.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()

    fun similarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    fun closestMatch(text: String, others: List<String>): String? =
        others.firstOrNull { similarity(text, it) >= THRESHOLD }
}
