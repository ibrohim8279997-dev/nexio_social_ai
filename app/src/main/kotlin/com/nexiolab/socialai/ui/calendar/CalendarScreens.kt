package com.nexiolab.socialai.ui.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.content.ContentWorkflow
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.ContentType
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.MediaSource
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.BusyButton
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
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
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle

fun statusColor(status: ContentStatus): Color = when (status) {
    ContentStatus.DRAFT -> Color(0xFF91A4BC)
    ContentStatus.REVIEW -> Brand.Warning
    ContentStatus.APPROVED -> Brand.Accent
    ContentStatus.SCHEDULED -> Brand.Success
    ContentStatus.PUBLISHING -> Brand.Primary
    ContentStatus.PUBLISHED -> Brand.Success
    ContentStatus.FAILED -> Brand.Danger
}

class CalendarViewModel(private val app: AppContainer) : BaseViewModel() {
    private val zone = ZoneId.systemDefault()
    val month = MutableStateFlow(YearMonth.now())

    val items = month.flatMapLatest { m ->
        app.content.observeBetween(
            m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }.state(emptyList())

    fun day(item: ContentItem): LocalDate? = item.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun generate() = act(app.context.getString(R.string.plan_cal_generating)) {
        if (app.providers.providers().isEmpty()) throw AiException(AiError.NOT_CONFIGURED, app.context.getString(R.string.plan_no_provider))
        val brand = app.brand.get()
        if (brand?.interviewCompletedAt == null) throw AiException(AiError.NOT_CONFIGURED, app.context.getString(R.string.plan_cal_interview_first))
        Jobs.monthPlan(app.context, month.value)
    }
}

@Composable
fun CalendarScreen(open: (String) -> Unit) {
    val vm = appViewModel { CalendarViewModel(it) }
    val locale = LocalConfiguration.current.locales[0]
    Messages(vm)
    val month by vm.month.collectAsState()
    val items by vm.items.collectAsState()
    val busy by vm.busy.collectAsState()
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var weekView by rememberSaveable { mutableStateOf(false) }
    val selectedDay = LocalDate.parse(selected)
    val byDay = items.groupBy(vm::day)
    ScreenColumn {
        item {
            NexioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.month.value = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.plan_cal_prev_month)) }
                    Text(
                        month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + month.year,
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        color = Nexio.colors.text,
                    )
                    IconButton({ vm.month.value = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.plan_cal_next_month)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!weekView, { weekView = false }, { Text(stringResource(R.string.plan_cal_month)) })
                    FilterChip(weekView, { weekView = true }, { Text(stringResource(R.string.plan_cal_week)) })
                }
                Gap()
                DayGrid(month, selectedDay, weekView, byDay) { selected = it.toString() }
            }
        }
        item { SectionHeader(if (weekView) stringResource(R.string.plan_cal_week_posts) else Format.date(selectedDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()), action = stringResource(R.string.plan_cal_new_post)) { open(Routes.content(0)) } }
        val shown = if (weekView) {
            val start = selectedDay.with(DayOfWeek.MONDAY)
            items.filter { vm.day(it)?.let { d -> !d.isBefore(start) && d.isBefore(start.plusDays(7)) } == true }
        } else {
            byDay[selectedDay].orEmpty()
        }
        if (shown.isEmpty()) {
            item { Text(stringResource(R.string.plan_cal_no_posts), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted) }
        }
        items(shown.size, key = { shown[it].id }) { i -> ContentRow(shown[i]) { open(Routes.content(shown[i].id)) } }
        item { BusyButton(stringResource(R.string.plan_cal_generate), busy) { vm.generate() } }
    }
}

