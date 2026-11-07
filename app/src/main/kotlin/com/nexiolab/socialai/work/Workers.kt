package com.nexiolab.socialai.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nexiolab.socialai.NexioApp
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.instagram.InstagramException
import com.nexiolab.socialai.domain.instagram.InstagramPublisher
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.InstagramPost
import com.nexiolab.socialai.domain.model.NoticeSeverity
import com.nexiolab.socialai.domain.model.PublishState
import com.nexiolab.socialai.domain.repository.CredentialStore
import kotlinx.coroutines.CancellationException
import java.time.YearMonth
import java.util.concurrent.TimeUnit

private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

abstract class AppWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val app get() = (applicationContext as NexioApp).container

    abstract suspend fun run()

    /** Thrown by run() to ask WorkManager for a later attempt with its backoff. */
    protected class RetryLater : Exception()

    /**
     * After a publish step: an unfinished post is retried with backoff, up to [MAX_PUBLISH_ATTEMPTS]
     * runs. A post that may already be live (PUBLISH_UNCERTAIN) is never retried, only reported.
     */
    protected suspend fun settle(post: InstagramPost) {
        when (post.state) {
            PublishState.PUBLISHED, PublishState.FAILED -> Unit
            PublishState.PUBLISH_UNCERTAIN -> app.activity.notice(
                "uncertain.${post.id}", NoticeSeverity.WARNING, Msg.NOTICE_CHECK_IG_POST(), Msg.NOTICE_CONNECTION_DROPPED(),
            )
            else -> {
                // A reconnect notice is already out; retrying with a dead token only wastes attempts.
                if (app.instagram.account()?.needsReconnect == true) return
                if (runAttemptCount + 1 < MAX_PUBLISH_ATTEMPTS) throw RetryLater()
                app.activity.notice(
                    "stalled.${post.id}", NoticeSeverity.ERROR, Msg.NOTICE_PUBLISH_STALLED(),
                    Msg.NOTICE_PUBLISH_STALLED_BODY(MAX_PUBLISH_ATTEMPTS),
                )
            }
        }
    }

    /** A publish refused before anything was sent: tell the owner, and release an item left in PUBLISHING. */
    protected suspend fun publishBlocked(itemId: Long, e: InstagramPublisher.Blocked, noticeKey: String, title: String, releaseFrom: Set<ContentStatus>) {
        app.activity.notice(noticeKey, NoticeSeverity.ERROR, title, e.message.orEmpty())
        app.content.item(itemId)?.let { item ->
            if (item.status in releaseFrom) app.content.save(item.copy(status = ContentStatus.FAILED, errorMessage = e.message))
        }
    }

    /** A failed paid request is not repeated here; the owner decides after seeing the error. */
    override suspend fun doWork(): Result = try {
        run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RetryLater) {
        Result.retry()
    } catch (e: Exception) {
        app.activity.notice("work.$id", NoticeSeverity.ERROR, Msg.NOTICE_WORK_FAILED(), e.message ?: Msg.UNEXPECTED_ERROR())
        Result.failure()
    }
}

class InstructionWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        app.engine.resume(inputData.getLong(KEY_ID, 0))
    }
}

class PlanWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        val month = YearMonth.parse(inputData.getString(KEY_MONTH))
        val result = app.planner.generateMonth(month)
        val body = if (result.needsOwnerReview) {
            Msg.NOTICE_PLAN_WAITING_REVIEW_QC(result.items, month)
        } else {
            Msg.NOTICE_PLAN_WAITING_REVIEW(result.items, month)
        }
        app.activity.notice("plan.${result.planId}.${System.currentTimeMillis()}", NoticeSeverity.INFO, Msg.LOG_PLAN_READY(), body)
    }
}

class VideoConceptWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        app.videoPipeline.prepareConcept(inputData.getLong(KEY_ID, 0))
    }
}

class VideoGenerateWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        val id = inputData.getLong(KEY_ID, 0)
        app.videoPipeline.generate(id)
        app.activity.notice("video.$id.${System.currentTimeMillis()}", NoticeSeverity.INFO, Msg.LOG_VIDEO_READY(), Msg.NOTICE_VIDEO_FINISHED())
    }
}

/** Publishes one post the owner approved and scheduled. It re-checks that at fire time. */
class ScheduledPublishWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        val item = app.content.item(inputData.getLong(KEY_ID, 0)) ?: return
        // PUBLISHING means an earlier run died after begin(); begin() resumes that same publication.
        if (item.status != ContentStatus.SCHEDULED && item.status != ContentStatus.PUBLISHING) return
        if (item.status == ContentStatus.SCHEDULED && (item.scheduledAt ?: Long.MAX_VALUE) > System.currentTimeMillis()) return
        publish(item.id)
    }

    private suspend fun publish(itemId: Long) {
        val post = try {
            app.publisher.run(app.publisher.begin(itemId).id)
        } catch (e: InstagramPublisher.Blocked) {
            // Never overwrite PUBLISHED: begin() corrects the item before refusing an already-live post.
            publishBlocked(itemId, e, "blocked.$itemId", Msg.NOTICE_SCHEDULED_NOT_SENT(), setOf(ContentStatus.SCHEDULED, ContentStatus.PUBLISHING, ContentStatus.APPROVED))
            return
        }
        settle(post)
    }
}

class PublishWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        val itemId = inputData.getLong(KEY_ITEM, 0)
        val postId = inputData.getLong(KEY_ID, 0)
        try {
            val post = if (postId != 0L) app.publisher.run(postId) else app.publisher.run(app.publisher.begin(itemId).id)
            settle(post)
        } catch (e: InstagramPublisher.Blocked) {
            val owner = if (itemId != 0L) itemId else app.instagram.post(postId)?.contentItemId ?: 0L
            publishBlocked(owner, e, "blocked.$itemId.$id", Msg.NOTICE_COULD_NOT_PUBLISH(), setOf(ContentStatus.PUBLISHING))
        }
    }
}

/** Once a day, on any network: renews the Instagram token and fetches insights. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : AppWorker(context, params) {
    override suspend fun run() {
        val account = app.instagram.account() ?: return
        val token = app.credentials.get(CredentialStore.INSTAGRAM_TOKEN) ?: return
        val expires = account.tokenExpiresAt
        if (expires != null && expires - System.currentTimeMillis() < REFRESH_WINDOW) {
            try {
                val fresh = app.instagramApi.refreshToken(token)
                app.credentials.put(CredentialStore.INSTAGRAM_TOKEN, fresh.accessToken)
                app.instagram.saveAccount(account.copy(tokenExpiresAt = fresh.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 }))
            } catch (e: InstagramException) {
                if (e.authFailure) app.instagram.saveAccount(account.copy(needsReconnect = true))
            }
        }
        app.instagramAnalytics.sync()
    }

    private companion object {
        const val REFRESH_WINDOW = 10L * 24 * 60 * 60 * 1000
    }
}

private const val MAX_PUBLISH_ATTEMPTS = 5
private const val KEY_ID = "id"
private const val KEY_ITEM = "item"
private const val KEY_MONTH = "month"

object Jobs {
    private inline fun <reified W : AppWorker> request(data: Data): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<W>()
            .setConstraints(network)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(data)
            .build()

    private fun manager(context: Context) = WorkManager.getInstance(context)

    fun instruction(context: Context, taskId: Long) {
        manager(context).enqueue(request<InstructionWorker>(workDataOf(KEY_ID to taskId)))
    }

    fun monthPlan(context: Context, month: YearMonth) {
        manager(context).enqueueUniqueWork("plan-$month", ExistingWorkPolicy.KEEP, request<PlanWorker>(workDataOf(KEY_MONTH to month.toString())))
    }

    fun videoConcept(context: Context, id: Long) {
        manager(context).enqueueUniqueWork("video-$id", ExistingWorkPolicy.KEEP, request<VideoConceptWorker>(workDataOf(KEY_ID to id)))
    }

    fun videoGenerate(context: Context, id: Long) {
        manager(context).enqueueUniqueWork("video-$id", ExistingWorkPolicy.KEEP, request<VideoGenerateWorker>(workDataOf(KEY_ID to id)))
    }

    /** Publishes an approved post now. The unique name stops a double tap queuing two runs. */
    fun publishNow(context: Context, itemId: Long) {
        manager(context).enqueueUniqueWork("publish-$itemId", ExistingWorkPolicy.KEEP, request<PublishWorker>(workDataOf(KEY_ITEM to itemId)))
    }

    fun publishRun(context: Context, postId: Long) {
        manager(context).enqueueUniqueWork("publish-run-$postId", ExistingWorkPolicy.KEEP, request<PublishWorker>(workDataOf(KEY_ID to postId)))
    }

    /** One timer per scheduled post, so nothing polls in the background. */
    fun schedulePost(context: Context, itemId: Long, at: Long) {
        val work = OneTimeWorkRequestBuilder<ScheduledPublishWorker>()
            .setConstraints(network)
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ID to itemId))
            .build()
        manager(context).enqueueUniqueWork("scheduled-$itemId", ExistingWorkPolicy.REPLACE, work)
    }

    fun cancelScheduled(context: Context, itemId: Long) {
        manager(context).cancelUniqueWork("scheduled-$itemId")
    }

    fun analytics(context: Context) {
        manager(context).enqueueUniqueWork("analytics", ExistingWorkPolicy.KEEP, request<MaintenanceWorker>(Data.EMPTY))
    }

    fun dailyMaintenance(context: Context) {
        val work = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).setConstraints(network).build()
        manager(context).enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
