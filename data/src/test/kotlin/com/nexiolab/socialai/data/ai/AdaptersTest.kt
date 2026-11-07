package com.nexiolab.socialai.data.ai

import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.ai.ChatMessage
import com.nexiolab.socialai.domain.ai.CompletionRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AdaptersTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val request = CompletionRequest("m", "be brief", listOf(ChatMessage.user("hi")), temperature = 0.5, maxTokens = 100)
    private val base get() = server.url("").toString()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun anthropicSendsCurrentVersionAndNoSamplingParameters() = runTest {
        respond(200, """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":3}}""")
        val result = AnthropicClient(http, base, "secret").complete(request)
        val sent = server.takeRequest()
        assertEquals("secret", sent.headers["x-api-key"])
        assertEquals("2026-10-21", sent.headers["anthropic-version"])
        val body = ApiJson.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertFalse("temperature must never be sent", "temperature" in body)
        assertEquals("be brief", body.string("system"))
        assertEquals("ok", result.text)
        assertEquals(12, result.usage.inputTokens)
        assertTrue(result.usage.exact)
    }

    @Test fun anthropicRefusalIsNotReturnedAsContent() = runTest {
        respond(200, """{"content":[],"stop_reason":"refusal","stop_details":{"explanation":"declined"},"usage":{"input_tokens":1,"output_tokens":0}}""")
        try {
            AnthropicClient(http, base, "k").complete(request)
            fail("expected refusal")
        } catch (e: AiException) {
            assertEquals(AiError.REFUSED, e.error)
        }
    }

    @Test fun missingUsageIsMarkedInexact() = runTest {
        respond(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        val result = OpenAiClient(http, base, "k").complete(request)
        assertFalse(result.usage.exact)
    }

    @Test fun openAiRetriesWithoutTemperatureWhenModelRejectsIt() = runTest {
        respond(400, """{"error":{"message":"Unsupported value: 'temperature' does not support 0.5"}}""")
        respond(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}""")
        val result = OpenAiClient(http, base, "k").complete(request)
        assertEquals("ok", result.text)
        server.takeRequest()
        val second = ApiJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse("temperature" in second)
    }

    @Test fun geminiKeepsKeyOutOfUrlAndCountsThinkingAsOutput() = runTest {
        respond(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4,"thoughtsTokenCount":6}}""")
        val result = GeminiClient(http, base, "secret").complete(request)
        val sent = server.takeRequest()
        assertFalse("secret" in sent.url.toString())
        assertEquals("secret", sent.headers["x-goog-api-key"])
        assertEquals(10, result.usage.outputTokens)
    }

    @Test fun httpStatusesMapToErrorKinds() = runTest {
        val cases = listOf(
            401 to AiError.AUTH,
            429 to AiError.RATE_LIMITED,
            500 to AiError.SERVER,
            404 to AiError.NOT_FOUND,
        )
        for ((code, kind) in cases) {
            respond(code, """{"error":{"message":"nope"}}""")
            try {
                OpenAiClient(http, base, "k").complete(request.copy(temperature = null))
                fail("expected failure for $code")
            } catch (e: AiException) {
                assertEquals(kind, e.error)
            }
        }
    }

    @Test fun quotaExhaustionIsNotARetryableRateLimit() = runTest {
        respond(429, """{"error":{"message":"You exceeded your current quota"}}""")
        try {
            OpenAiClient(http, base, "k").complete(request)
            fail("expected failure")
        } catch (e: AiException) {
            assertEquals(AiError.QUOTA, e.error)
            assertFalse(e.error.retryable)
        }
    }

    @Test fun keyIsSentOnlyToTheSameHost() {
        val base = "https://api.openai.com/v1"
        assertTrue(isSameHost(base, "https://api.openai.com/v1/videos/1/content"))
        assertTrue(isSameHost(base, "https://API.OpenAI.com/files/x.png"))
        assertFalse("different host", isSameHost(base, "https://cdn.example.com/x.png"))
        assertFalse("look-alike host", isSameHost(base, "https://api.openai.com.evil.example/x.png"))
        assertFalse("look-alike prefix", isSameHost(base, "https://evilapi.openai.com/x.png"))
        assertFalse("userinfo trick", isSameHost(base, "https://api.openai.com@evil.example/x.png"))
        assertFalse(isSameHost(base, "not a url"))
    }
}
