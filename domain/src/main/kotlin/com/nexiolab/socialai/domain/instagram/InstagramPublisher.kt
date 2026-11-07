package com.nexiolab.socialai.domain.instagram

import com.nexiolab.socialai.domain.content.ContentWorkflow
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AnalyticsRecord
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.ContentType
import com.nexiolab.socialai.domain.model.InstagramAccount
import com.nexiolab.socialai.domain.model.InstagramPost
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.model.NoticeSeverity
import com.nexiolab.socialai.domain.model.PublishState
import com.nexiolab.socialai.domain.model.PublishState.*
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.ContentRepository
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

enum class ContainerStatus { IN_PROGRESS, FINISHED, PUBLISHED, ERROR, EXPIRED, UNKNOWN }

data class ContainerState(val status: ContainerStatus, val message: String? = null)

data class IgProfile(val id: String, val username: String, val accountType: String)

data class IgToken(val accessToken: String, val expiresInSeconds: Long?, val userId: String? = null)

data class IgMedia(val id: String, val caption: String, val permalink: String?)

enum class ContainerKind { IMAGE, REEL, STORY_IMAGE, STORY_VIDEO }

/**
 * [delivered] says whether Instagram answered. False means the request may or may not have taken
 * effect, which is the case that must never be retried blindly.
 */
class InstagramException(message: String, val delivered: Boolean, val authFailure: Boolean = false, val code: Int? = null) : Exception(message)

/** Official Instagram Platform API calls only. */
interface InstagramApi {
    suspend fun profile(token: String): IgProfile
    suspend fun createContainer(token: String, igUserId: String, kind: ContainerKind, mediaUrl: String, caption: String): String
    /** A carousel child: an image container created with is_carousel_item=true. It is never published on its own. */
    suspend fun createCarouselItem(token: String, igUserId: String, imageUrl: String): String
    /** The CAROUSEL parent container over finished child containers, in order. */
    suspend fun createCarousel(token: String, igUserId: String, childIds: List<String>, caption: String): String
    suspend fun containerState(token: String, containerId: String): ContainerState
    suspend fun publish(token: String, igUserId: String, containerId: String): String
    suspend fun permalink(token: String, mediaId: String): String?
    suspend fun recentMedia(token: String, igUserId: String): List<IgMedia>
    suspend fun insights(token: String, mediaId: String): Map<String, Double>
    suspend fun refreshToken(token: String): IgToken
    suspend fun exchangeCode(appId: String, appSecret: String, redirectUri: String, code: String): IgToken
}

/** Meta fetches media from a public URL, so a file must be reachable there while it publishes. */
interface MediaHost {
    suspend fun isConfigured(): Boolean
    suspend fun upload(path: String, mimeType: String, key: String): String
    suspend fun delete(key: String)
}

interface InstagramRepository {
    fun observeAccount(): Flow<InstagramAccount?>
    fun observePosts(): Flow<List<InstagramPost>>
    suspend fun account(): InstagramAccount?
    suspend fun saveAccount(account: InstagramAccount)
    suspend fun clearAccount()
    suspend fun post(id: Long): InstagramPost?
    suspend fun byKey(key: String): InstagramPost?

    /** The newest publication row for a content item, whatever approval created it. */
    suspend fun latestFor(contentItemId: Long): InstagramPost?
    suspend fun insert(post: InstagramPost): Long
    suspend fun save(post: InstagramPost)
    suspend fun published(limit: Int): List<InstagramPost>
    suspend fun saveAnalytics(records: List<AnalyticsRecord>)
}

/**
 * Publication state machine. A step's outcome is stored before the next step starts, so a process
 * death resumes from the last known state. The final publish call writes PUBLISH_UNCERTAIN first:
 * if the app dies or the network drops mid-call, the record says so, and only the container's own
 * status can release it. The unique idempotency key stops a second row for the same approval.
 */
