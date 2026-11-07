package com.nexiolab.socialai.ui.instagram

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.InstagramCallback
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.domain.instagram.InstagramException
import com.nexiolab.socialai.domain.model.InstagramAccount
import com.nexiolab.socialai.domain.model.InstagramPost
import com.nexiolab.socialai.domain.model.PublishState
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.LiveField
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.common.rememberAuthenticator
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.components.StatusChip
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import com.nexiolab.socialai.work.Jobs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64

@Composable
private fun PublishState.label(): String = stringResource(
    when (this) {
        PublishState.PENDING -> R.string.connect_publish_pending
        PublishState.MEDIA_UPLOADED -> R.string.connect_publish_uploading
        PublishState.CONTAINER_CREATED -> R.string.connect_publish_processing
        PublishState.READY_TO_PUBLISH -> R.string.connect_publish_publishing
        PublishState.PUBLISH_UNCERTAIN -> R.string.connect_publish_needs_checking
        PublishState.PUBLISHED -> R.string.connect_publish_published
        PublishState.FAILED -> R.string.connect_publish_failed
    },
)

private fun PublishState.color(): Color = when (this) {
    PublishState.PUBLISHED -> Brand.Success
    PublishState.FAILED -> Brand.Danger
    PublishState.PUBLISH_UNCERTAIN -> Brand.Warning
    else -> Brand.Accent
}

data class HostingForm(val endpoint: String = "", val region: String = "", val bucket: String = "", val accessKey: String = "", val publicBase: String = "", val hasSecret: Boolean = false)

class InstagramViewModel(private val app: AppContainer) : BaseViewModel() {
    val account = app.instagram.observeAccount().state(null)
    val posts = app.instagram.observePosts().state(emptyList())
    val appId = app.settings.observe(SettingKeys.META_APP_ID).state(null)
    val redirect = app.settings.observe(REDIRECT).state(null)

    val hosting = combine(
        app.settings.observe(SettingKeys.HOSTING_ENDPOINT),
        app.settings.observe(SettingKeys.HOSTING_REGION),
        app.settings.observe(SettingKeys.HOSTING_BUCKET),
        app.settings.observe(SettingKeys.HOSTING_ACCESS_KEY),
        app.settings.observe(SettingKeys.HOSTING_PUBLIC_BASE),
    ) { endpoint, region, bucket, key, base ->
        HostingForm(endpoint.orEmpty(), region.orEmpty(), bucket.orEmpty(), key.orEmpty(), base.orEmpty(), app.credentials.has(CredentialStore.HOSTING_SECRET))
    }.state(HostingForm())

    fun set(key: String, value: String) = quiet { app.settings.set(key, value) }

    private suspend fun connect(token: String, expiresIn: Long?) {
        val profile = app.instagramApi.profile(token)
        app.credentials.put(CredentialStore.INSTAGRAM_TOKEN, token)
        app.instagram.saveAccount(
            InstagramAccount(
                igUserId = profile.id,
                username = profile.username,
                accountType = profile.accountType,
                tokenExpiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000 },
                connectedAt = System.currentTimeMillis(),
            ),
        )
        app.activity.log(app.context.getString(R.string.connect_ig_connected), profile.username)
    }

    fun connectWithToken(token: String) = act(app.context.getString(R.string.connect_ig_connected)) {
        require(token.isNotBlank()) { app.context.getString(R.string.connect_ig_paste_token) }
        try {
            connect(token.trim(), null)
        } catch (e: InstagramException) {
            throw IllegalStateException(app.context.getString(R.string.connect_ig_token_refused, e.message))
        }
    }

    /** Stores the secret and returns the official authorization address to open in the browser. */
    suspend fun authorizationUrl(id: String, secret: String, redirectUri: String): Uri {
        if (secret.isNotBlank()) app.credentials.put(CredentialStore.META_APP_SECRET, secret.trim())
        app.settings.set(SettingKeys.META_APP_ID, id.trim())
        app.settings.set(REDIRECT, redirectUri.trim())
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        app.settings.set(OAUTH_STATE, state)
        return Uri.parse("https://www.instagram.com/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", id.trim())
            .appendQueryParameter("redirect_uri", redirectUri.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "instagram_business_basic,instagram_business_content_publish,instagram_business_manage_insights")
            .appendQueryParameter("state", state)
            .build()
    }

    /** Accepts the redirect, from the app link or pasted from the browser. */
    fun finish(callback: String) = act(app.context.getString(R.string.connect_ig_connected)) {
        val uri = Uri.parse(callback.trim())
        val code = uri.getQueryParameter("code") ?: throw IllegalStateException(uri.getQueryParameter("error_description") ?: app.context.getString(R.string.connect_ig_no_code))
        val expected = app.settings.get(OAUTH_STATE)
        if (expected.isNullOrBlank() || uri.getQueryParameter("state") != expected) {
            throw IllegalStateException(app.context.getString(R.string.connect_ig_auth_rejected))
        }
        val id = app.settings.get(SettingKeys.META_APP_ID).orEmpty()
        val secret = app.credentials.get(CredentialStore.META_APP_SECRET) ?: throw IllegalStateException(app.context.getString(R.string.connect_ig_secret_missing))
        val token = app.instagramApi.exchangeCode(id, secret, app.settings.get(REDIRECT).orEmpty(), code)
        connect(token.accessToken, token.expiresInSeconds)
        app.settings.set(OAUTH_STATE, "")
    }

    fun saveHostingSecret(secret: String) = act(app.context.getString(R.string.connect_saved)) { app.credentials.put(CredentialStore.HOSTING_SECRET, secret.trim()) }

    fun disconnect() = act(app.context.getString(R.string.connect_ig_disconnected)) {
        app.instagram.clearAccount()
        app.credentials.delete(CredentialStore.INSTAGRAM_TOKEN)
    }

    fun check(post: InstagramPost) = act { Jobs.publishRun(app.context, post.id) }

    /** Starts a fresh attempt for a failed post; the publisher asks Instagram about the old container first. */
    fun tryAgain(post: InstagramPost) = act { Jobs.publishNow(app.context, post.contentItemId) }

    fun resolve(post: InstagramPost, published: Boolean) = act { app.publisher.resolveManually(post.id, published) }

    private companion object {
        const val REDIRECT = "meta_redirect_uri"
        const val OAUTH_STATE = "meta_oauth_state"
    }
}

