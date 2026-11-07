package com.nexiolab.socialai.ui.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.ai.ExpenseSummary
import com.nexiolab.socialai.domain.model.AIModel
import com.nexiolab.socialai.domain.model.AIProvider
import com.nexiolab.socialai.domain.model.ProviderBudget
import com.nexiolab.socialai.domain.model.ProviderType
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.common.rememberAuthenticator
import com.nexiolab.socialai.ui.components.EmptyState
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.components.StatusChip
import com.nexiolab.socialai.ui.navigation.Routes
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.time.ZoneId

class ApiViewModel(private val app: AppContainer) : BaseViewModel() {
    val providers = app.providers.observeProviders().state(emptyList())

    fun add(type: ProviderType, name: String, baseUrl: String, then: (Long) -> Unit) = act {
        val url = baseUrl.ifBlank { type.defaultBaseUrl }.trim()
        require(url.startsWith("https://")) { app.context.getString(R.string.connect_api_https_required) }
        val first = app.providers.providers().isEmpty()
        val order = app.providers.providers().size
        then(app.providers.saveProvider(AIProvider(type = type, name = name.ifBlank { type.label }, baseUrl = url, isDefault = first, fallbackOrder = order)))
    }
}

@Composable
fun ApiScreen(open: (String) -> Unit) {
    val vm = appViewModel { ApiViewModel(it) }
    Messages(vm)
    val providers by vm.providers.collectAsState()
    var adding by remember { mutableStateOf(false) }
    ScreenColumn {
        item {
            NexioCard {
                Text(stringResource(R.string.connect_api_keystore_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
            }
        }
        if (providers.isEmpty()) {
            item { EmptyState(Icons.Outlined.Key, stringResource(R.string.connect_api_empty_title), stringResource(R.string.connect_api_empty_message)) }
        }
        items(providers.size, key = { providers[it].id }) { i ->
            val provider = providers[i]
            NexioCard(onClick = { open(Routes.provider(provider.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                        Text(provider.type.label, style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                    }
                    if (provider.isDefault) StatusChip(stringResource(R.string.connect_chip_default), Brand.Accent)
                    if (!provider.enabled) StatusChip(stringResource(R.string.connect_chip_off), Nexio.colors.textMuted)
                    when (provider.lastTestOk) {
                        true -> StatusChip(stringResource(R.string.connect_connected), Brand.Success)
                        false -> StatusChip(stringResource(R.string.connect_failed), Brand.Danger)
                        null -> StatusChip(stringResource(R.string.connect_chip_not_tested), Brand.Warning)
                    }
                }
            }
        }
        item {
            OutlinedButton({ adding = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Text("  ${stringResource(R.string.connect_add_provider)}")
            }
        }
    }
    if (adding) AddDialog({ adding = false }) { type, name, url ->
        adding = false
        vm.add(type, name, url) { open(Routes.provider(it)) }
    }
}

@Composable
private fun AddDialog(dismiss: () -> Unit, add: (ProviderType, String, String) -> Unit) {
    var type by remember { mutableStateOf(ProviderType.ANTHROPIC) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.connect_add_provider)) },
        text = {
            Column {
                OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text(type.label) }
                DropdownMenu(menu, { menu = false }) {
                    ProviderType.entries.forEach { DropdownMenuItem({ Text(it.label) }, { type = it; menu = false }) }
                }
                Gap()
                Field(stringResource(R.string.connect_name_optional), name, { name = it })
                if (type == ProviderType.OPENAI_COMPATIBLE) Field(stringResource(R.string.connect_base_address), url, { url = it }, supporting = stringResource(R.string.connect_https_only))
            }
        },
        confirmButton = { TextButton({ add(type, name, url) }, enabled = type != ProviderType.OPENAI_COMPATIBLE || url.startsWith("https://")) { Text(stringResource(R.string.connect_add)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.connect_cancel)) } },
    )
}

class ProviderViewModel(private val app: AppContainer, private val id: Long) : BaseViewModel() {
    val provider = combine(app.providers.observeProviders(), MutableStateFlow(id)) { all, wanted -> all.firstOrNull { it.id == wanted } }.state(null)
    val models = combine(app.providers.observeModels(), MutableStateFlow(id)) { all, wanted -> all.filter { it.providerId == wanted } }.state(emptyList())
    val maskedKey = MutableStateFlow<String?>(null)

    private val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val usage = app.expenses.observeSince(monthStart).combine(provider) { rows, p -> ExpenseSummary.of(rows.filter { it.providerId == p?.id }) }.state(ExpenseSummary.of(emptyList()))

    init {
        act { maskedKey.value = app.credentials.masked(CredentialStore.provider(id)) }
    }

