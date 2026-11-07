package com.nexiolab.socialai.ui.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.ContentType
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.VideoMode
import com.nexiolab.socialai.domain.model.VideoProject
import com.nexiolab.socialai.domain.model.VideoStatus
import com.nexiolab.socialai.domain.model.VideoTransition
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.LiveField
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.EmptyState
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.components.StatusChip
import com.nexiolab.socialai.ui.media.MediaThumb
import com.nexiolab.socialai.ui.navigation.Routes
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import com.nexiolab.socialai.work.Jobs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.File

@Composable
private fun VideoStatus.label(): String = when (this) {
    VideoStatus.DRAFT -> stringResource(R.string.video_status_draft)
    VideoStatus.CONCEPT_READY -> stringResource(R.string.video_status_concept_ready)
    VideoStatus.APPROVED -> stringResource(R.string.video_status_approved)
    VideoStatus.GENERATING -> stringResource(R.string.video_status_generating)
    VideoStatus.RENDERING -> stringResource(R.string.video_status_rendering)
    VideoStatus.QC_REVIEW -> stringResource(R.string.video_status_qc_review)
    VideoStatus.READY -> stringResource(R.string.video_status_ready)
    VideoStatus.FAILED -> stringResource(R.string.video_status_failed)
}

@Composable
private fun VideoTransition.label(): String = when (this) {
    VideoTransition.NONE -> stringResource(R.string.video_transition_none)
    VideoTransition.FADE -> stringResource(R.string.video_transition_fade)
    VideoTransition.ZOOM -> stringResource(R.string.video_transition_zoom)
}

private fun VideoStatus.color() = when (this) {
    VideoStatus.READY -> Brand.Success
    VideoStatus.FAILED -> Brand.Danger
    VideoStatus.GENERATING, VideoStatus.RENDERING, VideoStatus.QC_REVIEW -> Brand.Accent
    else -> Brand.Warning
}

class VideoListViewModel(private val app: AppContainer) : BaseViewModel() {
    val projects = app.video.observeAll().state(emptyList())

    fun create(mode: VideoMode, then: (Long) -> Unit) = act {
        val now = System.currentTimeMillis()
        val name = app.context.getString(
            if (mode == VideoMode.FULL_AI) R.string.video_default_title_ai else R.string.video_default_title_photo,
        )
        then(app.video.save(VideoProject(title = name, mode = mode, createdAt = now, updatedAt = now)))
    }
}

