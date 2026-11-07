package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.model.AIModel
import com.nexiolab.socialai.domain.model.CostConfidence
import com.nexiolab.socialai.domain.model.ExpenseRecord

data class Cost(val actual: Double?, val estimated: Double?, val confidence: CostConfidence)

object CostCalculator {
    private const val MILLION = 1_000_000.0

    fun text(model: AIModel, usage: Usage): Cost {
        val inPrice = model.inputPricePerMTok
        val outPrice = model.outputPricePerMTok
        if (inPrice == null || outPrice == null) return Cost(null, null, CostConfidence.UNPRICED)
        val amount = usage.inputTokens / MILLION * inPrice + usage.outputTokens / MILLION * outPrice
        return if (usage.exact) Cost(amount, null, CostConfidence.MEASURED) else Cost(null, amount, CostConfidence.ESTIMATED)
    }

    /** [exact] is true for counted units (images) and false for units we can only infer (video length). */
    fun units(unitPrice: Double?, units: Double, exact: Boolean): Cost {
        if (unitPrice == null) return Cost(null, null, CostConfidence.UNPRICED)
        val amount = unitPrice * units
        return if (exact) Cost(amount, null, CostConfidence.MEASURED) else Cost(null, amount, CostConfidence.ESTIMATED)
    }

    /** Whether [model] has the price this [capability] is billed by; without it a spending limit cannot see the cost. */
    fun hasPrice(model: AIModel, capability: Capability): Boolean = when (capability) {
        Capability.TEXT, Capability.VISION -> model.inputPricePerMTok != null && model.outputPricePerMTok != null
        Capability.IMAGE -> model.pricePerImage != null
        Capability.VIDEO -> model.pricePerVideoSecond != null
        Capability.SPEECH -> model.pricePerMChar != null
    }

    /** Rough token count for a provider that returns no usage. Always flagged ESTIMATED. */
    fun approximateTokens(text: String): Long = (text.length / 4L).coerceAtLeast(1)
}

/** Aggregations behind the AI Expenses screen. Unpriced rows are counted, never treated as free. */
data class ExpenseSummary(
    val total: Double,
    val measured: Double,
    val estimated: Double,
    val unpricedRequests: Int,
    val failedRequests: Int,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val images: Int,
    val videos: Int,
    val videoSeconds: Double,
    /** Null until there is something to divide by. */
    val costPerImage: Double?,
    val costPerVideo: Double?,
    val byProvider: Map<String, Double>,
    val byModel: Map<String, Double>,
    val byAgent: Map<String, Double>,
) {
    companion object {
        fun of(rows: List<ExpenseRecord>): ExpenseSummary {
            fun group(key: (ExpenseRecord) -> String) =
                rows.groupBy(key).mapValues { (_, list) -> list.sumOf { it.amount } }
                    .entries.sortedByDescending { it.value }.associate { it.key to it.value }
            val images = rows.sumOf { it.imageCount }
            val videos = rows.sumOf { it.videoCount }
            return ExpenseSummary(
                total = rows.sumOf { it.amount },
                measured = rows.sumOf { it.actualCost ?: 0.0 },
                estimated = rows.sumOf { it.estimatedCost ?: 0.0 },
                unpricedRequests = rows.count { it.succeeded && it.confidence == CostConfidence.UNPRICED },
                failedRequests = rows.count { !it.succeeded },
                requests = rows.size,
                inputTokens = rows.sumOf { it.inputTokens },
                outputTokens = rows.sumOf { it.outputTokens },
                images = images,
                videos = videos,
                videoSeconds = rows.sumOf { it.videoSeconds },
                costPerImage = if (images > 0) rows.filter { it.imageCount > 0 }.sumOf { it.amount } / images else null,
                costPerVideo = if (videos > 0) rows.filter { it.videoCount > 0 }.sumOf { it.amount } / videos else null,
                byProvider = group { it.providerName },
                byModel = group { it.modelId },
                byAgent = group { it.agentRole?.title ?: "Other" },
            )
        }
    }
}
