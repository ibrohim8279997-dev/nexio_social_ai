package com.nexiolab.socialai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.BuildConfig
import com.nexiolab.socialai.R
import com.nexiolab.socialai.data.db.NexioDatabase
import com.nexiolab.socialai.domain.Brand
import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.ui.common.AppLanguage
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.LiveField
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SettingsRow
import com.nexiolab.socialai.ui.navigation.Section
import com.nexiolab.socialai.ui.navigation.Tab
import com.nexiolab.socialai.ui.theme.Nexio
import com.nexiolab.socialai.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(private val app: AppContainer) : BaseViewModel() {
    val theme = app.settings.observe(SettingKeys.THEME).state(null)
    val language = app.settings.observe(SettingKeys.CONTENT_LANGUAGE).state(null)
    val notifications = app.settings.observe(SettingKeys.NOTIFICATIONS).state(null)
    val name = app.settings.observe(SettingKeys.DISPLAY_NAME).state(null)

    fun set(key: String, value: String) = quiet { app.settings.set(key, value) }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        app.context.filesDir.walkTopDown().filter(File::isFile).sumOf(File::length) +
            app.context.getDatabasePath(NexioDatabase.FILE_NAME).length()
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.DARK -> R.string.common_theme_dark
    ThemeMode.LIGHT -> R.string.common_theme_light
    ThemeMode.SYSTEM -> R.string.common_system
}

@Composable
fun SettingsScreen(open: (String) -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    Messages(vm)
    val theme by vm.theme.collectAsState()
    val language by vm.language.collectAsState()
    val notifications by vm.notifications.collectAsState()
    val name by vm.name.collectAsState()
    val context = LocalContext.current
    val appLanguage = remember { AppLanguage.current(context) }
    var storage by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { storage = vm.storageBytes() }
    ScreenColumn {
        item {
            NexioCard {
                LiveField(stringResource(R.string.common_your_name), name.orEmpty(), { vm.set(SettingKeys.DISPLAY_NAME, it) })
                Gap()
                LiveField(
                    stringResource(R.string.common_ai_content_language),
                    language.orEmpty(),
                    { vm.set(SettingKeys.CONTENT_LANGUAGE, it) },
                    supporting = stringResource(R.string.common_ai_content_language_hint),
                )
            }
        }
        item {
            NexioCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                SettingsRow(stringResource(R.string.common_brand_profile), icon = Section.Brand.icon, onClick = { open(Section.Brand.route) })
                SettingsRow(stringResource(R.string.common_ai_providers_models), icon = Section.Api.icon, onClick = { open(Section.Api.route) })
                SettingsRow(stringResource(R.string.common_agent_configuration), icon = Icons.Outlined.SmartToy, onClick = { open(Tab.Agents.route) })
                SettingsRow(stringResource(R.string.common_instagram_connections), icon = Section.Instagram.icon, onClick = { open(Section.Instagram.route) })
                SettingsRow(stringResource(R.string.common_data_migration), icon = Section.Migration.icon, onClick = { open(Section.Migration.route) })
                SettingsRow(stringResource(R.string.common_backup_restore), icon = Icons.Outlined.Backup, onClick = { open(Section.Migration.route) })
                SettingsRow(stringResource(R.string.common_security_center), icon = Section.Security.icon, onClick = { open(Section.Security.route) })
            }
        }
        item {
            NexioCard {
                Text(stringResource(R.string.common_app_language), style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(appLanguage == AppLanguage.SYSTEM, { AppLanguage.set(context, AppLanguage.SYSTEM) }, { Text(stringResource(R.string.common_system)) })
                    AppLanguage.options.forEach { (tag, name) ->
                        FilterChip(appLanguage == tag, { AppLanguage.set(context, tag) }, { Text(name) })
                    }
                }
            }
        }
        item {
            NexioCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = (theme ?: ThemeMode.DARK.name) == mode.name,
                            onClick = { vm.set(SettingKeys.THEME, mode.name) },
                            label = { Text(stringResource(themeLabel(mode))) },
                        )
                    }
                }
            }
        }
        item {
            NexioCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                SettingsRow(
                    stringResource(R.string.common_notifications),
                    stringResource(R.string.common_notifications_summary),
                    Icons.Outlined.Notifications,
                    trailing = { Switch(notifications != "false", { vm.set(SettingKeys.NOTIFICATIONS, it.toString()) }) },
                )
                SettingsRow(
                    stringResource(R.string.common_storage),
                    if (storage >= 0) stringResource(R.string.common_storage_used, Format.bytes(storage)) else stringResource(R.string.common_measuring),
                    Icons.Outlined.Storage,
                    onClick = { open(Section.Media.route) },
                )
                SettingsRow("${Brand.PRODUCT} ${BuildConfig.VERSION_NAME}", "${Brand.COMPANY}. ${Brand.SLOGAN}", Icons.Outlined.Info)
            }
        }
    }
}
