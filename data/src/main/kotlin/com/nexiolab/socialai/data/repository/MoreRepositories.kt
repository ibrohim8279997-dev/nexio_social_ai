package com.nexiolab.socialai.data.repository

import com.nexiolab.socialai.data.db.NexioDatabase
import com.nexiolab.socialai.domain.instagram.InstagramRepository
import com.nexiolab.socialai.domain.model.AnalyticsRecord
import com.nexiolab.socialai.domain.model.InstagramAccount
import com.nexiolab.socialai.domain.model.InstagramPost
import com.nexiolab.socialai.domain.model.VideoProject
import com.nexiolab.socialai.domain.video.VideoRepository

class RoomVideoRepository(db: NexioDatabase) : VideoRepository {
    private val dao = db.video()
    override fun observeAll() = dao.observeAll()
    override fun observe(id: Long) = dao.observe(id)
    override fun observePhotos(id: Long) = dao.observePhotos(id)
    override suspend fun byId(id: Long) = dao.byId(id)

    override suspend fun save(project: VideoProject): Long {
        val returned = dao.upsert(project)
        return if (project.id != 0L) project.id else returned
    }

    override suspend fun delete(project: VideoProject) = dao.delete(project)
    override suspend fun photos(id: Long) = dao.photos(id)
    override suspend fun setPhotos(id: Long, mediaIds: List<Long>) = dao.setPhotos(id, mediaIds)
}

class RoomInstagramRepository(db: NexioDatabase) : InstagramRepository {
    private val dao = db.instagram()
    private val analytics = db.analytics()
    override fun observeAccount() = dao.observeAccount()
    override fun observePosts() = dao.observePosts()
    override suspend fun account() = dao.account()
    override suspend fun saveAccount(account: InstagramAccount) = dao.saveAccount(account)
    override suspend fun clearAccount() = dao.clearAccount()
    override suspend fun post(id: Long) = dao.post(id)
    override suspend fun byKey(key: String) = dao.byKey(key)
    override suspend fun latestFor(contentItemId: Long) = dao.latestFor(contentItemId)
    override suspend fun insert(post: InstagramPost) = dao.insert(post)
    override suspend fun save(post: InstagramPost) = dao.save(post)
    override suspend fun published(limit: Int) = dao.published(limit)
    override suspend fun saveAnalytics(records: List<AnalyticsRecord>) = analytics.insertAll(records)
}