@Composable
private fun DayGrid(month: YearMonth, selected: LocalDate, weekView: Boolean, byDay: Map<LocalDate?, List<ContentItem>>, onSelect: (LocalDate) -> Unit) {
    val firstCell = if (weekView) selected.with(DayOfWeek.MONDAY) else month.atDay(1).with(DayOfWeek.MONDAY)
    val locale = LocalConfiguration.current.locales[0]
    val weeks = if (weekView) 1 else ((month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7)
    Row {
        DayOfWeek.entries.forEach {
            Text(it.getDisplayName(TextStyle.SHORT, locale), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
        }
    }
    repeat(weeks) { week ->
        Row {
            repeat(7) { d ->
                val day = firstCell.plusDays((week * 7 + d).toLong())
                val inMonth = YearMonth.from(day) == month
                val isSelected = day == selected
                val posts = byDay[day].orEmpty()
                Column(
                    Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).clickable { onSelect(day) }
                        .then(if (isSelected) Modifier.background(Brand.Primary) else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (day == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isSelected -> Color.White
                            inMonth -> Nexio.colors.text
                            else -> Nexio.colors.textMuted.copy(alpha = 0.5f)
                        },
                    )
                    Box(Modifier.size(5.dp).background(if (posts.isNotEmpty()) statusColor(posts.first().status) else Color.Transparent, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun ContentRow(item: ContentItem, onClick: () -> Unit) {
    NexioCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.scheduledAt?.let(Format::dateTime), item.type.label).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Nexio.colors.textMuted,
                )
            }
            StatusChip(item.status.label, statusColor(item))
        }
    }
}

private fun statusColor(item: ContentItem) = statusColor(item.status)

class ContentViewModel(private val app: AppContainer, private val id: Long) : BaseViewModel() {
    val draft = MutableStateFlow<ContentItem?>(null)
    val mediaName = MutableStateFlow<com.nexiolab.socialai.domain.model.MediaFile?>(null)
    private var stored: ContentItem? = null

    /** A carousel's images in the owner's order; unused for every other type. */
    val carousel = MutableStateFlow<List<MediaFile>>(emptyList())
    val library = app.media.observeAll().state(emptyList())
    private var storedCarousel: List<Long> = emptyList()

    init {
        act {
            val item = if (id == 0L) {
                val now = System.currentTimeMillis()
                ContentItem(title = "", status = ContentStatus.DRAFT, agentRole = AgentRole.CONTENT, createdAt = now, updatedAt = now)
            } else {
                app.content.item(id) ?: throw IllegalStateException(app.context.getString(R.string.plan_post_missing))
            }
            stored = item
            draft.value = item
            loadMedia(item.mediaId)
            val images = if (item.id != 0L) app.content.carouselMedia(item.id) else emptyList()
            carousel.value = images
            storedCarousel = images.map { it.id }
        }
    }

    /** Adds library images at the end, in the order given, up to the carousel's limit; non-images are never added. */
    private fun addImages(files: List<MediaFile>) {
        val have = carousel.value.map { it.id }.toSet()
        val fresh = files.filter { it.kind == MediaKind.IMAGE && it.id !in have }.distinctBy { it.id }
        val room = ContentWorkflow.CAROUSEL_MAX - carousel.value.size
        carousel.value = carousel.value + fresh.take(room.coerceAtLeast(0))
        if (fresh.size > room) messages.tryEmit(app.context.getString(R.string.plan_carousel_limit))
    }

    fun toggleImage(file: MediaFile) {
        if (carousel.value.any { it.id == file.id }) removeImage(carousel.value.indexOfFirst { it.id == file.id }) else addImages(listOf(file))
    }

    fun importImages(uris: List<String>) = act(app.context.getString(R.string.plan_carousel_images_added)) {
        addImages(uris.take(ContentWorkflow.CAROUSEL_MAX).map { app.media.import(it) })
    }

    fun moveImage(index: Int, by: Int) {
        val list = carousel.value.toMutableList()
        val to = index + by
        if (index !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(index))
        carousel.value = list
    }

    fun removeImage(index: Int) {
        carousel.value = carousel.value.filterIndexed { i, _ -> i != index }
    }

    private suspend fun loadMedia(mediaId: Long?) { mediaName.value = mediaId?.let { app.media.byId(it) } }

    fun path(file: com.nexiolab.socialai.domain.model.MediaFile) = File(app.media.absolutePath(file))

    fun update(item: ContentItem) { draft.value = item }

    private suspend fun persist(item: ContentItem): ContentItem {
        val before = stored
        val images = carousel.value.map { it.id }
        val imagesChanged = images != storedCarousel
        // Changing the images or their order after approval withdraws approval, like a caption edit.
        val next = if (before != null) ContentWorkflow.edited(before, item, carouselChanged = imagesChanged) else item
        val saved = next.copy(id = app.content.save(next))
        if (imagesChanged) {
            app.content.setCarouselMedia(saved.id, images)
            storedCarousel = images
        }
        stored = saved
        draft.value = saved
        return saved
    }

    fun save() = act(app.context.getString(R.string.plan_saved)) { draft.value?.let { persist(it) } }

    fun approve() = act(app.context.getString(R.string.plan_post_approved)) {
        val saved = persist(draft.value ?: return@act)
        val approved = ContentWorkflow.approve(saved, System.currentTimeMillis(), carousel.value)
        persist(approved)
        app.activity.log(app.context.getString(R.string.plan_post_log_approved), approved.title)
    }

    fun schedule() = act(app.context.getString(R.string.plan_post_scheduled)) {
        val saved = persist(draft.value ?: return@act)
        val scheduled = persist(ContentWorkflow.schedule(saved, System.currentTimeMillis(), carousel.value))
        Jobs.schedulePost(app.context, scheduled.id, scheduled.scheduledAt ?: return@act)
        app.activity.log(app.context.getString(R.string.plan_post_log_scheduled), saved.title)
    }

    fun unschedule() = act(app.context.getString(R.string.plan_post_unscheduled)) {
        val item = persist(ContentWorkflow.unschedule(draft.value ?: return@act))
        Jobs.cancelScheduled(app.context, item.id)
    }

    fun publishNow(then: () -> Unit) = act(app.context.getString(R.string.plan_post_publishing_started)) {
        // What is on screen is what gets published; a changed carousel loses its approval here, like a changed caption.
        val item = persist(draft.value ?: return@act)
        ContentWorkflow.problemForPublishing(item, carousel.value)?.let { throw ContentWorkflow.Blocked(it) }
        Jobs.publishNow(app.context, item.id)
        then()
    }

    fun attach(uri: String) = act(app.context.getString(R.string.plan_post_media_attached)) {
        val media = app.media.import(uri)
        mediaName.value = media
        draft.value = draft.value?.copy(mediaId = media.id)
    }

    fun write(idea: String) = act(app.context.getString(R.string.plan_post_written)) {
        val current = draft.value ?: return@act
        val written = app.planner.draftPost(idea, current.type, current.scheduledAt)
        stored = written
        draft.value = written.copy(mediaId = current.mediaId)
    }

    fun generateImage() = act(app.context.getString(R.string.plan_post_image_generated)) {
        val current = draft.value ?: return@act
        val task = app.engine.runSpecialist(
            AgentRole.VISUAL,
            app.context.getString(R.string.plan_task_image_for, current.title),
            "Write one precise image-generation prompt for this post. Answer with the prompt only.\nTitle: ${current.title}\nCaption: ${current.caption}",
        )
        val image = app.gateway.image(AgentRole.VISUAL, task.output.trim(), 1, 1)
        val media = app.media.store(image.bytes, MediaKind.IMAGE, image.mimeType, "${current.title.take(30)}.png", MediaSource.AI_GENERATED)
        mediaName.value = media
        draft.value = draft.value?.copy(mediaId = media.id)
    }

    fun delete(then: () -> Unit) = act {
        stored?.takeIf { it.id != 0L }?.let { app.content.delete(it) }
        then()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeButton(value: Long?, enabled: Boolean, onChange: (Long) -> Unit) {
    var step by remember { mutableStateOf(0) }
    val zone = ZoneId.systemDefault()
    val date = rememberDatePickerState(initialSelectedDateMillis = value)
    val initial = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(10, 0)
    val time = rememberTimePickerState(initial.hour, initial.minute, true)
    OutlinedButton({ step = 1 }, Modifier.fillMaxWidth(), enabled = enabled) { Text(value?.let(Format::dateTime) ?: stringResource(R.string.plan_post_choose_datetime)) }
    if (step == 1) {
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = { TextButton({ step = if (date.selectedDateMillis != null) 2 else 0 }) { Text(stringResource(R.string.plan_post_next)) } },
        ) { DatePicker(date) }
    }
    if (step == 2) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton({
                    val day = Instant.ofEpochMilli(date.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    onChange(day.atTime(time.hour, time.minute).atZone(zone).toInstant().toEpochMilli())
                    step = 0
                }) { Text(stringResource(R.string.plan_post_set)) }
            },
        )
    }
}

@Composable
fun ContentScreen(id: Long, onClose: () -> Unit, publish: (Long) -> Unit) {
    val vm = appViewModel(key = "content$id") { ContentViewModel(it, id) }
    Messages(vm)
    val draft by vm.draft.collectAsState()
    val media by vm.mediaName.collectAsState()
    val busy by vm.busy.collectAsState()
    val item = draft ?: return
    var idea by rememberSaveable { mutableStateOf("") }
    var typeMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attach(it.toString()) } }
    val editable = ContentWorkflow.canEdit(item.status)
    ScreenColumn {
        item {
            NexioCard {
                StatusChip(item.status.label, statusColor(item.status))
                item.errorMessage?.let { Gap(4); Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Danger) }
                if (item.qualityNotes.isNotBlank()) {
                    Gap(4)
                    Text(stringResource(R.string.plan_post_quality_control, item.qualityNotes), style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
                }
                Gap(4)
                Text(stringResource(R.string.plan_post_responsible, item.agentRole.title), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
            }
        }
        if (id == 0L && editable) {
            item {
                NexioCard {
                    SectionHeader(stringResource(R.string.plan_post_write_title))
                    Field(stringResource(R.string.plan_post_idea), idea, { idea = it }, singleLine = false)
                    Gap(4)
                    BusyButton(stringResource(R.string.plan_post_write), busy, enabled = idea.isNotBlank()) { vm.write(idea) }
                }
            }
        }
        item {
            NexioCard {
                Field(stringResource(R.string.plan_post_title), item.title, { vm.update(item.copy(title = it)) })
                Gap(4)
                Field(stringResource(R.string.plan_post_description), item.description, { vm.update(item.copy(description = it)) }, singleLine = false)
                Gap(4)
                Field(stringResource(R.string.plan_post_caption), item.caption, { vm.update(item.copy(caption = it)) }, singleLine = false, supporting = "${item.caption.length} / 2200")
                Gap(4)
                Field(stringResource(R.string.plan_post_hashtags), item.hashtags, { vm.update(item.copy(hashtags = it)) })
                Gap(4)
                Box {
                    OutlinedButton({ typeMenu = true }, Modifier.fillMaxWidth(), enabled = editable) { Text(stringResource(R.string.plan_post_type, item.type.label)) }
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        ContentType.entries.forEach { type ->
                            DropdownMenuItem({ Text(type.label) }, { vm.update(item.copy(type = type)); typeMenu = false })
                        }
                    }
                }
                Gap(4)
                DateTimeButton(item.scheduledAt, editable) { vm.update(item.copy(scheduledAt = it)) }
            }
        }
        if (item.type == ContentType.CAROUSEL) {
            item { CarouselEditor(vm, editable) }
        } else item {
            NexioCard {
                SectionHeader(stringResource(R.string.plan_post_media))
                media?.let {
                    Gap(4)
                    MediaThumb(it, vm.path(it), Modifier.fillMaxWidth().aspectRatio(1.6f))
                }
                Gap(4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, Modifier.weight(1f), enabled = editable) { Text(stringResource(R.string.plan_post_choose)) }
                    OutlinedButton({ vm.generateImage() }, Modifier.weight(1f), enabled = editable && !busy && item.title.isNotBlank()) { Text(stringResource(R.string.plan_post_generate)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editable) BusyButton(stringResource(R.string.plan_save), busy, enabled = item.title.isNotBlank()) { vm.save() }
                when (item.status) {
                    ContentStatus.DRAFT, ContentStatus.REVIEW, ContentStatus.FAILED ->
                        Button({ vm.approve() }, Modifier.fillMaxWidth(), enabled = !busy && item.title.isNotBlank()) { Text(stringResource(R.string.plan_post_approve)) }
                    ContentStatus.APPROVED -> {
                        Button({ vm.schedule() }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.plan_post_schedule)) }
                        OutlinedButton({ vm.publishNow { publish(item.id) } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.plan_post_publish_now)) }
                    }
                    ContentStatus.SCHEDULED -> OutlinedButton({ vm.unschedule() }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.plan_post_cancel_schedule)) }
                    else -> Unit
                }
                if (item.id != 0L && editable) TextButton({ vm.delete(onClose) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_post_delete), color = Brand.Danger) }
            }
        }
    }
}