@Composable
fun InstagramScreen() {
    val vm = appViewModel { InstagramViewModel(it) }
    Messages(vm)
    val account by vm.account.collectAsState()
    val posts by vm.posts.collectAsState()
    val busy by vm.busy.collectAsState()
    val auth = rememberAuthenticator()
    val callback by InstagramCallback.pending.collectAsState()
    val confirmDisconnect = stringResource(R.string.connect_ig_confirm_disconnect)
    LaunchedEffect(callback) {
        callback?.let {
            InstagramCallback.pending.value = null
            vm.finish(it)
        }
    }
    ScreenColumn {
        item { AccountCard(vm, account, busy) }
        item { HostingCard(vm, busy) }
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.connect_ig_allows_title))
                Text(
                    stringResource(R.string.connect_ig_allows_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nexio.colors.textMuted,
                )
            }
        }
        item { SectionHeader(stringResource(R.string.connect_ig_history)) }
        if (posts.isEmpty()) item { Text(stringResource(R.string.connect_ig_nothing_published), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted) }
        items(posts.size, key = { posts[it].id }) { i -> PostRow(posts[i], vm) }
        if (account != null) {
            item { TextButton({ auth.confirm(confirmDisconnect) { vm.disconnect() } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_ig_disconnect), color = Brand.Danger) } }
        }
    }
}

