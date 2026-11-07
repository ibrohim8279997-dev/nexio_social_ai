package com.nexiolab.socialai.data.instagram

import com.nexiolab.socialai.data.ai.ApiJson
import com.nexiolab.socialai.data.ai.SharedHttpClient
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.instagram.ContainerKind
import com.nexiolab.socialai.domain.instagram.ContainerState
import com.nexiolab.socialai.domain.instagram.ContainerStatus
import com.nexiolab.socialai.domain.instagram.IgMedia
import com.nexiolab.socialai.domain.instagram.IgProfile
import com.nexiolab.socialai.domain.instagram.IgToken
import com.nexiolab.socialai.domain.instagram.InstagramApi
import com.nexiolab.socialai.domain.instagram.InstagramException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Instagram API with Instagram Login (official Instagram Platform). The token travels in the
 * Authorization header, so it cannot end up in URL logs.
 */
class GraphInstagramApi(
    private val client: OkHttpClient = SharedHttpClient,
    private val graph: String = "https://graph.instagram.com",
    private val oauth: String = "https://api.instagram.com",
    private val version: String = "v23.0",
) : InstagramApi {

    private suspend fun send(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                val body = runCatching { ApiJson.parseToJsonElement(text).jsonObject }.getOrNull()
                if (response.isSuccessful && body != null) return@withContext body
                throw failure(response.code, body)
            }
        } catch (e: IOException) {
            throw InstagramException(Msg.IG_NO_CLEAR_ANSWER(e.javaClass.simpleName), delivered = false)
        }
    }

    private fun failure(http: Int, body: JsonObject?): InstagramException {
        val error = body?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val message = error?.get("error_user_msg")?.jsonPrimitive?.contentOrNull
            ?: error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: Msg.IG_HTTP(http)
        // A 5xx or a 408 (request timeout) proves nothing about whether the operation went through.
        return InstagramException(message.take(300), delivered = http in 400..499 && http != HTTP_REQUEST_TIMEOUT,authFailure = code == AUTH_ERROR || http == 401, code = code)
    }

    private fun get(token: String, path: String, vararg params: Pair<String, String>): Request {
        val url = "$graph/$version/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
    }

    private fun post(token: String, path: String, vararg params: Pair<String, String>): Request {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        return Request.Builder().url("$graph/$version/$path").header("Authorization", "Bearer $token").post(form).build()
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    override suspend fun profile(token: String): IgProfile {
        val me = send(get(token, "me", "fields" to "user_id,username,account_type"))
        return IgProfile(me.text("user_id") ?: me.text("id").orEmpty(), me.text("username").orEmpty(), me.text("account_type").orEmpty())
    }

    override suspend fun createContainer(token: String, igUserId: String, kind: ContainerKind, mediaUrl: String, caption: String): String {
        val params = when (kind) {
            ContainerKind.IMAGE -> arrayOf("image_url" to mediaUrl, "caption" to caption)
            ContainerKind.REEL -> arrayOf("media_type" to "REELS", "video_url" to mediaUrl, "caption" to caption)
            ContainerKind.STORY_IMAGE -> arrayOf("media_type" to "STORIES", "image_url" to mediaUrl)
            ContainerKind.STORY_VIDEO -> arrayOf("media_type" to "STORIES", "video_url" to mediaUrl)
        }
        return send(post(token, "$igUserId/media", *params)).text("id")
            ?: throw InstagramException(Msg.IG_NO_CONTAINER(), delivered = true)
    }

    override suspend fun createCarouselItem(token: String, igUserId: String, imageUrl: String): String =
        send(post(token, "$igUserId/media", "image_url" to imageUrl, "is_carousel_item" to "true")).text("id")
            ?: throw InstagramException(Msg.IG_NO_CONTAINER(), delivered = true)

    override suspend fun createCarousel(token: String, igUserId: String, childIds: List<String>, caption: String): String =
        send(post(token, "$igUserId/media", "media_type" to "CAROUSEL", "children" to childIds.joinToString(","), "caption" to caption)).text("id")
            ?: throw InstagramException(Msg.IG_NO_CONTAINER(), delivered = true)

    override suspend fun containerState(token: String, containerId: String): ContainerState {
        val body = send(get(token, containerId, "fields" to "status_code,status"))
        val status = when (body.text("status_code")) {
            "IN_PROGRESS" -> ContainerStatus.IN_PROGRESS
            "FINISHED" -> ContainerStatus.FINISHED
            "PUBLISHED" -> ContainerStatus.PUBLISHED
            "ERROR" -> ContainerStatus.ERROR
            "EXPIRED" -> ContainerStatus.EXPIRED
            else -> ContainerStatus.UNKNOWN
        }
        return ContainerState(status, body.text("status"))
    }

    override suspend fun publish(token: String, igUserId: String, containerId: String): String =
        send(post(token, "$igUserId/media_publish", "creation_id" to containerId)).text("id")
            ?: throw InstagramException(Msg.IG_NO_MEDIA_ID(), delivered = false)

    override suspend fun permalink(token: String, mediaId: String): String? =
        send(get(token, mediaId, "fields" to "permalink")).text("permalink")

    override suspend fun recentMedia(token: String, igUserId: String): List<IgMedia> =
        send(get(token, "$igUserId/media", "fields" to "id,caption,permalink", "limit" to "25"))["data"]?.jsonArray.orEmpty().map { item ->
            val row = item.jsonObject
            IgMedia(row.text("id").orEmpty(), row.text("caption").orEmpty(), row.text("permalink"))
        }

    override suspend fun insights(token: String, mediaId: String): Map<String, Double> {
        val body = send(get(token, "$mediaId/insights", "metric" to "reach,likes,comments,saved,shares,views"))
        return body["data"]?.jsonArray.orEmpty().mapNotNull { row ->
            val metric = row.jsonObject
            val name = metric.text("name") ?: return@mapNotNull null
            val value = metric["values"]?.jsonArray?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            name to value
        }.toMap()
    }

    override suspend fun refreshToken(token: String): IgToken {
        val url = "$graph/refresh_access_token".toHttpUrl().newBuilder().addQueryParameter("grant_type", "ig_refresh_token").build()
        val body = send(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())
        return IgToken(body.text("access_token").orEmpty(), body.text("expires_in")?.toLongOrNull())
    }

    override suspend fun exchangeCode(appId: String, appSecret: String, redirectUri: String, code: String): IgToken {
        val form = FormBody.Builder()
            .add("client_id", appId)
            .add("client_secret", appSecret)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code", code.removeSuffix("#_"))
            .build()
        val short = send(Request.Builder().url("$oauth/oauth/access_token").post(form).build())
        val shortToken = short.text("access_token") ?: throw InstagramException(Msg.IG_NO_TOKEN(), delivered = true)
        val url = "$graph/access_token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "ig_exchange_token")
            .addQueryParameter("client_secret", appSecret)
            .build()
        val long = send(Request.Builder().url(url).header("Authorization", "Bearer $shortToken").get().build())
        return IgToken(long.text("access_token").orEmpty(), long.text("expires_in")?.toLongOrNull(), short.text("user_id"))
    }

    private companion object {
        const val AUTH_ERROR = 190
        const val HTTP_REQUEST_TIMEOUT = 408
    }
}
