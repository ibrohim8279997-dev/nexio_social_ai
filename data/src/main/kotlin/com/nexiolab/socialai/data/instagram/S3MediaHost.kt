package com.nexiolab.socialai.data.instagram

import com.nexiolab.socialai.data.ai.SharedHttpClient
import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.Hashing.toHex
import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.instagram.MediaHost
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Temporary hosting on any S3-compatible bucket the owner controls (Cloudflare R2, AWS S3,
 * Backblaze B2). Meta only accepts public URLs, so a file is public while it publishes and is
 * deleted right after. Requests are signed with AWS Signature Version 4.
 */
class S3MediaHost(
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val client: OkHttpClient = SharedHttpClient,
) : MediaHost {
    private data class Config(val endpoint: String, val region: String, val bucket: String, val accessKey: String, val secret: String, val publicBase: String)

    private suspend fun config(): Config? {
        val endpoint = settings.get(SettingKeys.HOSTING_ENDPOINT).orEmpty().trimEnd('/')
        val bucket = settings.get(SettingKeys.HOSTING_BUCKET).orEmpty()
        val access = settings.get(SettingKeys.HOSTING_ACCESS_KEY).orEmpty()
        val base = settings.get(SettingKeys.HOSTING_PUBLIC_BASE).orEmpty().trimEnd('/')
        val secret = credentials.get(CredentialStore.HOSTING_SECRET).orEmpty()
        if (listOf(endpoint, bucket, access, base, secret).any(String::isBlank)) return null
        if (!endpoint.startsWith("https://") || !base.startsWith("https://")) return null
        return Config(endpoint, settings.get(SettingKeys.HOSTING_REGION).orEmpty().ifBlank { "auto" }, bucket, access, secret, base)
    }

    override suspend fun isConfigured() = config() != null

    override suspend fun upload(path: String, mimeType: String, key: String): String {
        val cfg = config() ?: throw IOException(Msg.HOSTING_NOT_CONFIGURED())
        val file = File(path)
        val hash = withContext(Dispatchers.IO) { file.inputStream().use(Hashing::sha256) }
        val builder = Request.Builder().put(file.asRequestBody(mimeType.toMediaType()))
        call(signed(builder, cfg, "PUT", key, hash, mimeType))
        return "${cfg.publicBase}/$key"
    }

    override suspend fun delete(key: String) {
        val cfg = config() ?: return
        call(signed(Request.Builder().delete(), cfg, "DELETE", key, EMPTY_HASH, null))
    }

    private suspend fun call(request: Request) = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(Msg.HOSTING_HTTP(response.code))
        }
    }

    private fun signed(builder: Request.Builder, cfg: Config, method: String, key: String, payloadHash: String, contentType: String?): Request {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val url = "${cfg.endpoint}/${cfg.bucket}/$key".toHttpUrl()
        val headers = SigV4.sign(method, url.host, url.encodedPath, cfg.region, cfg.accessKey, cfg.secret, payloadHash, contentType, now)
        builder.url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private companion object {
        const val EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}

internal object SigV4 {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(text.toByteArray())
        }

    fun sign(
        method: String,
        host: String,
        path: String,
        region: String,
        accessKey: String,
        secret: String,
        payloadHash: String,
        contentType: String?,
        now: ZonedDateTime,
        extraSignedHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val amzDate = now.format(stamp)
        val date = now.format(day)
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate)
        contentType?.let { headers["content-type"] = it }
        headers.putAll(extraSignedHeaders)
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val canonical = listOf(method, path, "", canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val scope = "$date/$region/s3/aws4_request"
        val toSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, Hashing.sha256(canonical.toByteArray())).joinToString("\n")
        val signingKey = listOf(region, "s3", "aws4_request").fold(hmac("AWS4$secret".toByteArray(), date), ::hmac)
        val signature = hmac(signingKey, toSign).toHex()
        return mapOf(
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
        )
    }
}
