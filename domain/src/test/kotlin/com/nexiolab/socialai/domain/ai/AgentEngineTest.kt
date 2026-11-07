package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.agents.AgentEngine
import com.nexiolab.socialai.domain.agents.LenientJson
import com.nexiolab.socialai.domain.content.DuplicateDetector
import com.nexiolab.socialai.domain.model.*
import com.nexiolab.socialai.domain.repository.BrandRepository
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentEngineTest {
    private val providers = FakeProviders()
    private val agents = FakeAgents()
    private val expenses = FakeExpenses()
    private val activity = FakeActivity()
    private val credentials = FakeCredentials()
    private val answers = ArrayDeque<String>()
    private val requests = ArrayList<CompletionRequest>()

    private val client = object : AiClient {
        override suspend fun listModels() = emptyList<DiscoveredModel>()
        override suspend fun complete(request: CompletionRequest): Completion {
            requests += request
            return Completion(answers.removeFirst(), Usage(10, 10, true))
        }
    }
    private val brand = object : BrandRepository {
        var profile = BrandProfile(businessName = "Kofe Uyi", brandVoice = "warm")
        override fun observe() = flowOf(profile)
        override suspend fun get() = profile
        override suspend fun save(profile: BrandProfile) { this.profile = profile }
    }
    private val settings = object : SettingsRepository {
        override fun observe(key: String) = flowOf<String?>(null)
        override suspend fun get(key: String): String? = null
        override suspend fun set(key: String, value: String) {}
    }
    private val gateway = AiGateway(providers, agents, credentials, { _, _ -> client }, expenses, activity, retryDelayMillis = 0)
    private val engine = AgentEngine(gateway, agents, brand, settings, activity)

    private suspend fun setup() {
        providers.saveProvider(AIProvider(1, ProviderType.OPENAI, "P", "https://x"))
        providers.saveModel(AIModel(10, 1, "m"))
        credentials.put(CredentialStore.provider(1), "k")
    }

    private val pass = """{"verdict":"pass","notes":""}"""
    private fun reject(notes: String) = """{"verdict":"reject","notes":"$notes"}"""

    @Test fun passingReviewCompletesTask() = runTest {
        setup()
        answers += listOf("draft", pass)
        val task = engine.runSpecialist(AgentRole.CONTENT, "t", "brief")
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(1, task.attempts)
        assertEquals("draft", task.output)
    }

    @Test fun rejectedWorkIsCorrectedWithReviewerNotes() = runTest {
        setup()
        answers += listOf("bad", reject("spelling"), "good", pass)
        val task = engine.runSpecialist(AgentRole.CONTENT, "t", "brief")
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(2, task.attempts)
        assertEquals("good", task.output)
        assertTrue(requests[2].messages.single().text.contains("spelling"))
    }

    @Test fun workRejectedEveryTimeWaitsForTheOwner() = runTest {
        setup()
        answers += listOf("a", reject("x"), "b", reject("x"), "c", reject("still wrong"))
        val task = engine.runSpecialist(AgentRole.CONTENT, "t", "brief")
        assertEquals(TaskStatus.WAITING_APPROVAL, task.status)
        assertEquals("still wrong", task.reviewNotes)
    }

    @Test fun unreadableVerdictIsNeverAPass() {
        assertFalse(AgentEngine.parseReview("looks great to me").passed)
        assertFalse(AgentEngine.parseReview("""{"verdict":"maybe"}""").passed)
        assertTrue(AgentEngine.parseReview("Sure:\n```json\n$pass\n```").passed)
    }

    @Test fun brandFactsReachTheSystemPrompt() = runTest {
        setup()
        answers += listOf("draft", pass)
        engine.runSpecialist(AgentRole.CONTENT, "t", "brief")
        assertTrue(requests[0].system.contains("Kofe Uyi"))
        assertTrue(requests[0].system.contains("<business_facts>"))
    }

    @Test fun instructionDelegatesReviewsAndSummarises() = runTest {
        setup()
        answers += listOf(
            """{"summary":"s","steps":[{"agent":"MARKETING","title":"Analyse","brief":"b1"},{"agent":"CONTENT","title":"Write","brief":"b2"}]}""",
            "analysis", pass, "post", pass, "all done",
        )
        val task = engine.runInstruction("make a post")
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals("all done", task.output)
        assertTrue("later steps see earlier results", requests[3].messages.single().text.contains("analysis"))
        assertEquals(setOf(AgentRole.MARKETING, AgentRole.CONTENT), agents.tasks.values.filter { it.parentId == task.id && it.role != AgentRole.QUALITY_CONTROL }.map { it.role }.toSet())
    }

    @Test fun planCannotBypassQualityControlOrRecurseIntoMain() = runTest {
        setup()
        answers += listOf(
            """{"steps":[{"agent":"MAIN","title":"x","brief":"y"},{"agent":"QUALITY_CONTROL","title":"x","brief":"y"},{"agent":"NOPE","title":"x","brief":"y"}]}""",
            "nothing to do",
        )
        val task = engine.runInstruction("hi")
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(1, agents.tasks.size)
    }

    @Test fun providerFailureFailsTheTaskWithItsReason() = runTest {
        answers += "unused"
        try {
            engine.runInstruction("hi")
            fail("expected failure")
        } catch (e: AiException) {
            assertEquals(AiError.NOT_CONFIGURED, e.error)
        }
        assertEquals(TaskStatus.FAILED, agents.tasks.values.single().status)
    }

    @Test fun disabledSpecialistIsRefusedNotFaked() = runTest {
        setup()
        agents.saveAgent(Agent(AgentRole.VIDEO, enabled = false))
        try {
            engine.runSpecialist(AgentRole.VIDEO, "t", "b")
            fail("expected refusal")
        } catch (e: AiException) {
            assertEquals(AiError.NOT_CONFIGURED, e.error)
        }
    }

    @Test fun aTaskThatAlreadyRanIsNotRunAgainWhenWorkRestarts() = runTest {
        setup()
        answers += listOf("""{"steps":[]}""", "done")
        val task = engine.runInstruction("hi")
        val before = requests.size
        engine.resume(task.id)
        assertEquals(before, requests.size)
    }

    @Test fun lenientJsonFindsValueInsideProse() {
        assertNotNull(LenientJson.parse("Here it is:\n```json\n{\"a\":\"}\"}\n```\nThanks"))
        assertNull(LenientJson.parse("no json"))
        assertNull(LenientJson.parse("{\"a\":"))
    }

    @Test fun duplicateDetectorCatchesRewordingOnly() {
        val base = "Our fresh morning coffee beans roasted daily in the city centre"
        assertTrue(DuplicateDetector.similarity(base, "Our fresh morning coffee beans roasted daily in city centre today") > 0.75)
        assertNull(DuplicateDetector.closestMatch(base, listOf("Join the weekend latte art workshop with friends")))
    }
}
