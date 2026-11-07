package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AIModel
import com.nexiolab.socialai.domain.model.AIProvider
import com.nexiolab.socialai.domain.model.Agent
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.CostConfidence
import com.nexiolab.socialai.domain.model.ExpenseRecord
import com.nexiolab.socialai.domain.model.NoticeSeverity
import com.nexiolab.socialai.domain.model.RequestType
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.AgentRepository
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.repository.ExpenseRepository
import com.nexiolab.socialai.domain.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

enum class Capability(private val message: Msg) {
    TEXT(Msg.CAPABILITY_TEXT),
    VISION(Msg.CAPABILITY_VISION),
    IMAGE(Msg.CAPABILITY_IMAGE),
    VIDEO(Msg.CAPABILITY_VIDEO),
    SPEECH(Msg.CAPABILITY_SPEECH),
    ;

    val label: String get() = message()

    fun of(model: AIModel) = when (this) {
        TEXT -> model.supportsText
        VISION -> model.supportsVision
        IMAGE -> model.supportsImage
        VIDEO -> model.supportsVideo
        SPEECH -> model.supportsSpeech
    }
}

data class GatewayCompletion(val text: String, val modelLabel: String, val truncated: Boolean)

/**
 * A started provider video job; poll with the same provider. [expenseId] is the ESTIMATED ledger row
 * written when the provider accepted the job (null if none was written).
 */
data class VideoJob(val jobId: String, val providerId: Long, val modelRowId: Long, val expenseId: Long? = null)

/**
 * The one door to every provider. Each call passes the budget stop, is written to the expense
 * ledger whether it succeeds or fails, and moves to the configured fallback when that makes sense.
 */
