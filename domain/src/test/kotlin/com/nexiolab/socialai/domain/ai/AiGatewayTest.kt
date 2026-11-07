package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.model.*
import com.nexiolab.socialai.domain.repository.CredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AiGatewayTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC)
    private val providers = FakeProviders()
    private val agents = FakeAgents()
    private val expenses = FakeExpenses()
    private val activity = FakeActivity()
    private val credentials = FakeCredentials()
    private val clientsById = HashMap<Long, ScriptedClient>()

    private class ScriptedClient(val script: ArrayDeque<() -> Completion>) : AiClient {
        var calls = 0
        var videoState: VideoJobState = VideoJobState.Running
        override suspend fun startVideo(request: VideoRequest) = "job-1"
        override suspend fun pollVideo(jobId: String) = videoState
        override suspend fun listModels() = listOf(DiscoveredModel("m"))
        override suspend fun complete(request: CompletionRequest): Completion { calls++; return script.removeFirst()() }
    }

    private val gateway = AiGateway(
        providers, agents, credentials,
        { provider, _ -> clientsById.getValue(provider.id) },
        expenses, activity, clock, retryDelayMillis = 0,
    )

    private fun ok(input: Long = 1_000_000, output: Long = 500_000, exact: Boolean = true) =
        { Completion("hello", Usage(input, output, exact)) }

    private fun fails(error: AiError): () -> Completion = { throw AiException(error, error.name) }

    private suspend fun provider(id: Long, vararg script: () -> Completion, priced: Boolean = true): AIModel {
        providers.saveProvider(AIProvider(id, ProviderType.OPENAI, "P$id", "https://x"))
        credentials.put(CredentialStore.provider(id), "key")
        clientsById[id] = ScriptedClient(ArrayDeque(script.toList()))
        val model = AIModel(id * 10, id, "model-$id", inputPricePerMTok = 2.0.takeIf { priced }, outputPricePerMTok = 10.0.takeIf { priced })
        providers.saveModel(model)
        return model
    }

    private suspend fun ask() = gateway.complete(AgentRole.CONTENT, "sys", listOf(ChatMessage.user("hi")))

    private fun spent(amount: Double) = ExpenseRecord(
        providerId = 1, providerName = "P1", modelId = "m", agentRole = null, requestType = RequestType.TEXT,
        actualCost = amount, confidence = CostConfidence.MEASURED, createdAt = clock.millis(),
    )

    @Test fun measuredUsageIsWrittenWithPriceSnapshot() = runTest {
        val model = provider(1, ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        assertEquals("hello", ask().text)
        val row = expenses.rows.single()
        assertEquals(7.0, row.actualCost!!, 1e-9)
        assertNull(row.estimatedCost)
        assertEquals(CostConfidence.MEASURED, row.confidence)
        assertEquals(2.0, row.inputPricePerMTok!!, 0.0)
    }

    @Test fun inferredUsageIsEstimatedNeverActual() = runTest {
        val model = provider(1, ok(exact = false))
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        ask()
        val row = expenses.rows.single()
        assertNull(row.actualCost)
        assertEquals(CostConfidence.ESTIMATED, row.confidence)
    }

    @Test fun missingPriceIsUnpricedNotZero() = runTest {
        val model = provider(1, ok(), priced = false)
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        ask()
        val row = expenses.rows.single()
        assertNull(row.actualCost)
        assertNull(row.estimatedCost)
        assertEquals(CostConfidence.UNPRICED, row.confidence)
        assertEquals(1, ExpenseSummary.of(expenses.rows).unpricedRequests)
    }

    @Test fun transientErrorsRetryThenSucceedAndFailuresAreLogged() = runTest {
        val model = provider(1, fails(AiError.SERVER), fails(AiError.RATE_LIMITED), ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        assertEquals("hello", ask().text)
        assertEquals(3, clientsById[1]!!.calls)
        assertEquals(2, expenses.rows.count { !it.succeeded })
        assertEquals(1, expenses.rows.count { it.succeeded })
    }

    @Test fun nonRetryableErrorGoesStraightToFallbackModel() = runTest {
        val main = provider(1, fails(AiError.AUTH))
        val backup = provider(2, ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = main.id, fallbackModelId = backup.id))
        assertEquals("hello", ask().text)
        assertEquals(1, clientsById[1]!!.calls)
        assertEquals(1, clientsById[2]!!.calls)
    }

    @Test fun invalidRequestDoesNotBurnTheFallback() = runTest {
        val main = provider(1, fails(AiError.INVALID_REQUEST))
        val backup = provider(2, ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = main.id, fallbackModelId = backup.id))
        try { ask(); fail("expected failure") } catch (e: AiException) { assertEquals(AiError.INVALID_REQUEST, e.error) }
        assertEquals(0, clientsById[2]!!.calls)
    }

    @Test fun hardSpendingLimitStopsBeforeAnyRequest() = runTest {
        val model = provider(1, ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        expenses.budget = MonthlyBudget("default", budget = 10.0, maxSpending = 5.0)
        expenses.record(spent(5.0))
        try { ask(); fail("expected budget stop") } catch (e: AiException) { assertEquals(AiError.BUDGET_EXCEEDED, e.error) }
        assertEquals(0, clientsById[1]!!.calls)
    }

    @Test fun providerLimitStopsThatProvider() = runTest {
        val model = provider(1, ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        expenses.providerLimit = ProviderBudget(1, 1.0)
        expenses.record(spent(1.0))
        try { ask(); fail("expected budget stop") } catch (e: AiException) { assertEquals(AiError.BUDGET_EXCEEDED, e.error) }
    }

    @Test fun budgetWarningFiresOncePerMonth() = runTest {
        val model = provider(1, ok(), ok())
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        expenses.budget = MonthlyBudget("default", budget = 8.0, warningPercent = 80)
        ask()
        assertTrue("budget.warn.2026-11" in activity.notices)
        val before = activity.notices.size
        activity.notice("budget.warn.2026-11", NoticeSeverity.WARNING, "again", "")
        assertEquals(before, activity.notices.size)
    }

    @Test fun missingKeyReportsNotConfigured() = runTest {
        providers.saveProvider(AIProvider(1, ProviderType.OPENAI, "P1", "https://x"))
        providers.saveModel(AIModel(10, 1, "m"))
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = 10))
        try { ask(); fail("expected failure") } catch (e: AiException) { assertEquals(AiError.NOT_CONFIGURED, e.error) }
    }

    @Test fun disabledProviderIsSkipped() = runTest {
        val model = provider(1, ok())
        providers.saveProvider(providers.providers[1]!!.copy(enabled = false))
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        try { ask(); fail("expected failure") } catch (e: AiException) { assertEquals(AiError.NOT_CONFIGURED, e.error) }
        assertFalse(clientsById[1]!!.calls > 0)
    }

    @Test fun connectionTestStoresTheOutcome() = runTest {
        provider(1, ok())
        val result = gateway.testConnection(providers.providers[1]!!)
        assertTrue(result.isSuccess)
        assertEquals(true, providers.providers[1]!!.lastTestOk)
    }

    @Test fun unpricedModelIsRefusedWhenAMonthlyLimitIsSet() = runTest {
        val model = provider(1, ok(), priced = false)
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        expenses.budget = MonthlyBudget("default", budget = 10.0, maxSpending = 5.0)
        try { ask(); fail("expected budget stop") } catch (e: AiException) { assertEquals(AiError.BUDGET_EXCEEDED, e.error) }
        assertEquals(0, clientsById[1]!!.calls)
    }

    @Test fun unpricedModelIsRefusedWhenAProviderLimitIsSet() = runTest {
        val model = provider(1, ok(), priced = false)
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        expenses.providerLimit = ProviderBudget(1, 50.0)
        try { ask(); fail("expected budget stop") } catch (e: AiException) { assertEquals(AiError.BUDGET_EXCEEDED, e.error) }
        assertEquals(0, clientsById[1]!!.calls)
    }

    @Test fun unpricedModelStillWorksWithoutAnyLimit() = runTest {
        val model = provider(1, ok(), priced = false)
        agents.saveAgent(Agent(AgentRole.CONTENT, modelId = model.id))
        assertEquals("hello", ask().text)
    }

    private suspend fun videoModel(): AIModel {
        provider(1)
        val model = AIModel(10, 1, "video-1", supportsVideo = true, pricePerVideoSecond = 0.5)
        providers.saveModel(model)
        agents.saveAgent(Agent(AgentRole.VIDEO, modelId = model.id))
        return model
    }

    @Test fun startedVideoIsInTheLedgerAsAnEstimateBeforeDelivery() = runTest {
        videoModel()
        val job = gateway.startVideo(AgentRole.VIDEO, "p", 9, 16, 10, null)
        val row = expenses.rows.single()
        assertEquals(job.expenseId, row.id)
        assertEquals(CostConfidence.ESTIMATED, row.confidence)
        assertEquals(5.0, row.estimatedCost!!, 1e-9)
        assertEquals(1, row.videoCount)
        assertEquals(10.0, row.videoSeconds, 0.0)
        assertEquals(0.5, row.unitPrice!!, 0.0)
        clientsById[1]!!.videoState = VideoJobState.Finished(byteArrayOf(1), "video/mp4")
        gateway.pollVideo(AgentRole.VIDEO, job, 10)
        assertEquals(1, expenses.rows.size)
    }

    @Test fun failedVideoReplacesTheEstimateWithAFailedRow() = runTest {
        videoModel()
        val job = gateway.startVideo(AgentRole.VIDEO, "p", 9, 16, 10, null)
        clientsById[1]!!.videoState = VideoJobState.Failed("nope")
        gateway.pollVideo(AgentRole.VIDEO, job, 10)
        val row = expenses.rows.single()
        assertFalse(row.succeeded)
        assertNull(row.estimatedCost)
        assertEquals(AiError.SERVER.name, row.errorCode)
        assertEquals(0.0, expenses.spentSince(0), 0.0)
    }

    @Test fun runningVideoKeepsItsEstimate() = runTest {
        videoModel()
        val job = gateway.startVideo(AgentRole.VIDEO, "p", 9, 16, 10, null)
        gateway.pollVideo(AgentRole.VIDEO, job, 10)
        assertEquals(5.0, expenses.spentSince(0), 1e-9)
    }
}
