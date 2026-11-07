package com.nexiolab.socialai.data.ai

import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.i18n.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val SharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // Non-streaming generation can legitimately take minutes.
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()
}

internal val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * JSON over HTTP for provider adapters. There is deliberately no logging interceptor here:
 * requests carry keys and business data, and neither belongs in logcat.
 */
internal class HttpJson(private val client: OkHttpClient, private val baseUrl: String, private val headers: Headers) {
    constructor(client: OkHttpClient, baseUrl: String, headers: Map<String, String>) : this(client, baseUrl.trimEnd('/'), headers.toHeaders())

    suspend fun get(path: String): JsonObject =
        json(call(request(path).get().build()))

    suspend fun post(path: String, body: JsonObject): JsonObject =
        json(call(request(path).post(body.toString().toRequestBody(JSON)).build()))

    suspend fun postRaw(path: String, body: RequestBody): JsonObject =
        json(call(request(path).post(body).build()))

    suspend fun postForBytes(path: String, body: JsonObject): Pair<ByteArray, String?> =
        bytes(request(path).post(body.toString().toRequestBody(JSON)).build())

    suspend fun getBytes(pathOrUrl: String): ByteArray = bytes(request(pathOrUrl).get().build()).first

    private fun request(pathOrUrl: String): Request.Builder {
        if (!pathOrUrl.startsWith("https://")) return Request.Builder().url(baseUrl + pathOrUrl).headers(headers)
        // A URL taken from a provider response may point at a CDN or another host; the key must never go there.
        val builder = Request.Builder().url(pathOrUrl)
        return if (isSameHost(baseUrl, pathOrUrl)) builder.headers(headers) else builder
    }

    private suspend fun bytes(request: Request): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        execute(request).use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.body.string())
            response.body.bytes() to response.header("Content-Type")
        }
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        execute(request).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, text)
            text
        }
    }

    private fun json(text: String): JsonObject = try {
        if (text.isBlank()) JsonObject(emptyMap()) else ApiJson.parseToJsonElement(text).jsonObject
    } catch (e: IllegalArgumentException) {
        throw AiException(AiError.SERVER, Msg.PROVIDER_UNREADABLE())
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) = continuation.resume(response)
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(AiException(AiError.NETWORK, Msg.NETWORK_ERROR(e.javaClass.simpleName)))
                    }
                }
            },
        )
    }

    private fun failure(code: Int, body: String): AiException {
        val message = errorMessage(body) ?: Msg.PROVIDER_HTTP(code)
        val lower = message.lowercase()
        val kind = when {
            code == 401 || code == 403 -> AiError.AUTH
            code == 402 -> AiError.QUOTA
            code == 429 && ("quota" in lower || "credit" in lower || "billing" in lower) -> AiError.QUOTA
            code == 429 -> AiError.RATE_LIMITED
            code == 404 -> AiError.NOT_FOUND
            code == 408 || code >= 500 -> AiError.SERVER
            else -> AiError.INVALID_REQUEST
        }
        return AiException(kind, message.take(300), code)
    }

    private fun errorMessage(body: String): String? = runCatching {
        val root = ApiJson.parseToJsonElement(body).jsonObject
        val error: JsonElement = root["error"] ?: return@runCatching root.string("message")
        (error as? JsonObject)?.string("message") ?: error.jsonPrimitive.contentOrNull
    }.getOrNull()

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** True only when both URLs parse and their hosts are identical (case-insensitive); look-alike hosts do not match. */
internal fun isSameHost(baseUrl: String, url: String): Boolean {
    val base = baseUrl.toHttpUrlOrNull() ?: return false
    val other = url.toHttpUrlOrNull() ?: return false
    return base.host.equals(other.host, ignoreCase = true)
}

internal fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? kotlinx.serialization.json.JsonArray).orEmpty()
internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
