package com.nexiolab.socialai.domain.video

import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.domain.agents.AgentEngine
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.ai.AiGateway
import com.nexiolab.socialai.domain.ai.ImageInput
import com.nexiolab.socialai.domain.ai.VideoJob
import com.nexiolab.socialai.domain.ai.VideoJobState
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.model.MediaSource
import com.nexiolab.socialai.domain.model.TaskStatus
import com.nexiolab.socialai.domain.model.VideoMode
import com.nexiolab.socialai.domain.model.VideoProject
import com.nexiolab.socialai.domain.model.VideoStatus
import com.nexiolab.socialai.domain.model.VideoTransition
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.MediaRepository
import com.nexiolab.socialai.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

/** Everything the local editor needs. Local editing assembles media; it does not generate footage. */
data class RenderSpec(
    val photos: List<String>,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationSeconds: Int,
    val transition: VideoTransition,
    val caption: String,
    val voiceoverPath: String?,
    val musicPath: String?,
)

interface VideoRenderer {
    /** Renders to a temporary MP4 and returns its path. */
    suspend fun render(spec: RenderSpec, onProgress: (Int) -> Unit = {}): String
}

interface VideoRepository {
    fun observeAll(): Flow<List<VideoProject>>
    fun observe(id: Long): Flow<VideoProject?>
    fun observePhotos(id: Long): Flow<List<MediaFile>>
    suspend fun byId(id: Long): VideoProject?
    suspend fun save(project: VideoProject): Long
    suspend fun delete(project: VideoProject)
    suspend fun photos(id: Long): List<MediaFile>
    suspend fun setPhotos(id: Long, mediaIds: List<Long>)
}

fun VideoProject.outputSize(): Pair<Int, Int> {
    val longSide = shortSidePx * maxOf(aspectWidth, aspectHeight) / minOf(aspectWidth, aspectHeight)
    return if (aspectWidth >= aspectHeight) (longSide + longSide % 2) to shortSidePx else shortSidePx to (longSide + longSide % 2)
}

