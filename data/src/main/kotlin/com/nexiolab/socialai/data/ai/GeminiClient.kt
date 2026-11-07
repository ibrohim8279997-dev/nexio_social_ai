package com.nexiolab.socialai.data.ai

import com.nexiolab.socialai.domain.ai.AiClient
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.ai.ChatMessage
import com.nexiolab.socialai.domain.ai.Completion
import com.nexiolab.socialai.domain.ai.CompletionRequest
import com.nexiolab.socialai.domain.ai.DiscoveredModel
import com.nexiolab.socialai.domain.ai.GeneratedImage
import com.nexiolab.socialai.domain.ai.ImageRequest
import com.nexiolab.socialai.domain.ai.Usage
import com.nexiolab.socialai.domain.ai.VideoJobState
import com.nexiolab.socialai.domain.ai.VideoRequest
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

/** Gemini API. The key travels in a header so it can never surface in a logged URL. */
internal class GeminiClient(client: OkHttpClient, baseUrl: String, apiKey: String) : AiClient {
    private val http = HttpJson(client, baseUrl, mapOf("x-goog-api-key" to apiKey))

    override suspend fun listModels(): List<DiscoveredModel> {
        val models = ArrayList<DiscoveredModel>()
        var token: String? = null
        do {
            val page = http.get("/v1beta/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: ""))
            page.array("models").map { it.jsonObject }.forEach { m ->
                val id = m.string("name")?.removePrefix("models/") ?: return@forEach
                models += DiscoveredModel(id, m.string("displayName") ?: id)
            }
            token = page.string("nextPageToken")
        } while (token != null)
        return models
    }

    override suspend fun complete(request: CompletionRequest): Completion {
        val body = buildJsonObject {
            if (request.system.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", request.system) } }
                }
            }
            putJsonArray("contents") {
                request.messages.forEach { message ->
                    addJsonObject {
                        put("role", if (message.role == ChatMessage.Role.USER) "user" else "model")
                        putJsonArray("parts") {
                            message.images.forEach { image ->
                                addJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", image.mimeType)
                                        put("data", Base64.getEncoder().encodeToString(image.bytes))
                                    }
                                }
                            }
                            addJsonObject { put("text", message.text) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                request.temperature?.let { put("temperature", it) }
                request.maxTokens?.let { put("maxOutputTokens", it) }
            }
        }
        val response = http.post("/v1beta/models/${request.model}:generateContent", body)
        response.obj("promptFeedback")?.string("blockReason")?.let {
            throw AiException(AiError.REFUSED, Msg.GEMINI_BLOCKED(it))
        }
        val candidate = response.array("candidates").firstOrNull()?.jsonObject
            ?: throw AiException(AiError.SERVER, Msg.PROVIDER_NO_ANSWER())
        val finish = candidate.string("finishReason")
        if (finish == "SAFETY" || finish == "RECITATION" || finish == "PROHIBITED_CONTENT") {
            throw AiException(AiError.REFUSED, Msg.GEMINI_STOPPED(finish))
        }
        val text = parts(candidate).filter { it.string("thought") != "true" }.joinToString("") { it.string("text").orEmpty() }
        val usage = response.obj("usageMetadata")
        val input = usage?.long("promptTokenCount")
        val output = usage?.let { (it.long("candidatesTokenCount") ?: 0) + (it.long("thoughtsTokenCount") ?: 0) }
        return Completion(
            text = text,
            usage = Usage(input ?: 0, output ?: 0, exact = input != null && output != null),
            truncated = finish == "MAX_TOKENS",
        )
    }

    private fun parts(candidate: JsonObject) = candidate.obj("content")?.array("parts").orEmpty().map { it.jsonObject }

    override suspend fun generateImage(request: ImageRequest): GeneratedImage =
        if (request.model.startsWith("imagen")) imagen(request) else nativeImage(request)

    private suspend fun nativeImage(request: ImageRequest): GeneratedImage {
        val response = http.post(
            "/v1beta/models/${request.model}:generateContent",
            buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", request.prompt) } }
                    }
                }
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("IMAGE") }
                    putJsonObject("imageConfig") { put("aspectRatio", "${request.aspectWidth}:${request.aspectHeight}") }
                }
            },
        )
        val candidate = response.array("candidates").firstOrNull()?.jsonObject
        val inline = candidate?.let(::parts).orEmpty().firstNotNullOfOrNull { it.obj("inlineData") }
            ?: throw AiException(AiError.REFUSED, Msg.GEMINI_NO_IMAGE())
        return GeneratedImage(
            Base64.getDecoder().decode(inline.string("data").orEmpty()),
            inline.string("mimeType") ?: "image/png",
        )
    }

    private suspend fun imagen(request: ImageRequest): GeneratedImage {
        val response = http.post(
            "/v1beta/models/${request.model}:predict",
            buildJsonObject {
                putJsonArray("instances") { addJsonObject { put("prompt", request.prompt) } }
                putJsonObject("parameters") {
                    put("sampleCount", 1)
                    put("aspectRatio", "${request.aspectWidth}:${request.aspectHeight}")
                }
            },
        )
        val prediction = response.array("predictions").firstOrNull()?.jsonObject
            ?: throw AiException(AiError.REFUSED, Msg.GEMINI_NO_IMAGE())
        return GeneratedImage(
            Base64.getDecoder().decode(prediction.string("bytesBase64Encoded").orEmpty()),
            prediction.string("mimeType") ?: "image/png",
        )
    }

    override suspend fun startVideo(request: VideoRequest): String {
        val response = http.post(
            "/v1beta/models/${request.model}:predictLongRunning",
            buildJsonObject {
                putJsonArray("instances") {
                    addJsonObject {
                        put("prompt", request.prompt)
                        request.referenceImage?.let { image ->
                            putJsonObject("image") {
                                put("bytesBase64Encoded", Base64.getEncoder().encodeToString(image.bytes))
                                put("mimeType", image.mimeType)
                            }
                        }
                    }
                }
                putJsonObject("parameters") {
                    put("aspectRatio", "${request.aspectWidth}:${request.aspectHeight}")
                    put("durationSeconds", request.durationSeconds)
                }
            },
        )
        return response.string("name") ?: throw AiException(AiError.SERVER, Msg.PROVIDER_NO_JOB_ID())
    }

    override suspend fun pollVideo(jobId: String): VideoJobState {
        val operation = http.get("/v1beta/$jobId")
        if (operation.string("done") != "true") return VideoJobState.Running
        operation.obj("error")?.let { return VideoJobState.Failed(it.string("message") ?: Msg.PROVIDER_VIDEO_FAILED()) }
        val sample = operation.obj("response")?.obj("generateVideoResponse")?.array("generatedSamples")?.firstOrNull()?.jsonObject
        val uri = sample?.obj("video")?.string("uri")
            ?: return VideoJobState.Failed(Msg.PROVIDER_NO_VIDEO())
        return VideoJobState.Finished(http.getBytes(uri), "video/mp4")
    }
}
