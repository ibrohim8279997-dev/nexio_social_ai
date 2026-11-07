package com.nexiolab.socialai.data.ai

import com.nexiolab.socialai.domain.ai.AiClient
import com.nexiolab.socialai.domain.ai.AiClientFactory
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AIProvider
import com.nexiolab.socialai.domain.model.ProviderType
import okhttp3.OkHttpClient

/** A new provider means one adapter class and one line here. */
class DefaultAiClientFactory(private val http: OkHttpClient = SharedHttpClient) : AiClientFactory {
    override fun create(provider: AIProvider, apiKey: String): AiClient {
        val base = provider.baseUrl.ifBlank { provider.type.defaultBaseUrl }
        if (!base.startsWith("https://")) {
            throw AiException(AiError.NOT_CONFIGURED, Msg.PROVIDER_NEEDS_HTTPS(provider.name))
        }
        return when (provider.type) {
            ProviderType.ANTHROPIC -> AnthropicClient(http, base, apiKey)
            ProviderType.GEMINI -> GeminiClient(http, base, apiKey)
            ProviderType.OPENAI, ProviderType.GROQ, ProviderType.OPENAI_COMPATIBLE -> OpenAiClient(http, base, apiKey)
        }
    }
}
