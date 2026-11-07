package com.nexiolab.socialai.domain.agents

import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.ai.AiGateway
import com.nexiolab.socialai.domain.ai.Capability
import com.nexiolab.socialai.domain.ai.ChatMessage
import com.nexiolab.socialai.domain.ai.ImageInput
import com.nexiolab.socialai.domain.brand.BrandFields
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AgentRole
import com.nexiolab.socialai.domain.model.AgentTask
import com.nexiolab.socialai.domain.model.TaskStatus
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.AgentRepository
import com.nexiolab.socialai.domain.repository.BrandRepository
import com.nexiolab.socialai.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock

@Serializable
private data class PlanStep(val agent: String, val title: String, val brief: String)

@Serializable
private data class AgentPlan(val summary: String = "", val steps: List<PlanStep> = emptyList())

data class Review(val passed: Boolean, val notes: String)

/**
 * Runs the agents. Specialists never reach the owner directly: their work goes through Quality
 * Control, which can send it back. Work still rejected after the retries is parked for the owner
 * as WAITING_APPROVAL rather than silently accepted.
 */
class AgentEngine(
    private val gateway: AiGateway,
    private val agents: AgentRepository,
    private val brand: BrandRepository,
    private val settings: SettingsRepository,
    private val activity: ActivityRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private suspend fun systemFor(role: AgentRole): String {
        val profile = brand.get()
        val config = agents.agent(role)
        val memory = agents.recent(role, MEMORY_ITEMS).joinToString("\n") { "- ${it.title}" }
        val extra = buildString {
            append(config.extraInstructions)
            if (memory.isNotEmpty()) append("\nYour recent finished tasks, for continuity:\n").append(memory)
        }
        return AgentPrompts.system(
            role,
            profile?.let(BrandFields::context).orEmpty(),
            settings.get(SettingKeys.CONTENT_LANGUAGE) ?: profile?.languages.orEmpty(),
            extra,
        )
    }

    private suspend fun ask(role: AgentRole, prompt: String, images: List<ImageInput> = emptyList()) =
        gateway.complete(
            role,
            systemFor(role),
            listOf(ChatMessage.user(prompt, images)),
            if (images.isEmpty()) Capability.TEXT else Capability.VISION,
        )

    private suspend fun newTask(role: AgentRole, title: String, input: String, parentId: Long?): AgentTask {
        val now = clock.millis()
        val task = AgentTask(parentId = parentId, role = role, title = title, input = input, status = TaskStatus.RUNNING, createdAt = now, updatedAt = now)
        return task.copy(id = agents.createTask(task))
    }

    /** One specialist task with its Quality Control loop. Returns the final task row. */
    suspend fun runSpecialist(
        role: AgentRole,
        title: String,
        brief: String,
        parentId: Long? = null,
        images: List<ImageInput> = emptyList(),
        check: ((String) -> String?)? = null,
    ): AgentTask {
        if (!agents.agent(role).enabled) {
            throw AiException(AiError.NOT_CONFIGURED, Msg.AGENT_TURNED_OFF(role.title))
        }
        var task = newTask(role, title, brief, parentId)
        try {
            var result = ask(role, brief, images)
            var attempts = 1
            var notes = ""
            while (true) {
                val truncated = if (result.truncated) "The output was cut off before it finished." else null
                val review = Review(false, truncated ?: check?.invoke(result.text) ?: "").takeIf { it.notes.isNotEmpty() }
                    ?: review(task.id, brief, result.text, role)
                notes = review.notes
                task = task.copy(output = result.text, attempts = attempts, modelUsed = result.modelLabel, reviewNotes = notes)
                if (review.passed) {
                    task = task.copy(status = TaskStatus.COMPLETED)
                    break
                }
                if (attempts >= MAX_ATTEMPTS) {
                    task = task.copy(status = TaskStatus.WAITING_APPROVAL)
                    break
                }
                agents.updateTask(task.copy(status = TaskStatus.NEEDS_CORRECTION))
                result = ask(role, AgentPrompts.correction(brief, result.text, notes), images)
                attempts++
            }
            agents.updateTask(task)
            return task
        } catch (e: CancellationException) {
            agents.setStatus(task.id, TaskStatus.CANCELLED, notes = Msg.STOPPED_BEFORE_FINISHING())
            throw e
        } catch (e: AiException) {
            agents.setStatus(task.id, TaskStatus.FAILED, notes = e.message)
            throw e
        }
    }

    /** Runs a task for the Quality Control agent; an unreadable verdict never counts as a pass. */
    private suspend fun review(parentId: Long, brief: String, work: String, author: AgentRole): Review {
        if (author == AgentRole.QUALITY_CONTROL) return Review(true, "")
        if (!agents.agent(AgentRole.QUALITY_CONTROL).enabled) return Review(true, Msg.QC_TURNED_OFF())
        val task = newTask(AgentRole.QUALITY_CONTROL, Msg.TASK_REVIEW(), work, parentId)
        val answer = try {
            ask(AgentRole.QUALITY_CONTROL, AgentPrompts.review(brief, work))
        } catch (e: AiException) {
            agents.setStatus(task.id, TaskStatus.FAILED, notes = e.message)
            throw e
        }
        val review = parseReview(answer.text)
        agents.updateTask(
            task.copy(
                output = answer.text,
                reviewNotes = review.notes,
                modelUsed = answer.modelLabel,
                status = TaskStatus.COMPLETED,
                attempts = 1,
            ),
        )
        return review
    }

    /** The owner's instruction: plan, delegate, review, summarise. Returns the Main Agent's task. */
    suspend fun runInstruction(instruction: String): AgentTask = resume(createInstructionTask(instruction))

    private fun firstLine(text: String) = text.lineSequence().first().take(TITLE_LENGTH)

    suspend fun createInstructionTask(instruction: String): Long =
        newTask(AgentRole.MAIN, firstLine(instruction), instruction, null).copy(status = TaskStatus.QUEUED).let { task ->
            agents.updateTask(task)
            task.id
        }

    suspend fun resume(taskId: Long): AgentTask {
        val task = agents.task(taskId) ?: throw AiException(AiError.INVALID_REQUEST, Msg.TASK_GONE())
        // WorkManager restarts work the system stopped; only a queued task may spend money.
        if (task.status != TaskStatus.QUEUED) return task
        return continueInstruction(task.copy(status = TaskStatus.RUNNING))
    }

    private suspend fun continueInstruction(main: AgentTask): AgentTask {
        var task = main
        agents.updateTask(task)
        try {
            activity.log(Msg.LOG_MAIN_STARTED(), task.title)
            val plan = planFor(task)
            val results = ArrayList<AgentTask>()
            for (step in plan.steps) {
                val role = specialistOrNull(step.agent) ?: continue
                val context = results.joinToString("\n\n") { "Result from ${it.role.titleEn} (${it.title}):\n${it.output}" }
                val brief = buildString {
                    appendLine("The owner's instruction: ${task.input}")
                    appendLine("Your part: ${step.brief}")
                    if (context.isNotEmpty()) append("\nWork already done by others:\n").append(context)
                }
                results += runSpecialist(role, step.title, brief, parentId = task.id)
            }
            val unresolved = results.filter { it.status == TaskStatus.WAITING_APPROVAL }
            val summary = ask(AgentRole.MAIN, summaryPrompt(task.input, results)).text
            task = task.copy(
                output = summary,
                status = if (unresolved.isEmpty()) TaskStatus.COMPLETED else TaskStatus.WAITING_APPROVAL,
                reviewNotes = unresolved.joinToString("\n") { "${it.role.title}: ${it.reviewNotes}" },
            )
            agents.updateTask(task)
            activity.log(if (unresolved.isEmpty()) Msg.LOG_MAIN_FINISHED() else Msg.LOG_MAIN_NEEDS_REVIEW(), task.title)
            return task
        } catch (e: CancellationException) {
            agents.setStatus(task.id, TaskStatus.CANCELLED, notes = Msg.STOPPED_BEFORE_FINISHING())
            throw e
        } catch (e: AiException) {
            agents.setStatus(task.id, TaskStatus.FAILED, notes = e.message)
            activity.log(Msg.LOG_MAIN_FAILED(), e.message.orEmpty())
            throw e
        }
    }

    private suspend fun planFor(task: AgentTask): AgentPlan {
        val prompt = "The owner's instruction:\n<instruction>\n${task.input}\n</instruction>\n\n${AgentPrompts.PLAN_FORMAT}"
        val answer = ask(AgentRole.MAIN, prompt)
        val parsed = LenientJson.parse(answer.text)?.let { LenientJson.decode(AgentPlan.serializer(), it) }
            ?: throw AiException(AiError.SERVER, Msg.PLAN_UNREADABLE())
        return parsed.copy(steps = parsed.steps.take(MAX_STEPS))
    }

    private fun specialistOrNull(name: String): AgentRole? =
        AgentRole.entries.firstOrNull { it.name == name.trim().uppercase() }
            ?.takeIf { it != AgentRole.MAIN && it != AgentRole.QUALITY_CONTROL }

    private fun summaryPrompt(instruction: String, results: List<AgentTask>) = buildString {
        appendLine("The owner asked: $instruction")
        appendLine()
        results.forEach { appendLine("${it.role.titleEn} (${it.status}):\n${it.output}\n") }
        append("Give the owner the combined result. Clearly list anything that still needs their decision.")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val MAX_STEPS = 6
        private const val TITLE_LENGTH = 80
        private const val MEMORY_ITEMS = 5

        fun parseReview(text: String): Review {
            val json = LenientJson.parse(text) as? JsonObject
            val verdict = runCatching { json?.get("verdict")?.jsonPrimitive?.content }.getOrNull()?.trim()?.lowercase()
            val notes = runCatching { json?.get("notes")?.jsonPrimitive?.content }.getOrNull().orEmpty()
            return when (verdict) {
                "pass" -> Review(true, notes)
                "reject" -> Review(false, notes.ifBlank { "Rejected without notes." })
                else -> Review(false, "Quality Control returned an unreadable verdict.")
            }
        }
    }
}