@Composable
private fun AccountCard(vm: InstagramViewModel, account: InstagramAccount?, busy: Boolean) {
    val context = LocalContext.current
    val savedId by vm.appId.collectAsState()
    val savedRedirect by vm.redirect.collectAsState()
    var appId by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") } // Not saveable: secrets stay out of saved state.
    var redirect by rememberSaveable { mutableStateOf("") }
    var pasted by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(savedId, savedRedirect) {
        if (appId.isEmpty()) appId = savedId.orEmpty()
        if (redirect.isEmpty()) redirect = savedRedirect.orEmpty()
    }
    NexioCard {
        SectionHeader(stringResource(R.string.connect_ig_account))
        if (account != null) {
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("@${account.username}", style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                    Text(account.accountType, style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                    account.tokenExpiresAt?.let { Text(stringResource(R.string.connect_ig_access_until, Format.date(it)), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted) }
                }
                if (account.needsReconnect) StatusChip(stringResource(R.string.connect_ig_reconnect), Brand.Danger) else StatusChip(stringResource(R.string.connect_connected), Brand.Success)
            }
        }
        if (account == null || account.needsReconnect) {
            Gap(4)
            Text(stringResource(R.string.connect_ig_sign_in_hint), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
            Gap(4)
            Field(stringResource(R.string.connect_ig_app_id), appId, { appId = it })
            Field(stringResource(R.string.connect_ig_app_secret), secret, { secret = it }, visual = PasswordVisualTransformation(), supporting = stringResource(R.string.connect_ig_app_secret_hint))
            Field(stringResource(R.string.connect_ig_redirect), redirect, { redirect = it }, supporting = stringResource(R.string.connect_ig_redirect_hint))
            Gap(4)
            val scopeRunner = androidx.compose.runtime.rememberCoroutineScope()
            BusyButton(stringResource(R.string.connect_ig_sign_in), busy, enabled = appId.isNotBlank() && redirect.startsWith("https://")) {
                val typedSecret = secret
                secret = ""
                scopeRunner.launch {
                    CustomTabsIntent.Builder().build().launchUrl(context, vm.authorizationUrl(appId, typedSecret, redirect))
                }
            }
            Gap(4)
            Field(stringResource(R.string.connect_ig_pasted_address), pasted, { pasted = it }, supporting = stringResource(R.string.connect_ig_pasted_hint))
            OutlinedButton({ vm.finish(pasted); pasted = "" }, Modifier.fillMaxWidth(), enabled = pasted.isNotBlank()) { Text(stringResource(R.string.connect_ig_finish)) }
            Gap()
            TextButton({ scope = scope != true }) { Text(stringResource(R.string.connect_ig_use_token)) }
            if (scope == true) {
                Field(stringResource(R.string.connect_ig_access_token), token, { token = it }, visual = PasswordVisualTransformation(), supporting = stringResource(R.string.connect_ig_access_token_hint))
                OutlinedButton({ vm.connectWithToken(token); token = "" }, Modifier.fillMaxWidth(), enabled = token.isNotBlank()) { Text(stringResource(R.string.connect_ig_connect_token)) }
            }
        }
    }
}

@Composable
private fun HostingCard(vm: InstagramViewModel, busy: Boolean) {
    val form by vm.hosting.collectAsState()
    var secret by remember { mutableStateOf("") }
    NexioCard {
        SectionHeader(stringResource(R.string.connect_ig_hosting_title))
        Text(
            stringResource(R.string.connect_ig_hosting_body),
            style = MaterialTheme.typography.bodySmall,
            color = Nexio.colors.textMuted,
        )
        Gap(4)
        LiveField(stringResource(R.string.connect_ig_s3_endpoint), form.endpoint, { vm.set(SettingKeys.HOSTING_ENDPOINT, it) }, supporting = stringResource(R.string.connect_https_only))
        LiveField(stringResource(R.string.connect_ig_region), form.region, { vm.set(SettingKeys.HOSTING_REGION, it) }, supporting = stringResource(R.string.connect_ig_region_hint))
        LiveField(stringResource(R.string.connect_ig_bucket), form.bucket, { vm.set(SettingKeys.HOSTING_BUCKET, it) })
        LiveField(stringResource(R.string.connect_ig_public_base), form.publicBase, { vm.set(SettingKeys.HOSTING_PUBLIC_BASE, it) }, supporting = stringResource(R.string.connect_ig_public_base_hint))
        LiveField(stringResource(R.string.connect_ig_access_key_id), form.accessKey, { vm.set(SettingKeys.HOSTING_ACCESS_KEY, it) })
        Field(stringResource(if (form.hasSecret) R.string.connect_ig_replace_secret else R.string.connect_ig_secret_key), secret, { secret = it }, visual = PasswordVisualTransformation())
        BusyButton(stringResource(R.string.connect_ig_save_secret), busy, enabled = secret.isNotBlank()) { vm.saveHostingSecret(secret); secret = "" }
    }
}

@Composable
private fun PostRow(post: InstagramPost, vm: InstagramViewModel) {
    NexioCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(Format.dateTime(post.publishedAt ?: post.createdAt), style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                post.permalink?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            StatusChip(post.state.label(), post.state.color())
        }
        post.lastError?.let { Gap(4); Text(it, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted) }
        if (post.state == PublishState.PUBLISH_UNCERTAIN) {
            Gap(4)
            Text(stringResource(R.string.connect_ig_uncertain), style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
            Gap(4)
            OutlinedButton({ vm.check(post) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_ig_ask_again)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ vm.resolve(post, true) }, Modifier.weight(1f)) { Text(stringResource(R.string.connect_ig_was_posted)) }
                TextButton({ vm.resolve(post, false) }, Modifier.weight(1f)) { Text(stringResource(R.string.connect_ig_was_not_posted)) }
            }
        } else if (post.state == PublishState.FAILED) {
            Gap(4)
            OutlinedButton({ vm.tryAgain(post) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_ig_try_again)) }
        } else if (post.state != PublishState.PUBLISHED) {
            Gap(4)
            OutlinedButton({ vm.check(post) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_ig_continue)) }
        }
    }
}
