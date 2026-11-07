package com.nexiolab.socialai.data.ai

import com.nexiolab.socialai.domain.ai.AiClient
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.ai.ChatMessage
import com.nexiolab.socialai.domain.ai.Completion
import com.nexiolab.socialai.domain.ai.CompletionRequest
import com.nexiolab.socialai.domain.ai.DiscoveredModel
import com.nexiolab.socialai.domain.ai.GeneratedImage
import com.nexiolab.socialai.domain.ai.GeneratedSpeech
import com.nexiolab.socialai.domain.ai.ImageRequest
import com.nexiolab.socialai.domain.ai.SpeechRequest
import com.nexiolab.socialai.domain.ai.Usage
import com.nexiolab.socialai.domain.ai.VideoJobState
import com.nexiolab.socialai.domain.ai.VideoRequest
import com.nexiolab.socialai.domain.i18n.Msg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.Base64

/** OpenAI, and any service that follows its REST shape (Groq, self-chosen compatible endpoints). */
internal class OpenAiClient(client: OkHttpClient, baseUrl: String, apiKey: String) : AiClient {
    private val http = HttpJson(client, baseUrl, mapOf("Authorization" to "Bearer $apiKey"))

    override suspend fun listModels(): List<DiscoveredModel> =
        http.get("/v1/models").array("data").mapNotNull { it.jsonObject.string("id") }.sorted().map(::DiscoveredModel)

    override suspend fun complete(request: CompletionRequest): Completion {
        val response = try {
            http.post("/v1/chat/completions", body(request, request.temperature))
        } catch (e: AiException) {
            // Some models accept only the default temperature; the error names the parameter.
            if (request.temperature != null && e.error == AiError.INVALID_REQUEST && "temperature" in e.message.orEmpty()) {
                http.post("/v1/chat/completions", body(request, null))
            } else {
                throw e
            }
        }
        val choice = response.array("choices").firstOrNull()?.jsonObject
            ?: throw AiException(AiError.SERVER, Msg.PROVIDER_NO_ANSWER())
        val finish = choice.string("finish_reason")
        if (finish == "content_filter") throw AiException(AiError.REFUSED, Msg.PROVIDER_FILTER_STOPPED())
        val usage = response.obj("usage")
        val input = usage?.long("prompt_tokens")
        val output = usage?.long("completion_tokens")
        return Completion(
            text = choice.obj("message")?.string("content").orEmpty(),
            usage = Usage(input ?: 0, output ?: 0, exact = input != null && output != null),
            truncated = finish == "length",
        )
    }

    private fun body(request: CompletionRequest, temperature: Double?): JsonObject = buildJsonObject {
        put("model", request.model)
        temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_completion_tokens", it) }
        putJsonArray("messages") {
            if (request.system.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.system)
                }
            }
            request.messages.forEach { message ->
                addJsonObject {
                    put("role", if (message.role == ChatMessage.Role.USER) "user" else "assistant")
                    if (message.images.isEmpty()) {
                        put("content", message.text)
                    } else {
                        putJsonArray("content") {
                            message.images.forEach { image ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        val data = Base64.getEncoder().encodeToString(image.bytes)
                                        put("url", "data:${image.mimeType};base64,$data")
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
    }

    override suspend fun generateImage(request: ImageRequest): GeneratedImage {
        val size = when {
            request.aspectWidth > request.aspectHeight -> "1536x1024"
            request.aspectWidth < request.aspectHeight -> "1024x1536"
            else -> "1024x1024"
        }
        val response = http.post(
            "/v1/images/generations",
            buildJsonObject {
                put("model", request.model)
                put("prompt", request.prompt)
                put("size", size)
                put("n", 1)
            },
        )
        val item = response.array("data").firstOrNull()?.jsonObject
            ?: throw AiException(AiError.SERVER, Msg.PROVIDER_NO_IMAGE())
        val bytes = item.string("b64_json")?.let { Base64.getDecoder().decode(it) }
            ?: item.string("url")?.let { http.getBytes(it) }
            ?: throw AiException(AiError.SERVER, Msg.PROVIDER_NO_IMAGE())
        return GeneratedImage(bytes, "image/png")
    }

    override suspend fun speech(request: SpeechRequest): GeneratedSpeech {
        val (bytes, _) = http.postForBytes(
            "/v1/audio/speech",
            buildJsonObject {
                put("model", request.model)
                put("voice", request.voice)
                put("input", request.text)
                put("response_format", "mp3")
            },
        )
        return GeneratedSpeech(bytes, "audio/mpeg")
    }

    override suspend fun startVideo(request: VideoRequest): String {
        val size = if (request.aspectWidth >= request.aspectHeight) "1280x720" else "720x1280"
        val response = http.post(
            "/v1/videos",
            buildJsonObject {
                put("model", request.model)
                put("prompt", request.prompt)
                put("seconds", request.durationSeconds.toString())
                put("size", size)
            },
        )
        return response.string("id") ?: throw AiException(AiError.SERVER, Msg.PROVIDER_NO_JOB_ID())
    }

    override suspend fun pollVideo(jobId: String): VideoJobState {
        val job = http.get("/v1/videos/$jobId")
        return when (job.string("status")) {
            "completed" -> VideoJobState.Finished(http.getBytes("/v1/videos/$jobId/content"), "video/mp4")
            "failed" -> VideoJobState.Failed(job.obj("error")?.string("message") ?: Msg.PROVIDER_VIDEO_FAILED())
            else -> VideoJobState.Running
        }
    }
}