class AiGateway(
    private val providers: ProviderRepository,
    private val agents: AgentRepository,
    private val credentials: CredentialStore,
    private val clients: AiClientFactory,
    private val expenses: ExpenseRepository,
    private val activity: ActivityRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val retryDelayMillis: Long = 1_500,
) {
    private data class Route(val provider: AIProvider, val model: AIModel, val client: AiClient)

    suspend fun complete(
        role: AgentRole,
        system: String,
        messages: List<ChatMessage>,
        capability: Capability = Capability.TEXT,
    ): GatewayCompletion {
        val agent = agents.agent(role)
        return withRoutes(agent, capability) { route ->
            val request = CompletionRequest(route.model.modelId, system, messages, agent.temperature, agent.maxTokens)
            val result = route.client.complete(request)
            val usage = if (result.usage.inputTokens + result.usage.outputTokens > 0) {
                result.usage
            } else {
                val sent = system + messages.joinToString { it.text }
                Usage(CostCalculator.approximateTokens(sent), CostCalculator.approximateTokens(result.text), exact = false)
            }
            val cost = CostCalculator.text(route.model, usage)
            record(route, role, capability.requestType(), cost, inputTokens = usage.inputTokens, outputTokens = usage.outputTokens)
            GatewayCompletion(result.text, "${route.provider.name} / ${route.model.displayName}", result.truncated)
        }
    }

    suspend fun image(role: AgentRole, prompt: String, aspectWidth: Int, aspectHeight: Int): GeneratedImage {
        val agent = agents.agent(role)
        return withRoutes(agent, Capability.IMAGE) { route ->
            val image = route.client.generateImage(ImageRequest(route.model.modelId, prompt, aspectWidth, aspectHeight))
            record(route, role, RequestType.IMAGE, CostCalculator.units(route.model.pricePerImage, 1.0, exact = true), images = 1)
            image
        }
    }

    suspend fun startVideo(role: AgentRole, prompt: String, aspectWidth: Int, aspectHeight: Int, seconds: Int, reference: ImageInput?): VideoJob {
        val agent = agents.agent(role)
        return withRoutes(agent, Capability.VIDEO) { route ->
            val id = route.client.startVideo(VideoRequest(route.model.modelId, prompt, aspectWidth, aspectHeight, seconds, reference))
            // The provider may bill from here on, so the estimate goes into the ledger now, not at delivery.
            val cost = CostCalculator.units(route.model.pricePerVideoSecond, seconds.toDouble(), exact = false)
            val expenseId = record(route, role, RequestType.VIDEO, cost, videos = 1, videoSeconds = seconds.toDouble())
            VideoJob(id, route.provider.id, route.model.id, expenseId)
        }
    }

    /**
     * The estimate was written by [startVideo]. A finished video adds nothing; a job the provider
     * reports as failed swaps the estimate for a failed row. A timeout or a dead process keeps the
     * estimate, because the provider may have billed anyway.
     */
    suspend fun pollVideo(role: AgentRole, job: VideoJob, seconds: Int): VideoJobState {
        val model = providers.model(job.modelRowId) ?: throw AiException(AiError.NOT_CONFIGURED, Msg.VIDEO_MODEL_REMOVED())
        val provider = providers.provider(job.providerId) ?: throw AiException(AiError.NOT_CONFIGURED, Msg.VIDEO_PROVIDER_REMOVED())
        val route = Route(provider, model, clientFor(provider))
        val state = route.client.pollVideo(job.jobId)
        when (state) {
            is VideoJobState.Finished -> if (job.expenseId == null) {
                val cost = CostCalculator.units(model.pricePerVideoSecond, seconds.toDouble(), exact = false)
                record(route, role, RequestType.VIDEO, cost, videos = 1, videoSeconds = seconds.toDouble())
            }
            is VideoJobState.Failed -> {
                job.expenseId?.let { expenses.delete(it) }
                recordFailure(route, role, Capability.VIDEO, AiError.SERVER.name)
            }
            VideoJobState.Running -> Unit
        }
        return state
    }

    suspend fun speech(role: AgentRole, voice: String, text: String): GeneratedSpeech {
        val agent = agents.agent(role)
        return withRoutes(agent, Capability.SPEECH) { route ->
            val speech = route.client.speech(SpeechRequest(route.model.modelId, voice, text))
            val cost = CostCalculator.units(route.model.pricePerMChar, text.length / 1_000_000.0, exact = true)
            record(route, role, RequestType.SPEECH, cost, characters = text.length.toLong())
            speech
        }
    }

    /** Checks the key by listing models, which costs nothing, and stores the outcome on the provider. */
    suspend fun testConnection(provider: AIProvider): Result<List<DiscoveredModel>> {
        val now = clock.millis()
        return try {
            val models = clientFor(provider).listModels()
            providers.saveProvider(provider.copy(lastTestAt = now, lastTestOk = true, lastError = null))
            Result.success(models)
        } catch (e: AiException) {
            providers.saveProvider(provider.copy(lastTestAt = now, lastTestOk = false, lastError = e.message))
            Result.failure(e)
        }
    }

    private suspend fun clientFor(provider: AIProvider): AiClient {
        val key = credentials.get(CredentialStore.provider(provider.id))
            ?: throw AiException(AiError.NOT_CONFIGURED, Msg.PROVIDER_NO_API_KEY(provider.name))
        return clients.create(provider, key)
    }

    /** Agent's model, then its fallback, then other enabled providers' capable models in fallback order. */
    private suspend fun candidates(agent: Agent, capability: Capability): List<Pair<AIProvider, AIModel>> {
        val result = LinkedHashMap<Long, Pair<AIProvider, AIModel>>()
        suspend fun add(modelRowId: Long?) {
            val model = modelRowId?.let { providers.model(it) } ?: return
            val provider = providers.provider(model.providerId) ?: return
            if (provider.enabled && capability.of(model)) result[model.id] = provider to model
        }
        add(agent.modelId)
        add(agent.fallbackModelId)
        // Only models the user selected are eligible; an arbitrary model must never run on its own.
        for (provider in providers.providers().filter { it.enabled }) {
            providers.modelsOf(provider.id).firstOrNull { it.selected && capability.of(it) }
                ?.let { result.putIfAbsent(it.id, provider to it) }
        }
        return result.values.toList()
    }

    private suspend fun <T> withRoutes(agent: Agent, capability: Capability, call: suspend (Route) -> T): T {
        val options = candidates(agent, capability)
        if (options.isEmpty()) {
            throw AiException(AiError.NOT_CONFIGURED, Msg.NO_MODEL_FOR_CAPABILITY(capability.label))
        }
        var last: AiException? = null
        for ((provider, model) in options) {
            checkBudget(provider, model, capability)
            val route = try {
                Route(provider, model, clientFor(provider))
            } catch (e: AiException) {
                last = e
                continue
            }
            try {
                return callWithRetry(route, agent.role, capability, call)
            } catch (e: AiException) {
                last = e
                if (!e.error.tryFallback) throw e
            }
        }
        throw last ?: AiException(AiError.NOT_CONFIGURED, Msg.NO_PROVIDER_AVAILABLE())
    }

    private suspend fun <T> callWithRetry(route: Route, role: AgentRole, capability: Capability, call: suspend (Route) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return call(route)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                attempt++
                recordFailure(route, role, capability, e)
                if (!e.error.retryable || attempt >= MAX_ATTEMPTS) throw e
                delay(retryDelayMillis * attempt)
            }
        }
    }

    private fun monthStartMillis(): Long {
        val date: LocalDate = YearMonth.now(clock).atDay(1)
        return date.atStartOfDay(clock.zone).toInstant().toEpochMilli()
    }

    private suspend fun checkBudget(provider: AIProvider, model: AIModel, capability: Capability) {
        val month = YearMonth.now(clock).toString()
        val start = monthStartMillis()
        val budget = expenses.budgetFor(month)
        val providerLimit = expenses.providerBudget(provider.id)
        // An unpriced request counts as 0 in the ledger, so a limit could never trigger; refuse instead of spending blind.
        if ((budget?.maxSpending != null || providerLimit != null) && !CostCalculator.hasPrice(model, capability)) {
            throw AiException(AiError.BUDGET_EXCEEDED, Msg.BUDGET_MODEL_UNPRICED(model.displayName, capability.label))
        }
        val spent = expenses.spentSince(start)
        budget?.maxSpending?.let { max ->
            if (spent >= max) {
                throw AiException(AiError.BUDGET_EXCEEDED, Msg.MONTHLY_LIMIT_REACHED(max, budget.currency))
            }
        }
        providerLimit?.let { limit ->
            if (expenses.spentSince(start, provider.id) >= limit.monthlyLimit) {
                throw AiException(AiError.BUDGET_EXCEEDED, Msg.PROVIDER_LIMIT_REACHED(provider.name))
            }
        }
    }

    private suspend fun afterSpend(provider: AIProvider) {
        val month = YearMonth.now(clock).toString()
        val budget = expenses.budgetFor(month) ?: return
        if (budget.budget <= 0) return
        val spent = expenses.spentSince(monthStartMillis())
        val percent = spent / budget.budget * 100
        if (percent >= 100) {
            activity.notice(
                "budget.over.$month", NoticeSeverity.ERROR, Msg.NOTICE_BUDGET_USED(),
                Msg.NOTICE_BUDGET_SPENT(spent, budget.budget, budget.currency),
            )
        } else if (percent >= budget.warningPercent) {
            activity.notice(
                "budget.warn.$month", NoticeSeverity.WARNING, Msg.NOTICE_BUDGET_AT_PERCENT(percent),
                Msg.NOTICE_BUDGET_SPENT(spent, budget.budget, budget.currency),
            )
        }
    }

    private suspend fun record(
        route: Route,
        role: AgentRole,
        type: RequestType,
        cost: Cost,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        images: Int = 0,
        videos: Int = 0,
        videoSeconds: Double = 0.0,
        characters: Long = 0,
    ): Long {
        val model = route.model
        val id = expenses.record(
            ExpenseRecord(
                providerId = route.provider.id,
                providerName = route.provider.name,
                modelId = model.modelId,
                agentRole = role,
                requestType = type,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                imageCount = images,
                videoCount = videos,
                videoSeconds = videoSeconds,
                characters = characters,
                actualCost = cost.actual,
                estimatedCost = cost.estimated,
                confidence = cost.confidence,
                inputPricePerMTok = model.inputPricePerMTok,
                outputPricePerMTok = model.outputPricePerMTok,
                unitPrice = when (type) {
                    RequestType.IMAGE -> model.pricePerImage
                    RequestType.VIDEO -> model.pricePerVideoSecond
                    RequestType.SPEECH -> model.pricePerMChar
                    else -> null
                },
                currency = model.currency,
                createdAt = clock.millis(),
            ),
        )
        afterSpend(route.provider)
        return id
    }

    private suspend fun recordFailure(route: Route, role: AgentRole, capability: Capability, error: AiException) =
        recordFailure(route, role, capability, error.httpCode?.toString() ?: error.error.name)

    private suspend fun recordFailure(route: Route, role: AgentRole, capability: Capability, errorCode: String) {
        expenses.record(
            ExpenseRecord(
                providerId = route.provider.id,
                providerName = route.provider.name,
                modelId = route.model.modelId,
                agentRole = role,
                requestType = capability.requestType(),
                confidence = CostConfidence.UNPRICED,
                succeeded = false,
                errorCode = errorCode,
                currency = route.model.currency,
                createdAt = clock.millis(),
            ),
        )
    }

    private fun Capability.requestType() = when (this) {
        Capability.TEXT -> RequestType.TEXT
        Capability.VISION -> RequestType.VISION
        Capability.IMAGE -> RequestType.IMAGE
        Capability.VIDEO -> RequestType.VIDEO
        Capability.SPEECH -> RequestType.SPEECH
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