@Composable
fun VideoScreen(open: (String) -> Unit) {
    val vm = appViewModel { VideoListViewModel(it) }
    Messages(vm)
    val projects by vm.projects.collectAsState()
    ScreenColumn {
        item {
            NexioCard {
                SectionHeader(stringResource(R.string.video_create_section))
                Gap(4)
                BusyButton(stringResource(R.string.video_create_ai_button), false) { vm.create(VideoMode.FULL_AI) { open(Routes.video(it)) } }
                Text(stringResource(R.string.video_create_ai_hint), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                Gap()
                BusyButton(stringResource(R.string.video_create_photo_button), false) { vm.create(VideoMode.PHOTO_BASED) { open(Routes.video(it)) } }
                Text(stringResource(R.string.video_create_photo_hint), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
            }
        }
        if (projects.isEmpty()) {
            item { EmptyState(Icons.Outlined.VideoLibrary, stringResource(R.string.video_empty_title), stringResource(R.string.video_empty_text)) }
        }
        items(projects.size, key = { projects[it].id }) { i ->
            val project = projects[i]
            NexioCard(onClick = { open(Routes.video(project.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(project.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                R.string.video_project_meta,
                                stringResource(if (project.mode == VideoMode.FULL_AI) R.string.video_mode_ai else R.string.video_mode_photo),
                                Format.date(project.updatedAt),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Nexio.colors.textMuted,
                        )
                    }
                    StatusChip(project.status.label(), project.status.color())
                }
            }
        }
    }
}

class VideoProjectViewModel(private val app: AppContainer, private val id: Long) : BaseViewModel() {
    val project = app.video.observe(id).state(null)
    val photos = app.video.observePhotos(id).state(emptyList())
    val music = MutableStateFlow<MediaFile?>(null)
    val voice = app.settings.observe(SettingKeys.VOICE).state(null)
    val output = project.flatMapLatest { p -> flowOf(p?.outputMediaId?.let { app.media.byId(it) }) }.state(null)

    fun path(file: MediaFile) = File(app.media.absolutePath(file))

    fun setVoice(value: String) = quiet { app.settings.set(SettingKeys.VOICE, value) }

    fun save(project: VideoProject) = quiet { app.video.save(project.copy(updatedAt = System.currentTimeMillis())) }

    /** Keeps the approved concept, so a failed render does not pay for the agents again. */
    fun retry() = act { project.value?.let { app.video.save(it.copy(status = VideoStatus.APPROVED, errorMessage = null)) } }

    fun addPhotos(uris: List<String>) = act {
        val existing = app.video.photos(id).map { it.id }
        val added = uris.map { app.media.import(it).id }
        app.video.setPhotos(id, (existing + added).distinct())
    }

    fun clearPhotos() = act { app.video.setPhotos(id, emptyList()) }

    fun setMusic(uri: String) = act {
        val file = app.media.import(uri)
        project.value?.let { app.video.save(it.copy(musicMediaId = file.id)) }
        music.value = file
    }

    fun concept() = act(app.context.getString(R.string.video_msg_agents_working)) {
        val current = project.value ?: return@act
        if (app.providers.providers().isEmpty()) throw IllegalStateException(app.context.getString(R.string.video_err_no_provider))
        if (current.mode == VideoMode.PHOTO_BASED && photos.value.isEmpty()) throw IllegalStateException(app.context.getString(R.string.video_err_choose_photo))
        app.video.save(current.copy(errorMessage = null))
        Jobs.videoConcept(app.context, id)
    }

    fun approve() = act(app.context.getString(R.string.video_msg_concept_approved)) {
        project.value?.let { app.video.save(it.copy(status = VideoStatus.APPROVED)) }
    }

    fun generate() = act(app.context.getString(R.string.video_msg_generation_started)) { Jobs.videoGenerate(app.context, id) }

    /** Saves the finished video as a post waiting for review. */
    fun usePost(then: (Long) -> Unit) = act {
        val current = project.value ?: return@act
        val now = System.currentTimeMillis()
        val itemId = app.content.save(
            ContentItem(
                title = current.title,
                description = current.objective,
                caption = current.captionText,
                type = ContentType.REEL,
                mediaId = current.outputMediaId,
                status = ContentStatus.REVIEW,
                agentRole = AgentRole.VIDEO,
                createdAt = now,
                updatedAt = now,
            ),
        )
        then(itemId)
    }

    fun delete(then: () -> Unit) = act {
        project.value?.let { app.video.delete(it) }
        then()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Nexio.colors.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { FilterChip(it == selected, { onSelect(it) }, { Text(name(it)) }) }
    }
}

@Composable
fun VideoProjectScreen(id: Long, open: (String) -> Unit, onClose: () -> Unit) {
    val vm = appViewModel(key = "video$id") { VideoProjectViewModel(it, id) }
    Messages(vm)
    val loaded by vm.project.collectAsState()
    val photos by vm.photos.collectAsState()
    val output by vm.output.collectAsState()
    val busy by vm.busy.collectAsState()
    val voice by vm.voice.collectAsState()
    val project = loaded ?: return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) vm.addPhotos(uris.map { it.toString() })
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.setMusic(it.toString()) } }
    val working = project.status == VideoStatus.GENERATING || project.status == VideoStatus.RENDERING || project.status == VideoStatus.QC_REVIEW
    val photoMode = project.mode == VideoMode.PHOTO_BASED
    val transitionLabels = VideoTransition.entries.associateWith { it.label() }
    ScreenColumn {
        item {
            NexioCard {
                StatusChip(project.status.label(), project.status.color())
                project.errorMessage?.let { Gap(4); Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Danger) }
                Gap()
                LiveField(stringResource(R.string.video_field_title), project.title, { title -> vm.save(vm.project.value!!.copy(title = title)) })
                LiveField(stringResource(R.string.video_field_objective), project.objective, { text -> vm.save(vm.project.value!!.copy(objective = text)) }, singleLine = false)
            }
        }
        item {
            NexioCard {
                Choice(stringResource(R.string.video_section_format), listOf(9 to 16, 1 to 1, 16 to 9), project.aspectWidth to project.aspectHeight, { "${it.first}:${it.second}" }) {
                    vm.save(project.copy(aspectWidth = it.first, aspectHeight = it.second))
                }
                Gap()
                Choice(stringResource(R.string.video_section_duration), listOf(5, 8, 15, 30, 60), project.durationSeconds, { it.toString() }) { vm.save(project.copy(durationSeconds = it)) }
                Gap()
                if (photoMode) {
                    Gap()
                    Choice(stringResource(R.string.video_section_resolution), listOf(720, 1080), project.shortSidePx, { "${it}p" }) { vm.save(project.copy(shortSidePx = it)) }
                    Gap()
                    Choice(stringResource(R.string.video_section_frame_rate), listOf(24, 30), project.frameRate, { "$it fps" }) { vm.save(project.copy(frameRate = it)) }
                    Gap()
                    Choice(stringResource(R.string.video_section_transitions), VideoTransition.entries, project.transition, { transitionLabels.getValue(it) }) { vm.save(project.copy(transition = it)) }
                }
            }
        }
        if (photoMode) {
            item {
                NexioCard {
                    SectionHeader(
                        stringResource(R.string.video_photos_count, photos.size),
                        action = if (photos.isEmpty()) null else stringResource(R.string.video_action_clear),
                    ) { vm.clearPhotos() }
                    if (photos.isNotEmpty()) {
                        Gap(4)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            photos.forEach { MediaThumb(it, vm.path(it), Modifier.size(72.dp)) }
                        }
                    }
                    Gap(4)
                    OutlinedButton(
                        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.video_button_choose_photos)) }
                }
            }
            item {
                NexioCard {
                    LiveField(stringResource(R.string.video_field_caption), project.captionText, { text -> vm.save(vm.project.value!!.copy(captionText = text)) })
                    Gap(4)
                    LiveField(
                        stringResource(R.string.video_field_voiceover),
                        project.voiceoverText,
                        { text -> vm.save(vm.project.value!!.copy(voiceoverText = text)) },
                        singleLine = false,
                        supporting = stringResource(R.string.video_voiceover_hint),
                    )
                    Gap(4)
                    LiveField(
                        stringResource(R.string.video_field_voice),
                        voice.orEmpty(),
                        { value -> vm.setVoice(value) },
                        supporting = stringResource(R.string.video_voice_hint),
                    )
                    Gap(4)
                    OutlinedButton({ musicPicker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth()) {
                        Text(
                            if (project.musicMediaId == null) stringResource(R.string.video_button_add_music) else stringResource(R.string.video_button_change_music),
                        )
                    }
                    Text(stringResource(R.string.video_music_rights), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                }
            }
        }
        if (project.script.isNotBlank()) {
            item {
                NexioCard {
                    SectionHeader(if (photoMode) stringResource(R.string.video_section_concept) else stringResource(R.string.video_section_script))
                    SelectionContainer { Text(project.script, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text) }
                    Gap()
                    SectionHeader(stringResource(R.string.video_section_storyboard))
                    SelectionContainer { Text(project.storyboard, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text) }
                }
            }
        }
        if (project.qualityNotes.isNotBlank()) {
            item {
                NexioCard {
                    Text(stringResource(R.string.video_section_quality), style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
                    Text(project.qualityNotes, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text)
                }
            }
        }
        output?.let { file ->
            item {
                NexioCard {
                    SectionHeader(stringResource(R.string.video_section_finished))
                    Text(
                        stringResource(R.string.video_file_meta, file.displayName, Format.bytes(file.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Nexio.colors.textMuted,
                    )
                    Gap(4)
                    Button({ vm.usePost { open(Routes.content(it)) } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_button_use_in_post)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (project.status) {
                    VideoStatus.DRAFT, VideoStatus.FAILED -> {
                        if (project.status == VideoStatus.FAILED && project.script.isNotBlank()) {
                            BusyButton(stringResource(R.string.video_button_retry), busy) { vm.retry() }
                        }
                        BusyButton(stringResource(R.string.video_button_create_concept), busy, enabled = project.objective.isNotBlank()) { vm.concept() }
                    }
                    VideoStatus.CONCEPT_READY -> {
                        BusyButton(stringResource(R.string.video_button_approve_concept), busy) { vm.approve() }
                        OutlinedButton({ vm.concept() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_button_new_concept)) }
                    }
                    VideoStatus.APPROVED -> BusyButton(
                        stringResource(if (photoMode) R.string.video_button_render_phone else R.string.video_button_generate_provider),
                        busy,
                    ) { vm.generate() }
                    VideoStatus.READY -> OutlinedButton({ vm.concept() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_button_start_over)) }
                    else -> Text(stringResource(R.string.video_working_notice), color = Nexio.colors.textMuted)
                }
                if (!working) TextButton({ vm.delete(onClose) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.video_button_delete), color = Brand.Danger)
                }
            }
        }
    }
}

private const val MAX_PHOTOS = 20