    fun saveKey(key: String) = act(app.context.getString(R.string.connect_api_key_saved)) {
        require(key.isNotBlank()) { app.context.getString(R.string.connect_enter_key) }
        app.credentials.put(CredentialStore.provider(id), key.trim())
        maskedKey.value = app.credentials.masked(CredentialStore.provider(id))
    }

    fun removeKey() = act(app.context.getString(R.string.connect_api_key_removed)) {
        app.credentials.delete(CredentialStore.provider(id))
        maskedKey.value = null
    }

    fun test() = act {
        val current = app.providers.provider(id) ?: return@act
        val result = app.gateway.testConnection(current)
        result.onSuccess { found ->
            app.providers.addDiscoveredModels(id, found.map { it.id })
            messages.tryEmit(app.context.getString(R.string.connect_models_found, found.size))
        }.onFailure { messages.tryEmit(it.message ?: app.context.getString(R.string.connect_connection_failed)) }
    }

    val limit = app.expenses.observeProviderBudgets().map { rows -> rows.firstOrNull { it.providerId == id } }.state(null)

    fun setLimit(amount: Double?) = act(app.context.getString(R.string.connect_limit_saved)) {
        if (amount == null || amount <= 0) app.expenses.deleteProviderBudget(id) else app.expenses.saveProviderBudget(ProviderBudget(id, amount))
    }

    fun setEnabled(on: Boolean) = act { app.providers.provider(id)?.let { app.providers.saveProvider(it.copy(enabled = on)) } }
    fun makeDefault() = act(app.context.getString(R.string.connect_default_set)) { app.providers.setDefault(id) }
    fun setOrder(order: Int) = act { app.providers.provider(id)?.let { app.providers.saveProvider(it.copy(fallbackOrder = order)) } }
    fun toggle(model: AIModel, on: Boolean) = act { app.providers.saveModel(model.copy(selected = on)) }
    fun saveModel(model: AIModel) = act(app.context.getString(R.string.connect_saved)) { app.providers.saveModel(model.copy(priceUpdatedAt = System.currentTimeMillis())) }
    fun addModel(modelId: String) = act { app.providers.saveModel(AIModel(providerId = id, modelId = modelId.trim())) }
    fun remove(then: () -> Unit) = act {
        app.providers.provider(id)?.let { app.providers.removeProvider(it) }
        then()
    }
}

@Composable
fun ProviderScreen(id: Long, onClose: () -> Unit) {
    val vm = appViewModel(key = "provider$id") { ProviderViewModel(it, id) }
    Messages(vm)
    val auth = rememberAuthenticator()
    val provider by vm.provider.collectAsState()
    val models by vm.models.collectAsState()
    val masked by vm.maskedKey.collectAsState()
    val busy by vm.busy.collectAsState()
    val usage by vm.usage.collectAsState()
    val current = provider ?: return
    var key by remember { mutableStateOf("") } // Not saveable: secrets stay out of saved state.
    val limit by vm.limit.collectAsState()
    var limitText by remember(limit) { mutableStateOf(limit?.monthlyLimit?.toString().orEmpty()) }
    var filter by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<AIModel?>(null) }
    val confirmSaveKey = stringResource(R.string.connect_confirm_save_key)
    val confirmRemoveKey = stringResource(R.string.connect_confirm_remove_key)
    val confirmRemoveProvider = stringResource(R.string.connect_remove_provider)
    val shown = models.filter { filter.isBlank() || filter.lowercase() in it.modelId.lowercase() }.sortedByDescending { it.selected }
    ScreenColumn {
        item {
            NexioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
                        Text(current.baseUrl, style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                    }
                    Switch(current.enabled, vm::setEnabled)
                }
                current.lastError?.let { Gap(4); Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Danger) }
                current.lastTestAt?.let { Gap(4); Text(stringResource(R.string.connect_last_test, Format.dateTime(it)), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted) }
            }
        }
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.connect_section_api_key))
                Gap(4)
                Text(masked ?: stringResource(R.string.connect_no_key_saved), style = MaterialTheme.typography.bodyMedium, color = Nexio.colors.text)
                Gap(4)
                Field(stringResource(if (masked == null) R.string.connect_paste_key else R.string.connect_replace_key), key, { key = it }, visual = PasswordVisualTransformation())
                Gap(4)
                BusyButton(stringResource(R.string.connect_save_key), busy, enabled = key.isNotBlank()) { auth.confirm(confirmSaveKey) { vm.saveKey(key); key = "" } }
                if (masked != null) {
                    TextButton({ auth.confirm(confirmRemoveKey) { vm.removeKey() } }) { Text(stringResource(R.string.connect_remove_key), color = Brand.Danger) }
                }
            }
        }
        item {
            NexioCard {
                BusyButton(stringResource(R.string.connect_test_connection), busy, enabled = masked != null) { vm.test() }
                Gap(4)
                Text(stringResource(R.string.connect_listing_free), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                Gap()
                OutlinedButton({ vm.makeDefault() }, Modifier.fillMaxWidth(), enabled = !current.isDefault) { Text(stringResource(if (current.isDefault) R.string.connect_default_provider else R.string.connect_make_default)) }
                Gap(4)
                Field(stringResource(R.string.connect_fallback_order), current.fallbackOrder.toString(), { v -> v.toIntOrNull()?.let(vm::setOrder) }, supporting = stringResource(R.string.connect_fallback_hint))
            }
        }
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.connect_usage_month))
                Gap(4)
                Text(stringResource(R.string.connect_usage_requests, usage.requests, usage.failedRequests), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                Text(
                    if (usage.unpricedRequests > 0) stringResource(R.string.connect_usage_total_unpriced, Format.money(usage.total), usage.unpricedRequests) else Format.money(usage.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nexio.colors.text,
                )
                Gap(4)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.connect_monthly_limit), limitText, { limitText = it }, Modifier.weight(1f))
                    TextButton({ vm.setLimit(limitText.toDoubleOrNull()) }) { Text(stringResource(R.string.connect_save)) }
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.connect_models))
            Gap(4)
            Field(stringResource(R.string.connect_filter), filter, { filter = it })
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.connect_add_by_identifier), manual, { manual = it }, Modifier.weight(1f))
                TextButton({ vm.addModel(manual); manual = "" }, enabled = manual.isNotBlank()) { Text(stringResource(R.string.connect_add)) }
            }
        }
        items(shown.size, key = { shown[it].id }) { i ->
            val model = shown[i]
            NexioCard(onClick = { editing = model }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(model.selected, { vm.toggle(model, it) })
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(if (model.inputPricePerMTok == null && model.pricePerImage == null && model.pricePerVideoSecond == null) R.string.connect_model_no_price else R.string.connect_model_price_set),
                            style = MaterialTheme.typography.labelSmall,
                            color = Nexio.colors.textMuted,
                        )
                    }
                }
            }
        }
        item {
            TextButton({ auth.confirm(confirmRemoveProvider) { vm.remove(onClose) } }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.connect_remove_provider), color = Brand.Danger) }
        }
    }
    editing?.let { model ->
        ModelDialog(model, { editing = null }) {
            vm.saveModel(it)
            editing = null
        }
    }
}