/** The two Video Studio workflows from the product spec, stopping at the owner's approval. */
class VideoPipeline(
    private val engine: AgentEngine,
    private val gateway: AiGateway,
    private val videos: VideoRepository,
    private val media: MediaRepository,
    private val renderer: VideoRenderer,
    private val settings: SettingsRepository,
    private val activity: ActivityRepository,
    /** Must return an image small enough to send to a provider, not the original file. */
    private val loadForAnalysis: (String) -> ImageInput,
    private val pollMillis: Long = 10_000,
) {
    private suspend fun update(id: Long, change: (VideoProject) -> VideoProject): VideoProject {
        val current = videos.byId(id) ?: throw AiException(AiError.INVALID_REQUEST, Msg.PROJECT_GONE())
        val next = change(current).copy(updatedAt = System.currentTimeMillis())
        videos.save(next)
        return next
    }

    private fun format(p: VideoProject) = "${p.durationSeconds} seconds, ${p.aspectWidth}:${p.aspectHeight}"

    suspend fun prepareConcept(id: Long) {
        val project = videos.byId(id) ?: return
        try {
            if (project.mode == VideoMode.FULL_AI) fullAiConcept(project) else photoConcept(project)
            update(id) { it.copy(status = VideoStatus.CONCEPT_READY, errorMessage = null) }
            activity.log(Msg.LOG_VIDEO_CONCEPT_READY(), project.title)
        } catch (e: AiException) {
            update(id) { it.copy(status = VideoStatus.FAILED, errorMessage = e.message) }
            throw e
        }
    }

    private suspend fun fullAiConcept(project: VideoProject) {
        val id = project.id
        val script = engine.runSpecialist(
            AgentRole.VIDEO, Msg.TASK_VIDEO_SCRIPT(project.title),
            "Write a video script for this objective: ${project.objective}. Format: ${format(project)}.",
        )
        val check = engine.runSpecialist(
            AgentRole.MARKETING, Msg.TASK_OBJECTIVE_CHECK(project.title),
            "Check that this script serves the objective \"${project.objective}\". State what to change, briefly.\n\n${script.output}",
        )
        val visual = engine.runSpecialist(
            AgentRole.VISUAL, Msg.TASK_VISUAL_DIRECTION(project.title),
            "Prepare the visual direction for this video.\n\nScript:\n${script.output}\n\nMarketing notes:\n${check.output}",
        )
        val scenes = engine.runSpecialist(
            AgentRole.VIDEO, Msg.TASK_SCENES(project.title),
            "Prepare the scene list and one generation prompt for a video provider. Format: ${format(project)}.\n\n" +
                "Script:\n${script.output}\n\nVisual direction:\n${visual.output}",
        )
        update(id) {
            it.copy(
                script = script.output, visualDirection = visual.output, storyboard = scenes.output,
                qualityNotes = listOf(script, visual, scenes).filter { t -> t.status == TaskStatus.WAITING_APPROVAL }.joinToString("\n") { t -> t.reviewNotes },
            )
        }
    }

    private suspend fun photoConcept(project: VideoProject) {
        val photos = videos.photos(project.id)
        if (photos.isEmpty()) throw AiException(AiError.INVALID_REQUEST, Msg.CHOOSE_PHOTO())
        val images = photos.take(MAX_ANALYSED_PHOTOS).map { loadForAnalysis(media.absolutePath(it)) }
        val analysis = engine.runSpecialist(
            AgentRole.VISUAL, Msg.TASK_PHOTO_ANALYSIS(project.title),
            "Describe what these photographs show and how they could work together in a marketing video.",
            images = images,
        )
        val concept = engine.runSpecialist(
            AgentRole.CONTENT, Msg.TASK_VIDEO_CONCEPT(project.title),
            "Create a video concept and a short on-screen caption for: ${project.objective}.\n\nPhotos:\n${analysis.output}",
        )
        val storyboard = engine.runSpecialist(
            AgentRole.VIDEO, Msg.TASK_STORYBOARD(project.title),
            "Create a storyboard ordering these ${photos.size} photos for a ${format(project)} video.\n\n${concept.output}",
        )
        update(project.id) {
            it.copy(
                script = concept.output, visualDirection = analysis.output, storyboard = storyboard.output,
                qualityNotes = listOf(analysis, concept, storyboard).filter { t -> t.status == TaskStatus.WAITING_APPROVAL }.joinToString("\n") { t -> t.reviewNotes },
            )
        }
    }

    /** Runs only after the owner approved the concept, because this step spends the most money. */
    suspend fun generate(id: Long, onProgress: (Int) -> Unit = {}) {
        val project = videos.byId(id) ?: return
        if (project.status != VideoStatus.APPROVED) {
            throw AiException(AiError.INVALID_REQUEST, Msg.APPROVE_CONCEPT_FIRST())
        }
        try {
            val output = if (project.mode == VideoMode.FULL_AI) aiVideo(project) else photoVideo(project, onProgress)
            update(id) { it.copy(status = VideoStatus.QC_REVIEW, outputMediaId = output.id) }
            val review = engine.runSpecialist(
                AgentRole.QUALITY_CONTROL, Msg.TASK_VIDEO_TEXT_REVIEW(project.title),
                "Check this video's script and on-screen text. You cannot watch the video; say so if that matters.\n\n" +
                    "Script:\n${project.script}\n\nOn-screen text:\n${project.captionText}\n\nVoiceover:\n${project.voiceoverText}",
            )
            update(id) { it.copy(status = VideoStatus.READY, qualityNotes = review.output) }
            activity.log(Msg.LOG_VIDEO_READY(), project.title)
        } catch (e: AiException) {
            update(id) { it.copy(status = VideoStatus.FAILED, errorMessage = e.message) }
            throw e
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                update(id) { it.copy(status = VideoStatus.FAILED, errorMessage = Msg.VIDEO_STOPPED_BY_ANDROID()) }
            }
            throw e
        }
    }

    private suspend fun aiVideo(project: VideoProject): MediaFile {
        update(project.id) { it.copy(status = VideoStatus.GENERATING, errorMessage = null) }
        val job: VideoJob = gateway.startVideo(
            AgentRole.VIDEO, project.storyboard.ifBlank { project.script }, project.aspectWidth, project.aspectHeight, project.durationSeconds, null,
        )
        update(project.id) { it.copy(providerJobId = job.jobId) }
        repeat(MAX_POLLS) {
            delay(pollMillis)
            when (val state = gateway.pollVideo(AgentRole.VIDEO, job, project.durationSeconds)) {
                is VideoJobState.Running -> Unit
                is VideoJobState.Failed -> throw AiException(AiError.SERVER, state.message)
                is VideoJobState.Finished -> return media.store(state.bytes, MediaKind.VIDEO, state.mimeType, "${project.title}.mp4", MediaSource.AI_GENERATED)
            }
        }
        throw AiException(AiError.SERVER, Msg.VIDEO_TIMEOUT(job.jobId))
    }

    private suspend fun photoVideo(project: VideoProject, onProgress: (Int) -> Unit): MediaFile {
        update(project.id) { it.copy(status = VideoStatus.RENDERING, errorMessage = null) }
        var voiceover = project.voiceoverMediaId?.let { media.byId(it) }
        if (voiceover == null && project.voiceoverText.isNotBlank()) {
            val voice = settings.get(SettingKeys.VOICE).orEmpty().ifBlank { DEFAULT_VOICE }
            val speech = gateway.speech(AgentRole.VIDEO, voice, project.voiceoverText)
            voiceover = media.store(speech.bytes, MediaKind.AUDIO, speech.mimeType, "${project.title} voiceover.mp3", MediaSource.AI_GENERATED)
            update(project.id) { it.copy(voiceoverMediaId = voiceover.id) }
        }
        val (width, height) = project.outputSize()
        val path = renderer.render(
            RenderSpec(
                photos = videos.photos(project.id).map(media::absolutePath),
                width = width,
                height = height,
                frameRate = project.frameRate,
                durationSeconds = project.durationSeconds,
                transition = project.transition,
                caption = project.captionText,
                voiceoverPath = voiceover?.let(media::absolutePath),
                musicPath = project.musicMediaId?.let { media.byId(it) }?.let(media::absolutePath),
            ),
            onProgress,
        )
        return media.registerFile(path, MediaKind.VIDEO, "video/mp4", "${project.title}.mp4", MediaSource.RENDERED)
    }

    private companion object {
        const val MAX_ANALYSED_PHOTOS = 8
        const val MAX_POLLS = 40
        const val DEFAULT_VOICE = "alloy"
    }
}