class InstagramPublisher(
    private val api: InstagramApi,
    private val host: MediaHost,
    private val repository: InstagramRepository,
    private val content: ContentRepository,
    private val media: MediaRepository,
    private val credentials: CredentialStore,
    private val activity: ActivityRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val pollMillis: Long = 5_000,
) {
    class Blocked(message: String) : Exception(message)

    private suspend fun token() = credentials.get(CredentialStore.INSTAGRAM_TOKEN)
        ?: throw Blocked(Msg.IG_CONNECT_FIRST())

    private fun keyFor(item: ContentItem) = "content-${item.id}-${item.approvedAt}"

    /** Serializes begin/run/resolve so two workers can never step the same post at once. */
    private val lock = Mutex()

    /** Creates or resumes the publication for an approved post. Refuses anything else. */
    suspend fun begin(itemId: Long): InstagramPost = lock.withLock { beginLocked(itemId) }

    private suspend fun beginLocked(itemId: Long): InstagramPost {
        val item = content.item(itemId) ?: throw Blocked(Msg.POST_GONE())
        if (item.status != ContentStatus.APPROVED && item.status != ContentStatus.SCHEDULED && item.status != ContentStatus.PUBLISHING && item.status != ContentStatus.FAILED) {
            throw Blocked(Msg.ONLY_APPROVED_PUBLISHABLE())
        }
        val children = if (item.type == ContentType.CAROUSEL) content.carouselMedia(item.id) else emptyList()
        ContentWorkflow.problemForPublishing(item, children)?.let { throw Blocked(it) }
        val account = repository.account() ?: throw Blocked(Msg.IG_CONNECT_FIRST())
        if (account.needsReconnect) throw Blocked(Msg.IG_RECONNECT_FIRST())
        if (!host.isConfigured()) throw Blocked(Msg.IG_SETUP_HOSTING())
        // Re-approval changes the key, so the newest row for this item decides first.
        val latest = repository.latestFor(item.id)
        if (latest != null) {
            if (latest.state == PUBLISHED) {
                markItemPublished(item.id)
                throw Blocked(Msg.ALREADY_PUBLISHED())
            }
            if (latest.state != FAILED) {
                content.save(item.copy(status = ContentStatus.PUBLISHING, errorMessage = null))
                return latest
            }
            if (!latest.containerId.isNullOrBlank()) checkOldContainer(latest)
        }
        val key = keyFor(item)
        val existing = repository.byKey(key)
        val post = when {
            existing == null -> {
                val now = clock.millis()
                val row = InstagramPost(contentItemId = item.id, igUserId = account.igUserId, idempotencyKey = key, createdAt = now, updatedAt = now)
                row.copy(id = repository.insert(row))
            }
            existing.state == PUBLISHED -> {
                markItemPublished(item.id)
                throw Blocked(Msg.ALREADY_PUBLISHED())
            }
            existing.state == FAILED -> update(existing.copy(state = PENDING, hostedMediaUrl = null, containerId = null, carouselProgress = null, lastError = null))
            else -> existing
        }
        content.save(item.copy(status = ContentStatus.PUBLISHING, errorMessage = null))
        return post
    }

    /**
     * A failed row that still holds a container may have been published anyway (a 4xx or a timeout on
     * the final call proves little). Ask Instagram before a second post is created.
     */
    private suspend fun checkOldContainer(old: InstagramPost) {
        val state = try {
            api.containerState(token(), old.containerId.orEmpty())
        } catch (e: InstagramException) {
            // Auth failures say nothing about the container either.
            if (!e.delivered || e.authFailure) throw Blocked(Msg.IG_CHECK_EARLIER_ATTEMPT_LATER())
            return
        }
        if (state.status == ContainerStatus.PUBLISHED) {
            confirmPublished(old, findPublishedId(old))
            throw Blocked(Msg.ALREADY_PUBLISHED())
        }
    }

    private suspend fun markItemPublished(itemId: Long) {
        val item = content.item(itemId) ?: return
        if (item.status != ContentStatus.PUBLISHED) content.save(item.copy(status = ContentStatus.PUBLISHED, errorMessage = null))
    }

    /** Moves the publication forward as far as it can go right now. */
    suspend fun run(postId: Long): InstagramPost = lock.withLock { runLocked(postId) }

    private suspend fun runLocked(postId: Long): InstagramPost {
        var post = repository.post(postId) ?: throw Blocked(Msg.PUBLICATION_GONE())
        if (post.state == PUBLISHED) {
            markItemPublished(post.contentItemId)
            return post
        }
        while (true) {
            val before = post.state
            post = try {
                step(post)
            } catch (e: InstagramException) {
                // A carousel step saves progress as it goes, so handle the row as stored, not as this loop saw it.
                return handle(repository.post(postId) ?: post, e)
            }
            if (post.state == before || post.state == PUBLISHED || post.state == FAILED) return post
        }
    }

    private suspend fun step(post: InstagramPost): InstagramPost = when (post.state) {
        PENDING -> upload(post)
        MEDIA_UPLOADED -> createContainer(post)
        CONTAINER_CREATED -> awaitProcessing(post)
        READY_TO_PUBLISH -> publish(post)
        PUBLISH_UNCERTAIN -> reconcile(post)
        PUBLISHED, FAILED -> post
    }

    private suspend fun upload(post: InstagramPost): InstagramPost {
        val item = itemOf(post)
        if (item.type == ContentType.CAROUSEL) return uploadCarousel(post, item)
        val file = media.byId(item.mediaId ?: throw Blocked(Msg.POST_NO_MEDIA())) ?: throw Blocked(Msg.MEDIA_FILE_MISSING())
        val url = try {
            host.upload(media.absolutePath(file), file.mimeType, hostedKey(post))
        } catch (e: java.io.IOException) {
            throw InstagramException(Msg.HOSTING_UNREACHABLE(e.message.orEmpty()), delivered = true)
        }
        return update(post.copy(state = MEDIA_UPLOADED, hostedMediaUrl = url))
    }

    private suspend fun createContainer(post: InstagramPost): InstagramPost {
        val item = itemOf(post)
        if (item.type == ContentType.CAROUSEL) return createCarousel(post, item)
        val file = media.byId(item.mediaId ?: throw Blocked(Msg.POST_NO_MEDIA())) ?: throw Blocked(Msg.MEDIA_FILE_MISSING())
        val video = file.kind == MediaKind.VIDEO
        val kind = when (item.type) {
            ContentType.STORY -> if (video) ContainerKind.STORY_VIDEO else ContainerKind.STORY_IMAGE
            else -> if (video) ContainerKind.REEL else ContainerKind.IMAGE
        }
        val id = api.createContainer(token(), post.igUserId, kind, post.hostedMediaUrl.orEmpty(), caption(item))
        return update(post.copy(state = CONTAINER_CREATED, containerId = id))
    }

    private fun caption(item: ContentItem) = listOf(item.caption, item.hashtags).filter(String::isNotBlank).joinToString("\n\n")

    /** Saves one image's progress (and everything before it) before the next network step. */
    private suspend fun saveChildren(post: InstagramPost, children: List<CarouselChild>) =
        update(post.copy(carouselProgress = CarouselProgress.encode(children)))

    private fun List<CarouselChild>.with(changed: CarouselChild) = map { if (it.position == changed.position) changed else it }

    /**
     * Carousel step 1: every image goes to the hosting service under its own key. The image list is
     * frozen into the row first, so the owner's order cannot change under a half-finished attempt.
     */
    private suspend fun uploadCarousel(start: InstagramPost, item: ContentItem): InstagramPost {
        var post = start
        var children = CarouselProgress.decode(post.carouselProgress)
        if (children.isEmpty()) {
            val files = content.carouselMedia(item.id)
            ContentWorkflow.carouselProblem(files)?.let { throw Blocked(it) }
            children = files.mapIndexed { i, file -> CarouselChild(position = i, mediaId = file.id) }
            post = saveChildren(post, children)
        }
        for (child in children.filter { it.hostedUrl == null }) {
            val file = media.byId(child.mediaId) ?: throw Blocked(Msg.MEDIA_FILE_MISSING())
            if (file.kind != MediaKind.IMAGE) throw Blocked(Msg.CAROUSEL_IMAGES_ONLY(file.displayName))
            val url = try {
                host.upload(media.absolutePath(file), file.mimeType, hostedKey(post, child.position))
            } catch (e: java.io.IOException) {
                throw InstagramException(Msg.HOSTING_UNREACHABLE(e.message.orEmpty()), delivered = true)
            }
            children = children.with(child.copy(hostedUrl = url))
            post = saveChildren(post, children)
        }
        return update(post.copy(state = MEDIA_UPLOADED))
    }

    /**
     * Carousel step 2: a child container per image, each waited on until FINISHED, and only then the
     * CAROUSEL parent. If any child errors or expires the post fails here and no parent exists.
     */
    private suspend fun createCarousel(start: InstagramPost, item: ContentItem): InstagramPost {
        var post = start
        var children = CarouselProgress.decode(post.carouselProgress)
        if (children.isEmpty() || children.any { it.hostedUrl == null }) throw Blocked(Msg.POST_NO_MEDIA())
        for (child in children.filter { it.containerId == null }) {
            val id = api.createCarouselItem(token(), post.igUserId, child.hostedUrl.orEmpty())
            children = children.with(child.copy(containerId = id))
            post = saveChildren(post, children)
        }
        var round = 0
        while (children.any { !it.finished }) {
            for (child in children.filter { !it.finished }) {
                val state = api.containerState(token(), child.containerId.orEmpty())
                when (state.status) {
                    ContainerStatus.FINISHED -> {
                        children = children.with(child.copy(finished = true))
                        post = saveChildren(post, children)
                    }
                    ContainerStatus.ERROR, ContainerStatus.EXPIRED, ContainerStatus.PUBLISHED ->
                        return fail(post, state.message ?: Msg.CAROUSEL_ITEM_FAILED(child.position + 1))
                    ContainerStatus.IN_PROGRESS, ContainerStatus.UNKNOWN -> Unit
                }
            }
            if (children.all { it.finished }) break
            if (++round >= MAX_POLLS) return post
            delay(pollMillis)
        }
        val parent = api.createCarousel(token(), post.igUserId, children.sortedBy { it.position }.map { it.containerId.orEmpty() }, caption(item))
        return update(post.copy(state = CONTAINER_CREATED, containerId = parent))
    }

    private suspend fun awaitProcessing(post: InstagramPost): InstagramPost {
        repeat(MAX_POLLS) {
            val state = api.containerState(token(), post.containerId.orEmpty())
            when (state.status) {
                ContainerStatus.FINISHED -> return update(post.copy(state = READY_TO_PUBLISH))
                ContainerStatus.PUBLISHED -> return confirmPublished(post, null)
                ContainerStatus.ERROR, ContainerStatus.EXPIRED ->
                    return fail(post, state.message ?: Msg.IG_PROCESS_FAILED())
                ContainerStatus.IN_PROGRESS, ContainerStatus.UNKNOWN -> delay(pollMillis)
            }
        }
        return post
    }

    private suspend fun publish(post: InstagramPost): InstagramPost {
        val pending = update(post.copy(state = PUBLISH_UNCERTAIN, attempts = post.attempts + 1))
        return try {
            confirmPublished(pending, api.publish(token(), post.igUserId, post.containerId.orEmpty()))
        } catch (e: InstagramException) {
            // A definite refusal means nothing was posted; anything else stays uncertain.
            if (e.delivered) fail(pending, e.message.orEmpty()) else update(pending.copy(lastError = e.message))
        }
    }

    /** Asks Instagram what actually happened. Only a clear answer changes the state. */
    private suspend fun reconcile(post: InstagramPost): InstagramPost {
        val state = api.containerState(token(), post.containerId.orEmpty())
        return when (state.status) {
            ContainerStatus.PUBLISHED -> confirmPublished(post, findPublishedId(post))
            ContainerStatus.FINISHED -> update(post.copy(state = READY_TO_PUBLISH, lastError = null))
            ContainerStatus.ERROR, ContainerStatus.EXPIRED -> fail(post, state.message ?: Msg.IG_NOT_PUBLISHED())
            ContainerStatus.IN_PROGRESS, ContainerStatus.UNKNOWN -> post
        }
    }

    private suspend fun findPublishedId(post: InstagramPost): String? {
        val item = itemOf(post)
        // A blank caption would match the newest post whatever it is.
        if (item.caption.isBlank()) return null
        return runCatching {
            api.recentMedia(token(), post.igUserId).firstOrNull { it.caption.startsWith(item.caption.take(CAPTION_MATCH)) }?.id
        }.getOrNull()
    }

    private suspend fun confirmPublished(post: InstagramPost, mediaId: String?): InstagramPost {
        val link = mediaId?.let { id -> runCatching { api.permalink(token(), id) }.getOrNull() }
        val now = clock.millis()
        val done = update(post.copy(state = PUBLISHED, publishedMediaId = mediaId, permalink = link, publishedAt = now, lastError = null))
        content.save(itemOf(post).copy(status = ContentStatus.PUBLISHED, errorMessage = null))
        cleanUp(post)
        activity.log(Msg.LOG_PUBLISHED(), itemOf(post).title)
        return done
    }

    private suspend fun fail(post: InstagramPost, message: String): InstagramPost {
        val failed = update(post.copy(state = FAILED, lastError = message))
        content.save(itemOf(post).copy(status = ContentStatus.FAILED, errorMessage = message))
        cleanUp(post)
        activity.notice("publish.${post.id}.${post.attempts}", NoticeSeverity.ERROR, Msg.NOTICE_PUBLISH_FAILED(), message)
        return failed
    }

    private suspend fun handle(post: InstagramPost, e: InstagramException): InstagramPost {
        if (e.authFailure) {
            repository.account()?.let { repository.saveAccount(it.copy(needsReconnect = true)) }
            activity.notice("instagram.reconnect", NoticeSeverity.ERROR, Msg.NOTICE_RECONNECT_TITLE(), Msg.NOTICE_RECONNECT_BODY())
        }
        return when {
            e.delivered && !e.authFailure && post.state != PUBLISH_UNCERTAIN -> fail(post, e.message.orEmpty())
            else -> update(post.copy(lastError = e.message))
        }
    }

    /** The owner's verdict after checking Instagram, for when the API cannot settle an uncertain post. */
    suspend fun resolveManually(postId: Long, wasPublished: Boolean) {
        lock.withLock {
            val post = repository.post(postId) ?: return
            if (post.state != PUBLISH_UNCERTAIN) throw Blocked(Msg.NOT_WAITING_DECISION())
            if (wasPublished) confirmPublished(post, findPublishedId(post)) else fail(post, Msg.CONFIRMED_NOT_PUBLISHED())
        }
    }

    private fun hostedKey(post: InstagramPost) = "nexio/${post.idempotencyKey}"

    /** A carousel image gets its own key, so deleting one never touches another. */
    private fun hostedKey(post: InstagramPost, position: Int) = "${hostedKey(post)}/$position"

    /** Every key this attempt may have uploaded: one per carousel image, or the single file. */
    private suspend fun hostedKeys(post: InstagramPost): List<String> {
        // Progress is saved image by image, so read the stored row rather than trusting the caller's copy.
        val stored = repository.post(post.id)?.carouselProgress ?: post.carouselProgress
        val children = CarouselProgress.decode(stored)
        return if (children.isEmpty()) listOf(hostedKey(post)) else children.map { hostedKey(post, it.position) }
    }

    /** Deletes every hosted file of the attempt, even if one delete fails; a leftover is reported to the owner. */
    private suspend fun cleanUp(post: InstagramPost) {
        val remaining = ArrayList<String>()
        for (key in hostedKeys(post)) {
            try {
                host.delete(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remaining += key
            }
        }
        if (remaining.isNotEmpty()) {
            val title = content.item(post.contentItemId)?.title ?: "#${post.contentItemId}"
            activity.notice(
                "cleanup.${post.id}", NoticeSeverity.WARNING,
                Msg.NOTICE_PUBLIC_FILE_REMAINS(), Msg.NOTICE_PUBLIC_FILE_REMAINS_BODY(title, remaining.joinToString(", ")),
            )
        }
    }

    private suspend fun itemOf(post: InstagramPost) = content.item(post.contentItemId) ?: throw Blocked(Msg.POST_GONE())

    private suspend fun update(post: InstagramPost): InstagramPost {
        val next = post.copy(updatedAt = clock.millis())
        repository.save(next)
        return next
    }

    private companion object {
        const val MAX_POLLS = 60
        const val CAPTION_MATCH = 60
    }
}

class InstagramAnalytics(
    private val api: InstagramApi,
    private val repository: InstagramRepository,
    private val credentials: CredentialStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** Stores real figures from Instagram; a post it cannot read is skipped, never estimated. */
    suspend fun sync(): Int {
        val token = credentials.get(CredentialStore.INSTAGRAM_TOKEN) ?: return 0
        val now = clock.millis()
        val records = repository.published(RECENT_POSTS).mapNotNull { it.publishedMediaId }.flatMap { id ->
            runCatching { api.insights(token, id) }.getOrDefault(emptyMap()).map { (metric, value) ->
                AnalyticsRecord(mediaId = id, metric = metric, value = value, capturedAt = now)
            }
        }
        if (records.isNotEmpty()) repository.saveAnalytics(records)
        return records.size
    }

    private companion object {
        const val RECENT_POSTS = 30
    }
}
