package com.nexiolab.socialai.ui.brand

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.brand.BrandField
import com.nexiolab.socialai.domain.brand.BrandFields
import com.nexiolab.socialai.domain.model.BrandProfile
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.NexioProgressBar
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.theme.Nexio
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.YearMonth

class BrandViewModel(private val app: AppContainer) : BaseViewModel() {
    val saved = app.brand.observe().state(null)

    val logo = saved.map { profile -> profile?.logoMediaId?.let { app.media.byId(it) } }.state(null)

    suspend fun loadProfile() = app.brand.get() ?: BrandProfile()

    fun logoPath(media: MediaFile) = File(app.media.absolutePath(media))

    fun save(profile: BrandProfile, done: String? = app.context.getString(R.string.shell_saved), then: () -> Unit = {}) = act(done) {
        app.brand.save(profile)
        then()
    }

    fun importLogo(uri: String, profile: BrandProfile) = act(app.context.getString(R.string.shell_logo_added)) {
        val media = app.media.import(uri)
        app.brand.save(profile.copy(logoMediaId = media.id))
    }

    fun importFiles(uris: List<String>) = act(app.context.getString(R.string.shell_files_added, uris.size)) {
        uris.forEach { app.media.import(it) }
    }

    fun finishInterview(profile: BrandProfile, then: () -> Unit) = act {
        val now = System.currentTimeMillis()
        app.brand.save(profile.copy(interviewCompletedAt = now, lastMonthlyReview = YearMonth.now().toString()))
        app.activity.log(app.context.getString(R.string.shell_log_interview_finished), profile.businessName)
        then()
    }

    fun finishMonthly(profile: BrandProfile, changed: Boolean, then: () -> Unit) = act {
        app.brand.save(profile.copy(lastMonthlyReview = YearMonth.now().toString()))
        app.activity.log(
            app.context.getString(R.string.shell_log_monthly_done),
            app.context.getString(if (changed) R.string.shell_log_details_updated else R.string.shell_nothing_changed),
        )
        then()
    }
}

@Composable
private fun rememberDraft(vm: BrandViewModel): Pair<BrandProfile?, (BrandProfile) -> Unit> {
    var draft by remember { mutableStateOf<BrandProfile?>(null) }
    LaunchedEffect(Unit) { draft = vm.loadProfile() }
    val stored by vm.saved.collectAsState()
    LaunchedEffect(stored) { if (draft != null && stored != null) draft = draft?.copy(logoMediaId = stored?.logoMediaId) }
    return draft to { draft = it }
}

@Composable
private fun LogoPicker(vm: BrandViewModel, profile: BrandProfile) {
    val logo by vm.logo.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.importLogo(it.toString(), profile) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        logo?.let {
            AsyncImage(vm.logoPath(it), stringResource(R.string.shell_logo), Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
        }
        OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(stringResource(if (logo == null) R.string.shell_add_logo else R.string.shell_change_logo))
        }
    }
}

@Composable
fun BrandScreen() {
    val vm = appViewModel { BrandViewModel(it) }
    Messages(vm)
    val busy by vm.busy.collectAsState()
    val (draft, update) = rememberDraft(vm)
    val profile = draft ?: return
    ScreenColumn {
        item { NexioCard { SectionHeader(stringResource(R.string.shell_logo)); Gap(); LogoPicker(vm, profile) } }
        BrandFields.sections.forEach { section ->
            item(key = section) {
                NexioCard {
                    SectionHeader(section)
                    BrandFields.all.filter { it.section == section }.forEach { field ->
                        Field(field.label, field.read(profile), { update(field.write(profile, it)) }, singleLine = field.singleLine)
                        Gap(4)
                    }
                }
            }
        }
        item { BusyButton(stringResource(R.string.shell_save_brand_profile), busy) { vm.save(profile) } }
    }
}

/** First-launch interview: one question at a time, stored after each step so nothing is lost. */
@Composable
fun InterviewScreen(onDone: () -> Unit) {
    val vm = appViewModel { BrandViewModel(it) }
    Messages(vm)
    val (draft, update) = rememberDraft(vm)
    val profile = draft ?: return
    var step by rememberSaveable { mutableIntStateOf(0) }
    val uploadStep = BrandFields.all.size
    val uploads = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris.map { it.toString() })
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        NexioProgressBar((step + 1f) / (uploadStep + 1))
        Gap(4)
        Text(stringResource(R.string.shell_step_of, step + 1, uploadStep + 1), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
        Gap(16)
        if (step < uploadStep) {
            val field: BrandField = BrandFields.all[step]
            Text(field.question, style = MaterialTheme.typography.headlineSmall, color = Nexio.colors.text)
            Gap(16)
            if (field.id == "colors") {
                LogoPicker(vm, profile)
                Gap(12)
            }
            Field(field.label, field.read(profile), { update(field.write(profile, it)) }, singleLine = field.singleLine, supporting = stringResource(if (field.required) R.string.shell_required else R.string.shell_optional))
        } else {
            Text(stringResource(R.string.shell_existing_material), style = MaterialTheme.typography.headlineSmall, color = Nexio.colors.text)
            Gap(8)
            Text(
                stringResource(R.string.shell_existing_material_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Nexio.colors.textMuted,
            )
            Gap(16)
            OutlinedButton({ uploads.launch(arrayOf("image/*", "video/*", "application/pdf")) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.shell_choose_files)) }
        }
        Gap(24)
        val current = BrandFields.all.getOrNull(step)
        val blocked = current?.required == true && current.read(profile).isBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) TextButton({ step-- }) { Text(stringResource(R.string.shell_back)) }
            Button(
                onClick = {
                    if (step < uploadStep) {
                        vm.save(profile, done = null) { }
                        step++
                    } else {
                        vm.finishInterview(profile, onDone)
                    }
                },
                enabled = !blocked,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (step < uploadStep) R.string.shell_next else R.string.shell_finish)) }
        }
        val missing = BrandFields.missingRequired(profile)
        if (step == uploadStep && missing.isNotEmpty()) {
            Gap()
            Text(stringResource(R.string.shell_still_empty, missing.joinToString { it.label }), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
        }
    }
}

/** Monthly check-in: shows what we hold and only stores what the owner changes. */
@Composable
fun MonthlyScreen(onDone: () -> Unit) {
    val vm = appViewModel { BrandViewModel(it) }
    Messages(vm)
    val busy by vm.busy.collectAsState()
    val (draft, update) = rememberDraft(vm)
    val stored by vm.saved.collectAsState()
    val profile = draft ?: return
    val changed = stored != null && profile.copy(updatedAt = 0, logoMediaId = null) != stored?.copy(updatedAt = 0, logoMediaId = null)
    ScreenColumn {
        item {
            Text(stringResource(R.string.shell_monthly_question), style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text)
        }
        item {
            BusyButton(stringResource(if (changed) R.string.shell_save_changes else R.string.shell_nothing_changed), busy) { vm.finishMonthly(profile, changed, onDone) }
        }
        BrandFields.all.forEach { field ->
            item(key = field.id) {
                Field(field.label, field.read(profile), { update(field.write(profile, it)) }, singleLine = field.singleLine)
            }
        }
    }
}