@Composable
private fun ModelDialog(model: AIModel, dismiss: () -> Unit, save: (AIModel) -> Unit) {
    var draft by remember(model) { mutableStateOf(model) }
    val fields = listOf<Triple<String, Double?, (Double?) -> AIModel>>(
        Triple(stringResource(R.string.connect_price_input), draft.inputPricePerMTok) { draft.copy(inputPricePerMTok = it) },
        Triple(stringResource(R.string.connect_price_output), draft.outputPricePerMTok) { draft.copy(outputPricePerMTok = it) },
        Triple(stringResource(R.string.connect_price_image), draft.pricePerImage) { draft.copy(pricePerImage = it) },
        Triple(stringResource(R.string.connect_price_video_second), draft.pricePerVideoSecond) { draft.copy(pricePerVideoSecond = it) },
        Triple(stringResource(R.string.connect_price_speech_chars), draft.pricePerMChar) { draft.copy(pricePerMChar = it) },
    )
    val flags = listOf<Triple<String, Boolean, (Boolean) -> AIModel>>(
        Triple(stringResource(R.string.connect_cap_text), draft.supportsText) { draft.copy(supportsText = it) },
        Triple(stringResource(R.string.connect_cap_image_analysis), draft.supportsVision) { draft.copy(supportsVision = it) },
        Triple(stringResource(R.string.connect_cap_image_generation), draft.supportsImage) { draft.copy(supportsImage = it) },
        Triple(stringResource(R.string.connect_cap_video_generation), draft.supportsVideo) { draft.copy(supportsVideo = it) },
        Triple(stringResource(R.string.connect_cap_speech), draft.supportsSpeech) { draft.copy(supportsSpeech = it) },
    )
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(model.modelId) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item { Text(stringResource(R.string.connect_model_pricing_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted) }
                items(flags.size) { i ->
                    val (label, value, set) = flags[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(value, { draft = set(it) })
                        Text(label, color = Nexio.colors.text)
                    }
                }
                items(fields.size) { i ->
                    val (label, value, set) = fields[i]
                    var text by remember(i) { mutableStateOf(value?.toString().orEmpty()) }
                    Field(label, text, { text = it; draft = set(it.toDoubleOrNull()) })
                }
                item { Field(stringResource(R.string.connect_currency), draft.currency, { draft = draft.copy(currency = it.uppercase().take(3)) }) }
            }
        },
        confirmButton = { TextButton({ save(draft) }) { Text(stringResource(R.string.connect_save)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.connect_cancel)) } },
    )
}
