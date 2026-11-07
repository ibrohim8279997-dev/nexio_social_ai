package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.content.ContentWorkflow
import com.nexiolab.socialai.domain.instagram.*
import com.nexiolab.socialai.domain.model.*
import com.nexiolab.socialai.domain.repository.ContentRepository
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.repository.MediaRepository
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InstagramPublisherTest {
    private class FakeApi(private val events: MutableList<String> = ArrayList()) : InstagramApi {
        var publishCalls = 0
        var publishResult: () -> String = { "media-1" }
        var containers = 0
        var items = 0
        var parents = 0
        var parentCaption: String? = null
        /** Per-container answers for carousel children (ids k1, k2, ...); anything not listed is FINISHED. */
        val childStates = HashMap<String, ContainerState>()
        var onPublish: () -> Unit = {}
        val states = ArrayDeque(listOf(ContainerState(ContainerStatus.FINISHED)))
        override suspend fun profile(token: String) = IgProfile("ig", "kofe", "BUSINESS")
        override suspend fun createContainer(token: String, igUserId: String, kind: ContainerKind, mediaUrl: String, caption: String): String { containers++; return "c$containers" }
        var stateError: InstagramException? = null
        override suspend fun containerState(token: String, containerId: String): ContainerState {
            stateError?.let { throw it }
            events += "state:$containerId"
            if (containerId.startsWith("k")) return childStates[containerId] ?: ContainerState(ContainerStatus.FINISHED)
            return if (states.size > 1) states.removeFirst() else states.first()
        }
        override suspend fun createCarouselItem(token: String, igUserId: String, imageUrl: String): String { items++; events += "item:$imageUrl"; return "k$items" }
        override suspend fun createCarousel(token: String, igUserId: String, childIds: List<String>, caption: String): String {
            parents++
            parentCaption = caption
            events += "parent:${childIds.joinToString(",")}"
            return "p$parents"
        }
        override suspend fun publish(token: String, igUserId: String, containerId: String): String { publishCalls++; onPublish(); return publishResult() }
        override suspend fun permalink(token: String, mediaId: String) = "https://instagram.com/p/x"
        override suspend fun recentMedia(token: String, igUserId: String) = listOf(IgMedia("found", "Hello", null))
        override suspend fun insights(token: String, mediaId: String) = mapOf("reach" to 10.0)
        override suspend fun refreshToken(token: String) = IgToken(token, 100)
        override suspend fun exchangeCode(appId: String, appSecret: String, redirectUri: String, code: String) = IgToken("t", 100, "ig")
    }

    private class FakeHost(private val events: MutableList<String> = ArrayList()) : MediaHost {
        val deleted = ArrayList<String>()
        var failUploadOf: String? = null
        override suspend fun isConfigured() = true
        override suspend fun upload(path: String, mimeType: String, key: String): String {
            if (failUploadOf != null && key.endsWith(failUploadOf!!)) throw java.io.IOException("host down")
            events += "upload:$key"
            return "https://host/$key"
        }
        var failDelete = false
        override suspend fun delete(key: String) {
            if (failDelete) throw java.io.IOException("gone")
            deleted += key
        }
    }

    private class FakeRepo : InstagramRepository {
        var acct: InstagramAccount? = InstagramAccount("ig", "kofe", connectedAt = 0)
        val posts = LinkedHashMap<Long, InstagramPost>()
        private var next = 1L
        override fun observeAccount() = flowOf(acct)
        override fun observePosts() = flowOf(posts.values.toList())
        override suspend fun account() = acct
        override suspend fun saveAccount(account: InstagramAccount) { acct = account }
        override suspend fun clearAccount() { acct = null }
        override suspend fun post(id: Long) = posts[id]
        override suspend fun byKey(key: String) = posts.values.firstOrNull { it.idempotencyKey == key }
        override suspend fun latestFor(contentItemId: Long) = posts.values.filter { it.contentItemId == contentItemId }.maxByOrNull { it.id }
        override suspend fun insert(post: InstagramPost): Long { val id = next++; posts[id] = post.copy(id = id); return id }
        override suspend fun save(post: InstagramPost) { posts[post.id] = post }
        override suspend fun published(limit: Int) = posts.values.filter { it.state == PublishState.PUBLISHED }
        override suspend fun saveAnalytics(records: List<AnalyticsRecord>) {}
    }

    private class FakeContent : ContentRepository {
        val items = HashMap<Long, ContentItem>()
        override fun observePlans() = emptyFlow<List<ContentPlan>>()
        override fun observeBetween(fromMillis: Long, toMillis: Long) = emptyFlow<List<ContentItem>>()
        override fun observeAll() = emptyFlow<List<ContentItem>>()
        override fun observeStatusCounts() = emptyFlow<Map<ContentStatus, Int>>()
        override fun observeUpcoming(now: Long, limit: Int) = emptyFlow<List<ContentItem>>()
        override suspend fun item(id: Long) = items[id]
        override suspend fun planFor(yearMonth: String) = null
        override suspend fun savePlan(plan: ContentPlan) = 0L
        override suspend fun savePlanWithItems(plan: ContentPlan, items: List<ContentItem>) = 0L
        override suspend fun save(item: ContentItem): Long { items[item.id] = item; return item.id }
        override suspend fun delete(item: ContentItem) {}
        val carousel = HashMap<Long, List<MediaFile>>()
        override suspend fun carouselMedia(itemId: Long) = carousel[itemId].orEmpty()
        override suspend fun setCarouselMedia(itemId: Long, mediaIds: List<Long>) {}
        override suspend fun dueForPublishing(now: Long) = emptyList<ContentItem>()
        override suspend fun recentCaptions(limit: Int) = emptyList<String>()
    }

    private class FakeMedia : MediaRepository {
        private val file = MediaFile(1, MediaKind.IMAGE, "image/a.png", "a", "image/png", 1, "x", createdAt = 0)
        override fun observeAll() = flowOf(listOf(file))
        val others = HashMap<Long, MediaFile>()
        override suspend fun byId(id: Long) = others[id] ?: file
        override suspend fun import(uri: String, source: MediaSource) = file
        override suspend fun store(bytes: ByteArray, kind: MediaKind, mimeType: String, name: String, source: MediaSource) = file
        override suspend fun registerFile(path: String, kind: MediaKind, mimeType: String, name: String, source: MediaSource) = file
        override fun absolutePath(media: MediaFile) = "/x"
        override suspend fun delete(media: MediaFile) {}
    }

    private val events = ArrayList<String>()
    private val api = FakeApi(events)
    private val host = FakeHost(events)
    private val repo = FakeRepo()
    private val content = FakeContent()
    private val credentials = FakeCredentials()
    private val activity = FakeActivity()
    private val mediaRepo = FakeMedia()
    private val publisher = InstagramPublisher(api, host, repo, content, mediaRepo, credentials, activity, pollMillis = 0)

    private suspend fun approved(id: Long = 1): ContentItem {
        credentials.put(CredentialStore.INSTAGRAM_TOKEN, "t")
        val item = ContentItem(id = id, title = "T", caption = "Hello", mediaId = 1, status = ContentStatus.APPROVED, approvedAt = 5, createdAt = 0, updatedAt = 0)
        content.save(item)
        return item
    }

    private val noResponse = InstagramException("timeout", delivered = false)

    @Test fun happyPathPublishesOnceAndCleansHosting() = runTest {
        approved()
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertEquals("media-1", post.publishedMediaId)
        assertEquals(ContentStatus.PUBLISHED, content.items[1]!!.status)
        assertEquals(1, api.publishCalls)
        assertEquals(1, host.deleted.size)
    }

    @Test fun unapprovedContentCanNeverEnter() = runTest {
        approved()
        content.save(content.items[1]!!.copy(status = ContentStatus.REVIEW, approvedAt = null))
        try { publisher.begin(1); fail("expected block") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("approved")) }
        assertEquals(0, api.containers)
    }

    @Test fun networkFailureOnPublishIsNeverRetriedBlindly() = runTest {
        approved()
        api.publishResult = { throw noResponse }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        api.states += ContainerState(ContainerStatus.UNKNOWN)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISH_UNCERTAIN, post.state)
        assertEquals(1, api.publishCalls)
        val again = publisher.run(post.id)
        assertEquals(PublishState.PUBLISH_UNCERTAIN, again.state)
        assertEquals("still only one publish call", 1, api.publishCalls)
    }

    @Test fun uncertainPostThatActuallyWentThroughBecomesPublishedWithoutRepublishing() = runTest {
        approved()
        api.publishResult = { throw noResponse }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        api.states += ContainerState(ContainerStatus.PUBLISHED)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertEquals("found", post.publishedMediaId)
        assertEquals(1, api.publishCalls)
    }

    @Test fun uncertainPostStillUnusedIsSafeToPublish() = runTest {
        approved()
        var attempts = 0
        api.publishResult = { if (attempts++ == 0) throw noResponse else "media-2" }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertEquals(2, api.publishCalls)
    }

    @Test fun definiteRefusalFailsCleanly() = runTest {
        approved()
        api.publishResult = { throw InstagramException("Rejected", delivered = true, code = 400) }
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.FAILED, post.state)
        assertEquals(ContentStatus.FAILED, content.items[1]!!.status)
        assertEquals("Rejected", content.items[1]!!.errorMessage)
    }

    @Test fun processDeathMidPublishResumesFromUncertainNotFromTheStart() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.PUBLISH_UNCERTAIN, containerId = "c9", hostedMediaUrl = "u"))
        api.states.clear()
        api.states += ContainerState(ContainerStatus.PUBLISHED)
        val done = publisher.run(post.id)
        assertEquals(PublishState.PUBLISHED, done.state)
        assertEquals(0, api.publishCalls)
        assertEquals(0, api.containers)
    }

    @Test fun samePublicationCannotBeCreatedTwice() = runTest {
        approved()
        val first = publisher.begin(1)
        val second = publisher.begin(1)
        assertEquals(first.id, second.id)
        assertEquals(1, repo.posts.size)
        publisher.run(first.id)
        content.save(content.items[1]!!.copy(status = ContentStatus.APPROVED))
        try { publisher.begin(1); fail("expected block") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("already")) }
    }

    @Test fun expiredTokenAsksForReconnectAndKeepsThePost() = runTest {
        approved()
        api.publishResult = { "x" }
        val post = publisher.begin(1)
        credentials.put(CredentialStore.INSTAGRAM_TOKEN, "t")
        repo.save(post.copy(state = PublishState.MEDIA_UPLOADED, hostedMediaUrl = "u"))
        val failing = object : InstagramApi by api {
            override suspend fun createContainer(token: String, igUserId: String, kind: ContainerKind, mediaUrl: String, caption: String): String =
                throw InstagramException("expired", delivered = true, authFailure = true, code = 190)
        }
        val result = InstagramPublisher(failing, host, repo, content, FakeMedia(), credentials, FakeActivity(), pollMillis = 0).run(post.id)
        assertEquals(PublishState.MEDIA_UPLOADED, result.state)
        assertTrue(repo.acct!!.needsReconnect)
    }

    @Test fun ownerResolvesUncertainPost() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.PUBLISH_UNCERTAIN, containerId = "c"))
        publisher.resolveManually(post.id, wasPublished = false)
        assertEquals(PublishState.FAILED, repo.posts[post.id]!!.state)
        assertNotNull(content.items[1]!!.errorMessage)
    }

    private suspend fun reapprove(approvedAt: Long = 9) {
        content.save(content.items[1]!!.copy(status = ContentStatus.APPROVED, approvedAt = approvedAt, errorMessage = null))
    }

    @Test fun puttingAPublishingItemBackThroughBeginResumesTheSameRow() = runTest {
        approved()
        val first = publisher.begin(1)
        assertEquals(ContentStatus.PUBLISHING, content.items[1]!!.status)
        repo.save(first.copy(state = PublishState.CONTAINER_CREATED, containerId = "c1", hostedMediaUrl = "u"))
        val again = publisher.begin(1)
        assertEquals(first.id, again.id)
        assertEquals(1, repo.posts.size)
        val done = publisher.run(again.id)
        assertEquals(PublishState.PUBLISHED, done.state)
        assertEquals(0, api.containers)
        assertEquals(1, api.publishCalls)
    }

    @Test fun reapprovalAfterAnUncertainPostResumesInsteadOfCreating() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.PUBLISH_UNCERTAIN, containerId = "c9", hostedMediaUrl = "u"))
        content.save(content.items[1]!!.copy(status = ContentStatus.FAILED))
        reapprove()
        val resumed = publisher.begin(1)
        assertEquals(post.id, resumed.id)
        assertEquals(PublishState.PUBLISH_UNCERTAIN, resumed.state)
        assertEquals(1, repo.posts.size)
        assertEquals(0, api.containers)
        assertEquals(0, api.publishCalls)
    }

    @Test fun reapprovalAfterAReadyPostResumesInsteadOfCreating() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.READY_TO_PUBLISH, containerId = "c9"))
        reapprove()
        assertEquals(post.id, publisher.begin(1).id)
        assertEquals(1, repo.posts.size)
    }

    @Test fun failedPostWhoseContainerIsActuallyPublishedIsNotRepublished() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.FAILED, containerId = "c9", lastError = "400"))
        content.save(content.items[1]!!.copy(status = ContentStatus.FAILED))
        reapprove()
        api.states.clear()
        api.states += ContainerState(ContainerStatus.PUBLISHED)
        try { publisher.begin(1); fail("expected block") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("already")) }
        assertEquals(PublishState.PUBLISHED, repo.posts[post.id]!!.state)
        assertEquals(ContentStatus.PUBLISHED, content.items[1]!!.status)
        assertEquals(1, repo.posts.size)
        assertEquals(0, api.containers)
        assertEquals(0, api.publishCalls)
    }

    @Test fun failedPostIsNotRetriedWhenInstagramCannotBeAsked() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.FAILED, containerId = "c9"))
        content.save(content.items[1]!!.copy(status = ContentStatus.FAILED))
        reapprove()
        api.stateError = noResponse
        try { publisher.begin(1); fail("expected block") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("later")) }
        assertEquals(1, repo.posts.size)
        assertEquals(0, api.containers)
        assertEquals(ContentStatus.APPROVED, content.items[1]!!.status)
    }

    @Test fun failedPostWithAnErroredContainerGetsAFreshAttempt() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.FAILED, containerId = "c9"))
        content.save(content.items[1]!!.copy(status = ContentStatus.FAILED))
        reapprove()
        api.states.clear()
        api.states += ContainerState(ContainerStatus.ERROR)
        val fresh = publisher.begin(1)
        assertTrue(fresh.id != post.id)
        assertEquals(PublishState.PENDING, fresh.state)
        assertEquals(ContentStatus.PUBLISHING, content.items[1]!!.status)
    }

    @Test fun failedPostWhoseContainerCheckIsRefusedGetsAFreshAttempt() = runTest {
        approved()
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.FAILED, containerId = "c9"))
        content.save(content.items[1]!!.copy(status = ContentStatus.FAILED))
        reapprove()
        api.stateError = InstagramException("gone", delivered = true, code = 100)
        assertTrue(publisher.begin(1).id != post.id)
    }

    @Test fun beginOnAnAlreadyPublishedPostCorrectsTheItem() = runTest {
        approved()
        publisher.run(publisher.begin(1).id)
        content.save(content.items[1]!!.copy(status = ContentStatus.PUBLISHING))
        try { publisher.begin(1); fail("expected block") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("already")) }
        assertEquals(ContentStatus.PUBLISHED, content.items[1]!!.status)
    }

    @Test fun runOnAPublishedPostMakesSureTheItemSaysPublished() = runTest {
        approved()
        val post = publisher.run(publisher.begin(1).id)
        content.save(content.items[1]!!.copy(status = ContentStatus.PUBLISHING))
        publisher.run(post.id)
        assertEquals(ContentStatus.PUBLISHED, content.items[1]!!.status)
        assertEquals(1, api.publishCalls)
    }

    @Test fun blankCaptionNeverMatchesSomeoneElsesPost() = runTest {
        approved()
        content.save(content.items[1]!!.copy(caption = ""))
        val post = publisher.begin(1)
        repo.save(post.copy(state = PublishState.PUBLISH_UNCERTAIN, containerId = "c9"))
        api.states.clear()
        api.states += ContainerState(ContainerStatus.PUBLISHED)
        val done = publisher.run(post.id)
        assertEquals(PublishState.PUBLISHED, done.state)
        assertEquals(null, done.publishedMediaId)
    }

    @Test fun unfinishedProcessingLeavesThePostResumable() = runTest {
        approved()
        api.states.clear()
        api.states += ContainerState(ContainerStatus.IN_PROGRESS)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.CONTAINER_CREATED, post.state)
        assertEquals(ContentStatus.PUBLISHING, content.items[1]!!.status)
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        assertEquals(PublishState.PUBLISHED, publisher.run(post.id).state)
        assertEquals(1, api.containers)
    }

    @Test fun failedCleanUpTellsTheOwnerAPublicFileMayRemain() = runTest {
        approved()
        host.failDelete = true
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertTrue(activity.notices.containsKey("cleanup.${post.id}"))
    }

    @Test fun editingAnApprovedPostWithdrawsApproval() {
        val item = ContentItem(id = 1, title = "T", caption = "a", mediaId = 1, status = ContentStatus.APPROVED, approvedAt = 5, createdAt = 0, updatedAt = 0)
        val edited = ContentWorkflow.edited(item, item.copy(caption = "b"))
        assertEquals(ContentStatus.REVIEW, edited.status)
        assertEquals(null, edited.approvedAt)
        assertEquals(ContentStatus.APPROVED, ContentWorkflow.edited(item, item.copy(description = "note")).status)
    }

    @Test fun schedulingRequiresApproval() {
        val draft = ContentItem(id = 1, title = "T", scheduledAt = 100, status = ContentStatus.REVIEW, createdAt = 0, updatedAt = 0)
        try { ContentWorkflow.schedule(draft, 0); fail("expected block") } catch (e: ContentWorkflow.Blocked) { assertTrue(e.message!!.contains("Approve")) }
    }

    // ---- Carousels ----

    private fun image(id: Long) = MediaFile(id, MediaKind.IMAGE, "image/$id.jpg", "img$id", "image/jpeg", 1, "h$id", createdAt = 0)
    private fun video(id: Long) = MediaFile(id, MediaKind.VIDEO, "video/$id.mp4", "clip$id", "video/mp4", 1, "h$id", createdAt = 0)

    private suspend fun approvedCarousel(children: List<MediaFile> = listOf(image(11), image(12), image(13))): ContentItem {
        credentials.put(CredentialStore.INSTAGRAM_TOKEN, "t")
        val item = ContentItem(
            id = 1, title = "T", caption = "Hello", type = ContentType.CAROUSEL, status = ContentStatus.APPROVED, approvedAt = 5,
            createdAt = 0, updatedAt = 0,
        )
        content.save(item)
        content.carousel[1] = children
        children.forEach { mediaRepo.others[it.id] = it }
        return item
    }

    @Test fun carouselFlowRunsInTheDocumentedOrderAndPersistsUncertainBeforePublishing() = runTest {
        approvedCarousel()
        var stateWhenPublishing: PublishState? = null
        api.onPublish = { stateWhenPublishing = repo.posts.values.single().state }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        val begun = publisher.begin(1)
        val key = begun.idempotencyKey
        val post = publisher.run(begun.id)
        assertEquals(
            listOf(
                "upload:nexio/$key/0", "upload:nexio/$key/1", "upload:nexio/$key/2",
                "item:https://host/nexio/$key/0", "item:https://host/nexio/$key/1", "item:https://host/nexio/$key/2",
                "state:k1", "state:k2", "state:k3",
                "parent:k1,k2,k3",
                "state:p1",
            ),
            events,
        )
        assertEquals("the uncertain state is written before the publish call", PublishState.PUBLISH_UNCERTAIN, stateWhenPublishing)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertEquals("media-1", post.publishedMediaId)
        assertEquals("p1", post.containerId)
        assertEquals(1, api.publishCalls)
        assertEquals(1, api.parents)
        assertEquals("Hello", api.parentCaption)
        assertEquals(ContentStatus.PUBLISHED, content.items[1]!!.status)
        assertEquals(setOf("nexio/$key/0", "nexio/$key/1", "nexio/$key/2"), host.deleted.toSet())
        assertTrue(CarouselProgress.decode(post.carouselProgress).all { it.finished && it.containerId != null })
    }

    @Test fun carouselChildrenKeepTheOwnersOrder() = runTest {
        approvedCarousel(listOf(image(13), image(11), image(12)))
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(listOf(13L, 11L, 12L), CarouselProgress.decode(post.carouselProgress).sortedBy { it.position }.map { it.mediaId })
    }

    @Test fun carouselNeedsTwoToTenImagesAndOnlyImages() = runTest {
        approvedCarousel(listOf(image(11)))
        try { publisher.begin(1); fail("one image is not a carousel") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!, e.message!!.contains("between 2 and 10")) }
        content.carousel[1] = (1L..11L).map(::image)
        try { publisher.begin(1); fail("eleven images are too many") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("between 2 and 10")) }
        content.carousel[1] = listOf(image(11), video(12))
        try { publisher.begin(1); fail("video children are not offered") } catch (e: InstagramPublisher.Blocked) { assertTrue(e.message!!.contains("images only")) }
        assertEquals(0, repo.posts.size)
        assertEquals(0, api.items)
        content.carousel[1] = listOf(image(11), image(12))
        assertEquals(PublishState.PENDING, publisher.begin(1).state)
    }

    @Test fun carouselRuleLivesInTheWorkflow() {
        fun problem(children: List<MediaFile>) = ContentWorkflow.carouselProblem(children)
        assertNotNull(problem(emptyList()))
        assertNotNull(problem((1L..1L).map(::image)))
        assertEquals(null, problem((1L..2L).map(::image)))
        assertEquals(null, problem((1L..10L).map(::image)))
        assertNotNull(problem((1L..11L).map(::image)))
        assertNotNull(problem(listOf(image(1), video(2))))
        val item = ContentItem(id = 1, title = "T", caption = "c", type = ContentType.CAROUSEL, status = ContentStatus.REVIEW, approvedAt = 5, createdAt = 0, updatedAt = 0)
        assertNotNull("no children yet", ContentWorkflow.problemForPublishing(item))
        assertEquals(null, ContentWorkflow.problemForPublishing(item, listOf(image(1), image(2))))
        try { ContentWorkflow.approve(item, 9, listOf(image(1))); fail("approval needs 2..10 images") } catch (e: ContentWorkflow.Blocked) { assertTrue(e.message!!.contains("between 2 and 10")) }
        assertEquals(ContentStatus.APPROVED, ContentWorkflow.approve(item, 9, listOf(image(1), image(2))).status)
        // Another type is not affected by the carousel rule.
        assertEquals(ContentStatus.APPROVED, ContentWorkflow.approve(item.copy(type = ContentType.IMAGE_POST, mediaId = 1), 9).status)
    }

    @Test fun changingTheCarouselImagesOrOrderWithdrawsApproval() {
        val item = ContentItem(id = 1, title = "T", caption = "c", type = ContentType.CAROUSEL, status = ContentStatus.APPROVED, approvedAt = 5, createdAt = 0, updatedAt = 0)
        val withdrawn = ContentWorkflow.edited(item, item, carouselChanged = true)
        assertEquals(ContentStatus.REVIEW, withdrawn.status)
        assertEquals(null, withdrawn.approvedAt)
        assertEquals(ContentStatus.APPROVED, ContentWorkflow.edited(item, item, carouselChanged = false).status)
    }

    @Test fun oneFailingChildFailsThePostWithoutAParentAndDeletesEveryHostedFile() = runTest {
        approvedCarousel()
        api.childStates["k2"] = ContainerState(ContainerStatus.ERROR, "Image is too small")
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.FAILED, post.state)
        assertEquals("Image is too small", post.lastError)
        assertEquals(ContentStatus.FAILED, content.items[1]!!.status)
        assertEquals(0, api.parents)
        assertEquals(0, api.publishCalls)
        assertEquals(3, host.deleted.size)
        assertEquals(null, post.containerId)
    }

    @Test fun anExpiredChildFailsTheWholeCarousel() = runTest {
        approvedCarousel()
        api.childStates["k3"] = ContainerState(ContainerStatus.EXPIRED)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.FAILED, post.state)
        assertTrue(post.lastError!!.contains("image 3"))
        assertEquals(0, api.parents)
        assertEquals(3, host.deleted.size)
    }

    @Test fun anUploadFailureMidwayFailsThePostCreatesNothingAndCleansWhatWasUploaded() = runTest {
        approvedCarousel()
        host.failUploadOf = "/1"
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.FAILED, post.state)
        assertTrue(post.lastError!!.contains("host down"))
        assertEquals(0, api.items)
        assertEquals(0, api.parents)
        assertEquals(setOf("nexio/${post.idempotencyKey}/0", "nexio/${post.idempotencyKey}/1", "nexio/${post.idempotencyKey}/2"), host.deleted.toSet())
    }

    @Test fun carouselResumesWithoutRecreatingFinishedChildren() = runTest {
        approvedCarousel()
        api.childStates["k2"] = ContainerState(ContainerStatus.IN_PROGRESS)
        val stalled = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.MEDIA_UPLOADED, stalled.state)
        assertEquals(3, api.items)
        assertEquals(0, api.parents)
        assertEquals(listOf(true, false, true), CarouselProgress.decode(repo.posts[stalled.id]!!.carouselProgress).map { it.finished })
        events.clear()
        api.childStates.clear()
        val done = publisher.run(stalled.id)
        assertEquals(PublishState.PUBLISHED, done.state)
        assertEquals("no upload and no child container is repeated", 3, api.items)
        assertEquals(listOf("state:k2", "parent:k1,k2,k3", "state:p1"), events)
    }

    @Test fun anUncertainCarouselIsNeverPublishedAgainWhileTheAnswerIsUnclear() = runTest {
        approvedCarousel()
        api.publishResult = { throw noResponse }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        api.states += ContainerState(ContainerStatus.UNKNOWN)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISH_UNCERTAIN, post.state)
        assertEquals(1, api.publishCalls)
        assertEquals(1, api.parents)
        // Starting again resumes the same row; running it again only asks Instagram.
        val again = publisher.begin(1)
        assertEquals(post.id, again.id)
        assertEquals(PublishState.PUBLISH_UNCERTAIN, publisher.run(again.id).state)
        assertEquals(1, api.publishCalls)
        assertEquals(1, api.parents)
        assertEquals(3, api.items)
        assertEquals(1, repo.posts.size)
    }

    @Test fun anUncertainCarouselThatWentThroughIsConfirmedNotRepublished() = runTest {
        approvedCarousel()
        api.publishResult = { throw noResponse }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        api.states += ContainerState(ContainerStatus.PUBLISHED)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertEquals(1, api.publishCalls)
        assertEquals(1, api.parents)
        assertEquals(3, host.deleted.size)
    }

    @Test fun anUncertainCarouselMayBePublishedOnlyWhenInstagramSaysFinished() = runTest {
        approvedCarousel()
        var attempts = 0
        api.publishResult = { if (attempts++ == 0) throw noResponse else "media-2" }
        api.states.clear()
        api.states += ContainerState(ContainerStatus.FINISHED)
        val post = publisher.run(publisher.begin(1).id)
        assertEquals(PublishState.PUBLISHED, post.state)
        assertEquals(2, api.publishCalls)
        assertEquals("the same parent is reused", 1, api.parents)
    }
}