/** Picks 2 to 10 library images and puts them in order. Changes count as edits: approval is withdrawn when saved. */
@Composable
private fun CarouselEditor(vm: ContentViewModel, editable: Boolean) {
    val images by vm.carousel.collectAsState()
    val library by vm.library.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ContentWorkflow.CAROUSEL_MAX)) { uris ->
        if (uris.isNotEmpty()) vm.importImages(uris.map { it.toString() })
    }
    val full = images.size >= ContentWorkflow.CAROUSEL_MAX
    NexioCard {
        SectionHeader(stringResource(R.string.plan_carousel_title))
        Text(stringResource(R.string.plan_carousel_hint), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
        Gap(4)
        val valid = images.size in ContentWorkflow.CAROUSEL_MIN..ContentWorkflow.CAROUSEL_MAX
        Text(
            stringResource(R.string.plan_carousel_count, images.size),
            style = MaterialTheme.typography.labelMedium,
            color = if (valid) Nexio.colors.text else Brand.Warning,
        )
        images.forEachIndexed { index, file ->
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MediaThumb(file, vm.path(file), Modifier.size(52.dp))
                Text("${index + 1}. ${file.displayName}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ vm.moveImage(index, -1) }, enabled = editable && index > 0) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.plan_carousel_up)) }
                IconButton({ vm.moveImage(index, 1) }, enabled = editable && index < images.lastIndex) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.plan_carousel_down)) }
                IconButton({ vm.removeImage(index) }, enabled = editable) { Icon(Icons.Filled.Close, stringResource(R.string.plan_carousel_remove)) }
            }
        }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ picking = true }, Modifier.weight(1f), enabled = editable && !full) { Text(stringResource(R.string.plan_carousel_add)) }
            OutlinedButton(
                { phone.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f),
                enabled = editable && !full,
            ) { Text(stringResource(R.string.plan_carousel_from_phone)) }
        }
    }
    if (picking) {
        val choices = library.filter { it.kind == MediaKind.IMAGE }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.plan_carousel_pick_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (choices.isEmpty()) Text(stringResource(R.string.plan_carousel_library_empty))
                    choices.forEach { file ->
                        val chosen = images.any { it.id == file.id }
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleImage(file) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(chosen, { vm.toggleImage(file) })
                            MediaThumb(file, vm.path(file), Modifier.size(44.dp))
                            Text(file.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ picking = false }) { Text(stringResource(R.string.plan_carousel_pick_done)) } },
        )
    }
}
