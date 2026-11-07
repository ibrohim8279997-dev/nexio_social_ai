package com.nexiolab.socialai.ui.security

import android.content.res.Resources
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.BuildConfig
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.container
import com.nexiolab.socialai.ui.common.rememberAuthenticator
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

private data class Check(val title: String, val detail: String, val ok: Boolean)

private fun keystoreLevel(resources: Resources): String = runCatching {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = store.getKey("nexio.credentials.v1", null) as? SecretKey ?: return resources.getString(R.string.common_keystore_none)
    val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    when (info.securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> resources.getString(R.string.common_keystore_strongbox)
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> resources.getString(R.string.common_keystore_tee)
        else -> resources.getString(R.string.common_keystore_software)
    }
}.getOrDefault(resources.getString(R.string.common_keystore_unreadable))

/** Reports the live state of this install. It shows nothing it did not check just now. */
@Composable
fun SecurityScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val auth = rememberAuthenticator()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var permissions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val app = context.container
        val providers = app.providers.providers()
        val keys = providers.count { app.credentials.has(CredentialStore.provider(it.id)) }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val cleartext = android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
        checks = listOf(
            Check(
                resources.getString(R.string.common_check_screen_lock),
                if (auth.available) resources.getString(R.string.common_screen_lock_ok) else resources.getString(R.string.common_screen_lock_missing),
                auth.available,
            ),
            Check(resources.getString(R.string.common_key_storage), keystoreLevel(resources), true),
            Check(resources.getString(R.string.common_api_keys), resources.getString(R.string.common_api_keys_count, keys, providers.size), true),
            Check(
                resources.getString(R.string.common_encrypted_connections),
                if (cleartext) resources.getString(R.string.common_cleartext_allowed) else resources.getString(R.string.common_cleartext_blocked),
                !cleartext,
            ),
            Check(
                resources.getString(R.string.common_android_cloud_backup),
                if (context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0) resources.getString(R.string.common_backup_off) else resources.getString(R.string.common_on),
                context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0,
            ),
            Check(
                resources.getString(R.string.common_build_type),
                if (debuggable) resources.getString(R.string.common_debug_build, BuildConfig.VERSION_NAME) else resources.getString(R.string.common_release_build, BuildConfig.VERSION_NAME),
                !debuggable,
            ),
        )
        permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().filter { it.startsWith("android.permission") }.map { it.removePrefix("android.permission.") }.sorted()
    }
    ScreenColumn {
        items(checks.size) { i ->
            val check = checks[i]
            NexioCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (check.ok) Brand.Success else Brand.Warning)
                    Column(Modifier.weight(1f)) {
                        Text(check.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                        Text(check.detail, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    }
                }
            }
        }
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.common_permissions_declared))
                Gap(4)
                Text(permissions.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                Gap(4)
                Text(stringResource(R.string.common_no_sensitive_access), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text)
            }
        }
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.common_limit_title))
                Text(
                    stringResource(R.string.common_security_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nexio.colors.textMuted,
                )
            }
        }
    }
}
