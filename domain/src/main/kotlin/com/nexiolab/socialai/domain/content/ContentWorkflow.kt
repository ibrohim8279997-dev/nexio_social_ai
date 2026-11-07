package com.nexiolab.socialai.domain.content

import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.ContentItem
import com.nexiolab.socialai.domain.model.ContentStatus
import com.nexiolab.socialai.domain.model.ContentStatus.*
import com.nexiolab.socialai.domain.model.ContentType
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.MediaKind

/** The only allowed moves between content statuses; publishing can never skip the owner's approval. */
object ContentWorkflow {
    class Blocked(message: String) : Exception(message)

    const val CAROUSEL_MIN = 2
    const val CAROUSEL_MAX = 10

    /**
     * [children] are the carousel's images in the owner's order; they matter only for a CAROUSEL, which
     * cannot be approved with fewer than [CAROUSEL_MIN] or more than [CAROUSEL_MAX] images.
     */
    fun approve(item: ContentItem, now: Long, children: List<MediaFile> = emptyList()): ContentItem {
        if (item.status != DRAFT && item.status != REVIEW && item.status != FAILED) {
            throw Blocked(Msg.APPROVE_ONLY_DRAFTS())
        }
        if (item.type == ContentType.CAROUSEL) carouselProblem(children)?.let { throw Blocked(it) }
        return item.copy(status = APPROVED, approvedAt = now, errorMessage = null)
    }

    fun schedule(item: ContentItem, now: Long, children: List<MediaFile> = emptyList()): ContentItem {
        if (item.status != APPROVED) throw Blocked(Msg.APPROVE_BEFORE_SCHEDULING())
        problemForPublishing(item, children)?.let { throw Blocked(it) }
        val at = item.scheduledAt ?: throw Blocked(Msg.CHOOSE_DATE_TIME())
        if (at <= now) throw Blocked(Msg.CHOOSE_FUTURE_TIME())
        return item.copy(status = SCHEDULED)
    }

    fun unschedule(item: ContentItem): ContentItem {
        if (item.status != SCHEDULED) throw Blocked(Msg.NOT_SCHEDULED())
        return item.copy(status = APPROVED)
    }

    /**
     * Any text or media change after approval needs a fresh approval. A carousel's images live in
     * their own table, so the caller says whether their set or order changed ([carouselChanged]).
     */
    fun edited(before: ContentItem, after: ContentItem, carouselChanged: Boolean = false): ContentItem {
        val changed = before.caption != after.caption || before.hashtags != after.hashtags ||
            before.mediaId != after.mediaId || before.title != after.title || before.type != after.type ||
            (after.type == ContentType.CAROUSEL && carouselChanged)
        val approved = before.status == APPROVED || before.status == SCHEDULED
        return if (changed && approved) after.copy(status = REVIEW, approvedAt = null) else after
    }

    fun canEdit(status: ContentStatus) = status != PUBLISHING && status != PUBLISHED

    /** Null when [children] make a valid carousel: 2 to 10 images. Video children are not offered by this version. */
    fun carouselProblem(children: List<MediaFile>): String? = when {
        children.size !in CAROUSEL_MIN..CAROUSEL_MAX -> Msg.CAROUSEL_COUNT(CAROUSEL_MIN, CAROUSEL_MAX)
        else -> children.firstOrNull { it.kind != MediaKind.IMAGE }?.let { Msg.CAROUSEL_IMAGES_ONLY(it.displayName) }
    }

    fun problemForPublishing(item: ContentItem, children: List<MediaFile> = emptyList()): String? = when {
        item.approvedAt == null -> Msg.NOT_APPROVED()
        item.type == ContentType.CAROUSEL && carouselProblem(children) != null -> carouselProblem(children)
        item.type != ContentType.CAROUSEL && item.mediaId == null -> Msg.NEEDS_MEDIA()
        item.caption.length + item.hashtags.length > CAPTION_LIMIT -> Msg.CAPTION_LIMIT(CAPTION_LIMIT)
        item.hashtags.count { it == '#' } > HASHTAG_LIMIT -> Msg.HASHTAG_LIMIT(HASHTAG_LIMIT)
        else -> null
    }

    private const val CAPTION_LIMIT = 2200
    private const val HASHTAG_LIMIT = 30
}
