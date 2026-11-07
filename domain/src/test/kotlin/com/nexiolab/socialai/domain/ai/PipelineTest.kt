package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.agents.AgentEngine
import com.nexiolab.socialai.domain.content.ContentPlanner
import com.nexiolab.socialai.domain.model.*
import com.nexiolab.socialai.domain.repository.BrandRepository
import com.nexiolab.socialai.domain.repository.ContentRepository
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.repository.MediaRepository
import com.nexiolab.socialai.domain.repository.SettingsRepository
import com.nexiolab.socialai.domain.video.RenderSpec
import com.nexiolab.socialai.domain.video.VideoPipeline
import com.nexiolab.socialai.domain.video.VideoRenderer
import com.nexiolab.socialai.domain.video.VideoRepository
import com.nexiolab.socialai.domain.video.outputSize
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class PipelineTest {
    private val pass = """{"verdict":"pass","notes":""}"""
    private val providers = FakeProviders()
    private val agents = FakeAgents()
    private val expenses = FakeExpenses()
    private val activity = FakeActivity()
    private val credentials = FakeCredentials()
    private val answers = ArrayDeque<String>()
    private val prompts = ArrayList<String>()
    private val clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC)

    private inner class Client : AiClient {
        var videoStarted = 0
        var pollsLeft = 1
        var videoFails = false
        override suspend fun listModels() = emptyList<DiscoveredModel>()
        override suspend fun complete(request: CompletionRequest): Completion {
            prompts += request.messages.single().text
            return Completion(answers.removeFirst(), Usage(10, 10, true))
        }
        override suspend fun startVideo(request: VideoRequest): String { videoStarted++; return "job" }
        override suspend fun pollVideo(jobId: String): VideoJobState = when {
            videoFails -> VideoJobState.Failed("provider said no")
            pollsLeft-- > 0 -> VideoJobState.Running
            else -> VideoJobState.Finished(byteArrayOf(1), "video/mp4")
        }
        override suspend fun speech(request: SpeechRequest) = GeneratedSpeech(byteArrayOf(2), "audio/mpeg")
    }

    private val client = Client()
    private val brand = object : BrandRepository {
        override fun observe() = flowOf(BrandProfile(businessName = "Kofe Uyi"))
        override suspend fun get() = BrandProfile(businessName = "Kofe Uyi")
        override suspend fun save(profile: BrandProfile) {}
    }
    private val settings = object : SettingsRepository {
        override fun observe(key: String) = flowOf<String?>(null)
        override suspend fun get(key: String): String? = null
        override suspend fun set(key: String, value: String) {}
    }
    private val gateway = AiGateway(providers, agents, credentials, { _, _ -> client }, expenses, activity, retryDelayMillis = 0)
    private val engine = AgentEngine(gateway, agents, brand, settings, activity)

    private class Content : ContentRepository {
        val plans = ArrayList<Pair<ContentPlan, List<ContentItem>>>()
        var captions = emptyList<String>()
        override fun observePlans() = emptyFlow<List<ContentPlan>>()
        override fun observeBetween(fromMillis: Long, toMillis: Long) = emptyFlow<List<ContentItem>>()
        override fun observeAll() = emptyFlow<List<ContentItem>>()
        override fun observeStatusCounts() = emptyFlow<Map<ContentStatus, Int>>()
        override fun observeUpcoming(now: Long, limit: Int) = emptyFlow<List<ContentItem>>()
        override suspend fun item(id: Long) = null
        override suspend fun planFor(yearMonth: String) = null
        override suspend fun savePlan(plan: ContentPlan) = 1L
        override suspend fun savePlanWithItems(plan: ContentPlan, items: List<ContentItem>): Long { plans += plan to items; return 1 }
        override suspend fun save(item: ContentItem) = 1L
        override suspend fun delete(item: ContentItem) {}
        override suspend fun carouselMedia(itemId: Long) = emptyList<MediaFile>()
        override suspend fun setCarouselMedia(itemId: Long, mediaIds: List<Long>) {}
        override suspend fun dueForPublishing(now: Long) = emptyList<ContentItem>()
        override suspend fun recentCaptions(limit: Int) = captions
    }

    private class Videos : VideoRepository {
        val rows = HashMap<Long, VideoProject>()
        var photoList = emptyList<MediaFile>()
        override fun observeAll() = emptyFlow<List<VideoProject>>()
        override fun observe(id: Long) = emptyFlow<VideoProject?>()
        override fun observePhotos(id: Long) = emptyFlow<List<MediaFile>>()
        override suspend fun byId(id: Long) = rows[id]
        override suspend fun save(project: VideoProject): Long { rows[project.id] = project; return project.id }
        override suspend fun delete(project: VideoProject) {}
        override suspend fun photos(id: Long) = photoList
        override suspend fun setPhotos(id: Long, mediaIds: List<Long>) {}
    }

    private class Media : MediaRepository {
        val stored = ArrayList<MediaFile>()
        private fun row(name: String, kind: MediaKind, source: MediaSource) =
            MediaFile((stored.size + 1).toLong(), kind, "p/$name", name, "x/y", 1, "s$name", source = source, createdAt = 0).also { stored += it }
        override fun observeAll() = flowOf(stored.toList())
        override suspend fun byId(id: Long) = stored.firstOrNull { it.id == id }
        override suspend fun import(uri: String, source: MediaSource) = row(uri, MediaKind.IMAGE, source)
        override suspend fun store(bytes: ByteArray, kind: MediaKind, mimeType: String, name: String, source: MediaSource) = row(name, kind, source)
        override suspend fun registerFile(path: String, kind: MediaKind, mimeType: String, name: String, source: MediaSource) = row(name, kind, source)
        override fun absolutePath(media: MediaFile) = "/m/${media.relativePath}"
        override suspend fun delete(media: MediaFile) {}
    }

    private class Renderer : VideoRenderer {
        var last: RenderSpec? = null
        var failure: Exception? = null
        override suspend fun render(spec: RenderSpec, onProgress: (Int) -> Unit): String {
            failure?.let { throw it }
            last = spec
            return "/cache/out.mp4"
        }
    }

    private val content = Content()
    private val videos = Videos()
    private val media = Media()
    private val renderer = Renderer()
    private val planner = ContentPlanner(engine, content, activity, clock)
    private val pipeline = VideoPipeline(engine, gateway, videos, media, renderer, settings, activity, { ImageInput("image/jpeg", byteArrayOf(1)) }, pollMillis = 0)

    private suspend fun setup() {
        providers.saveProvider(AIProvider(1, ProviderType.OPENAI, "P", "https://x"))
        providers.saveModel(AIModel(10, 1, "m", supportsVision = true, supportsVideo = true, supportsSpeech = true))
        credentials.put(CredentialStore.provider(1), "k")
    }

    private fun calendar(day: Int = 3, caption: String = "Fresh beans roasted this morning") =
        """[{"title":"Post","description":"d","caption":"$caption","hashtags":"#kofe","type":"REEL","day":$day,"time":"09:30"}]"""

    private val november = YearMonth.of(2026, 11)

    @Test fun monthPlanWaitsForTheOwnerAndIsNeverScheduledByAgents() = runTest {
        setup()
        answers += listOf("strategy", pass, calendar(), pass)
        val result = planner.generateMonth(november)
        val (plan, items) = content.plans.single()
        assertEquals("2026-11", plan.yearMonth)
        assertEquals("strategy", plan.strategy)
        assertEquals(1, result.items)
        val item = items.single()
        assertEquals(ContentStatus.REVIEW, item.status)
        assertEquals(null, item.approvedAt)
        assertEquals(ContentType.REEL, item.type)
        assertEquals(Instant.parse("2026-11-03T09:30:00Z").toEpochMilli(), item.scheduledAt)
    }

    private fun carouselCalendar() =
        """[{"title":"Post","description":"d","caption":"A look at the menu","hashtags":"#kofe","type":"CAROUSEL","day":3,"time":"09:30"}]"""

    @Test fun plannerProposesACarouselOnlyWhenTheLibraryHasTwoImages() = runTest {
        setup()
        answers += listOf("strategy", pass, carouselCalendar(), pass)
        ContentPlanner(engine, content, activity, clock, libraryImageCount = { 2 }).generateMonth(november)
        assertEquals(ContentType.CAROUSEL, content.plans.last().second.single().type)
        assertTrue(prompts.any { it.contains("IMAGE_POST|CAROUSEL|REEL|STORY") })
        assertEquals("a carousel is created without children; the owner picks the images", null, content.plans.last().second.single().mediaId)
    }

    @Test fun plannerKeepsASingleImagePostWithFewerThanTwoLibraryImages() = runTest {
        setup()
        answers += listOf("strategy", pass, carouselCalendar(), pass)
        ContentPlanner(engine, content, activity, clock, libraryImageCount = { 1 }).generateMonth(november)
        assertEquals(ContentType.IMAGE_POST, content.plans.last().second.single().type)
        assertTrue(prompts.none { it.contains("IMAGE_POST|CAROUSEL") })
    }

    @Test fun calendarWithAnImpossibleDayGoesBackForCorrectionWithoutQualityControl() = runTest {
        setup()
        answers += listOf("strategy", pass, calendar(day = 31), calendar(day = 5), pass)
        planner.generateMonth(november)
        assertTrue(prompts[3].contains("outside the month"))
        assertEquals(1, content.plans.single().second.size)
    }

    @Test fun unreadableCalendarIsNeverSaved() = runTest {
        setup()
        answers += listOf("strategy", pass, "sorry", "still no", "nope")
        val result = runCatching { planner.generateMonth(november) }
        assertTrue(result.isFailure)
        assertTrue(content.plans.isEmpty())
    }

    @Test fun repeatedCaptionIsFlaggedForTheOwner() = runTest {
        setup()
        content.captions = listOf("Fresh beans roasted this morning")
        answers += listOf("strategy", pass, calendar(), pass)
        val result = planner.generateMonth(november)
        assertEquals(1, result.flaggedDuplicates)
        assertTrue(content.plans.single().second.single().qualityNotes.isNotBlank())
    }

    private fun project(mode: VideoMode, status: VideoStatus = VideoStatus.DRAFT) =
        VideoProject(1, "Ad", mode, objective = "sell", status = status, createdAt = 0, updatedAt = 0)

    @Test fun outputSizeIsEvenAndFollowsTheFormat() {
        assertEquals(720 to 1280, project(VideoMode.PHOTO_BASED).outputSize())
        assertEquals(1280 to 720, project(VideoMode.PHOTO_BASED).copy(aspectWidth = 16, aspectHeight = 9).outputSize())
        assertEquals(1080 to 1080, project(VideoMode.PHOTO_BASED).copy(aspectWidth = 1, aspectHeight = 1, shortSidePx = 1080).outputSize())
    }

    @Test fun nothingPaidRunsBeforeTheConceptIsApproved() = runTest {
        setup()
        videos.save(project(VideoMode.FULL_AI, VideoStatus.CONCEPT_READY))
        val result = runCatching { pipeline.generate(1) }
        assertTrue(result.exceptionOrNull() is AiException)
        assertEquals(0, client.videoStarted)
        assertEquals(VideoStatus.CONCEPT_READY, videos.rows[1]!!.status)
    }

    @Test fun fullAiConceptRunsEverySpecialistThenStopsForApproval() = runTest {
        setup()
        videos.save(project(VideoMode.FULL_AI))
        answers += listOf("script", pass, "check", pass, "visual", pass, "scenes", pass)
        pipeline.prepareConcept(1)
        val done = videos.rows[1]!!
        assertEquals(VideoStatus.CONCEPT_READY, done.status)
        assertEquals("script", done.script)
        assertEquals("scenes", done.storyboard)
        assertEquals(0, client.videoStarted)
    }

    @Test fun fullAiVideoIsStoredAndItsCostIsMarkedEstimated() = runTest {
        setup()
        providers.saveModel(providers.models[10]!!.copy(pricePerVideoSecond = 0.5))
        videos.save(project(VideoMode.FULL_AI, VideoStatus.APPROVED).copy(storyboard = "scenes"))
        answers += listOf("looks fine", pass)
        pipeline.generate(1)
        assertEquals(VideoStatus.READY, videos.rows[1]!!.status)
        assertEquals(MediaSource.AI_GENERATED, media.stored.single().source)
        val row = expenses.rows.single { it.requestType == RequestType.VIDEO }
        assertEquals(CostConfidence.ESTIMATED, row.confidence)
        assertEquals(7.5, row.estimatedCost!!, 1e-9)
    }

    @Test fun providerVideoFailureKeepsTheConcept() = runTest {
        setup()
        client.videoFails = true
        videos.save(project(VideoMode.FULL_AI, VideoStatus.APPROVED).copy(script = "keep me"))
        assertTrue(runCatching { pipeline.generate(1) }.isFailure)
        val failed = videos.rows[1]!!
        assertEquals(VideoStatus.FAILED, failed.status)
        assertEquals("keep me", failed.script)
        assertEquals("provider said no", failed.errorMessage)
    }

    @Test fun photoVideoRendersLocallyWithVoiceover() = runTest {
        setup()
        val photo = media.import("a.jpg", MediaSource.UPLOADED)
        videos.photoList = listOf(photo)
        videos.save(project(VideoMode.PHOTO_BASED, VideoStatus.APPROVED).copy(voiceoverText = "Salom", captionText = "Yangi"))
        answers += listOf("fine", pass)
        pipeline.generate(1)
        val spec = renderer.last!!
        assertEquals(listOf("/m/p/a.jpg"), spec.photos)
        assertEquals("Yangi", spec.caption)
        assertTrue(spec.voiceoverPath!!.endsWith("voiceover.mp3"))
        assertEquals(VideoStatus.READY, videos.rows[1]!!.status)
        assertEquals(MediaSource.RENDERED, media.stored.last().source)
    }

    @Test fun photoConceptNeedsPhotos() = runTest {
        setup()
        videos.save(project(VideoMode.PHOTO_BASED))
        assertTrue(runCatching { pipeline.prepareConcept(1) }.isFailure)
        assertEquals(VideoStatus.FAILED, videos.rows[1]!!.status)
        assertFalse(prompts.isNotEmpty())
    }

    @Test fun renderFailureSurfacesAsFailedProject() = runTest {
        setup()
        renderer.failure = AiException(AiError.SERVER, "encoder busy")
        videos.photoList = listOf(media.import("a.jpg", MediaSource.UPLOADED))
        videos.save(project(VideoMode.PHOTO_BASED, VideoStatus.APPROVED))
        try {
            pipeline.generate(1)
            fail("expected failure")
        } catch (e: AiException) {
            assertEquals("encoder busy", e.message)
        }
        assertEquals(VideoStatus.FAILED, videos.rows[1]!!.status)
    }
}
