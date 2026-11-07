package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AIProvider

class ImageInput(val mimeType: String, val bytes: ByteArray)

data class ChatMessage(val role: Role, val text: String, val images: List<ImageInput> = emptyList()) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(text: String, images: List<ImageInput> = emptyList()) = ChatMessage(Role.USER, text, images)
    }
}

data class CompletionRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    /** Sent only to providers and models that accept it. */
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** [exact] is false when the provider returned no usage block and the counts were inferred. */
data class Usage(val inputTokens: Long, val outputTokens: Long, val exact: Boolean)

data class Completion(val text: String, val usage: Usage, val truncated: Boolean = false)

data class ImageRequest(val model: String, val prompt: String, val aspectWidth: Int, val aspectHeight: Int)

class GeneratedImage(val bytes: ByteArray, val mimeType: String)

data class VideoRequest(
    val model: String,
    val prompt: String,
    val aspectWidth: Int,
    val aspectHeight: Int,
    val durationSeconds: Int,
    val referenceImage: ImageInput? = null,
)

sealed interface VideoJobState {
    data object Running : VideoJobState
    class Finished(val bytes: ByteArray, val mimeType: String) : VideoJobState
    data class Failed(val message: String) : VideoJobState
}

data class SpeechRequest(val model: String, val voice: String, val text: String)

class GeneratedSpeech(val bytes: ByteArray, val mimeType: String)

data class DiscoveredModel(val id: String, val displayName: String = id)

/** One provider's API. Adapters translate; they hold no policy on budgets, retries or logging. */
interface AiClient {
    /** Doubles as the connection test: it proves the key works without spending tokens. */
    suspend fun listModels(): List<DiscoveredModel>
    suspend fun complete(request: CompletionRequest): Completion
    suspend fun generateImage(request: ImageRequest): GeneratedImage = unsupported(Capability.IMAGE)
    suspend fun startVideo(request: VideoRequest): String = unsupported(Capability.VIDEO)
    suspend fun pollVideo(jobId: String): VideoJobState = unsupported(Capability.VIDEO)
    suspend fun speech(request: SpeechRequest): GeneratedSpeech = unsupported(Capability.SPEECH)
}

private fun unsupported(what: Capability): Nothing =
    throw AiException(AiError.UNSUPPORTED, Msg.PROVIDER_UNSUPPORTED(what.label))

fun interface AiClientFactory {
    fun create(provider: AIProvider, apiKey: String): AiClient
}

enum class AiError(val retryable: Boolean = false, val tryFallback: Boolean = true) {
    AUTH,
    RATE_LIMITED(retryable = true),
    QUOTA,
    INVALID_REQUEST(tryFallback = false),
    NOT_FOUND,
    REFUSED,
    SERVER(retryable = true),
    NETWORK(retryable = true),
    UNSUPPORTED,
    NOT_CONFIGURED(tryFallback = false),
    BUDGET_EXCEEDED(tryFallback = false),
}

class AiException(val error: AiError, message: String, val httpCode: Int? = null) : Exception(message)

/** First guess at what a discovered model can do. The user can correct every flag in API Manager. */
object ModelHeuristics {
    private val nonChat = listOf("embed", "whisper", "moderation", "transcribe", "realtime", "guard", "aqa")

    fun capabilitiesOf(id: String): Capabilities {
        val name = id.lowercase()
        val image = listOf("image", "imagen", "dall-e").any(name::contains)
        val video = listOf("sora", "veo", "kling").any(name::contains)
        val speech = "tts" in name
        val other = nonChat.any(name::contains)
        return Capabilities(text = !(image || video || speech || other), image = image, video = video, speech = speech)
    }

    data class Capabilities(val text: Boolean, val image: Boolean, val video: Boolean, val speech: Boolean)
}
