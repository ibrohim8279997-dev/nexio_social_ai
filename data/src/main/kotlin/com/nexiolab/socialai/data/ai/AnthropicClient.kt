package com.nexiolab.socialai.data.ai

import com.nexiolab.socialai.domain.ai.AiClient
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.ai.ChatMessage
import com.nexiolab.socialai.domain.ai.Completion
import com.nexiolab.socialai.domain.ai.CompletionRequest
import com.nexiolab.socialai.domain.ai.DiscoveredModel
import com.nexiolab.socialai.domain.ai.Usage
import com.nexiolab.socialai.domain.i18n.Msg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.Base64

/**
 * Anthropic Messages API. Text and vision only; the API has no image, video or speech generation.
 * Sampling parameters are never sent: API version 2026-10-21 rejects them on every model.
 */
internal class AnthropicClient(client: OkHttpClient, baseUrl: String, apiKey: String) : AiClient {
    private val http = HttpJson(
        client,
        baseUrl,
        mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION),
    )

    override suspend fun listModels(): List<DiscoveredModel> {
        val models = ArrayList<DiscoveredModel>()
        var after: String? = null
        do {
            val page = http.get("/v1/models?limit=100" + (after?.let { "&after_id=$it" } ?: ""))
            page.array("data").map { it.jsonObject }.forEach { m ->
                val id = m.string("id") ?: return@forEach
                models += DiscoveredModel(id, m.string("display_name") ?: id)
            }
            after = page.string("last_id").takeIf { page.string("has_more") == "true" }
        } while (after != null)
        return models
    }

    override suspend fun complete(request: CompletionRequest): Completion {
        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
            if (request.system.isNotBlank()) put("system", request.system)
            putJsonArray("messages") {
                request.messages.forEach { message ->
                    addJsonObject {
                        put("role", if (message.role == ChatMessage.Role.USER) "user" else "assistant")
                        putJsonArray("content") {
                            message.images.forEach { image ->
                                addJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", image.mimeType)
                                        put("data", Base64.getEncoder().encodeToString(image.bytes))
                                    }
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            }
                        }
                    }
                }
            }
        }
        val response = http.post("/v1/messages", body)
        val stop = response.string("stop_reason")
        if (stop == "refusal") {
            val why = response.obj("stop_details")?.string("explanation")
            throw AiException(AiError.REFUSED, why ?: Msg.MODEL_DECLINED())
        }
        val text = response.array("content").map { it.jsonObject }
            .filter { it.string("type") == "text" }
            .joinToString("") { it.string("text").orEmpty() }
        val usage = response.obj("usage")
        val input = usage?.let(::billedInput)
        val output = usage?.long("output_tokens")
        return Completion(
            text = text,
            usage = Usage(input ?: 0, output ?: 0, exact = input != null && output != null),
            truncated = stop == "max_tokens",
        )
    }

    private fun billedInput(usage: JsonObject): Long? = usage.long("input_tokens")

    private companion object {
        const val API_VERSION = "2026-10-21"
        const val DEFAULT_MAX_TOKENS = 16_000
    }
}
