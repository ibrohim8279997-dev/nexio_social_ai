package com.nexiolab.socialai

import android.content.Context
import com.nexiolab.socialai.data.ai.DefaultAiClientFactory
import com.nexiolab.socialai.data.backup.BackupService
import com.nexiolab.socialai.data.db.NexioDatabase
import com.nexiolab.socialai.data.instagram.GraphInstagramApi
import com.nexiolab.socialai.data.instagram.S3MediaHost
import com.nexiolab.socialai.data.repository.LocalMediaRepository
import com.nexiolab.socialai.data.repository.RoomActivityRepository
import com.nexiolab.socialai.data.repository.RoomAgentRepository
import com.nexiolab.socialai.data.repository.RoomBrandRepository
import com.nexiolab.socialai.data.repository.RoomContentRepository
import com.nexiolab.socialai.data.repository.RoomExpenseRepository
import com.nexiolab.socialai.data.repository.RoomInstagramRepository
import com.nexiolab.socialai.data.repository.RoomProviderRepository
import com.nexiolab.socialai.data.repository.RoomSettingsRepository
import com.nexiolab.socialai.data.repository.RoomVideoRepository
import com.nexiolab.socialai.data.security.KeystoreCredentialStore
import com.nexiolab.socialai.data.video.AnalysisImages
import com.nexiolab.socialai.data.video.Media3VideoRenderer
import com.nexiolab.socialai.domain.agents.AgentEngine
import com.nexiolab.socialai.domain.ai.AiGateway
import com.nexiolab.socialai.domain.content.ContentPlanner
import com.nexiolab.socialai.domain.instagram.InstagramAnalytics
import com.nexiolab.socialai.domain.instagram.InstagramPublisher
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.CredentialStore
import com.nexiolab.socialai.domain.video.VideoPipeline
import kotlinx.coroutines.flow.first

/** Hand-written dependency graph: small enough to read, with no annotation processing to pay for. */
class AppContainer(val context: Context) {
    val db = NexioDatabase.build(context)
    val credentials: CredentialStore = KeystoreCredentialStore(context)
    val settings = RoomSettingsRepository(db)
    val brand = RoomBrandRepository(db)
    val media = LocalMediaRepository(context, db.media())
    val providers = RoomProviderRepository(db, credentials)
    val agents = RoomAgentRepository(db)
    val content = RoomContentRepository(db)
    val expenses = RoomExpenseRepository(db)
    val video = RoomVideoRepository(db)
    val instagram = RoomInstagramRepository(db)
    val activity: ActivityRepository = NotifyingActivity(context, RoomActivityRepository(db), settings)
    val gateway = AiGateway(providers, agents, credentials, DefaultAiClientFactory(), expenses, activity)
    val engine = AgentEngine(gateway, agents, brand, settings, activity)
    val planner = ContentPlanner(engine, content, activity, libraryImageCount = { media.observeAll().first().count { it.kind == MediaKind.IMAGE } })
    val videoPipeline = VideoPipeline(engine, gateway, video, media, Media3VideoRenderer(context), settings, activity, AnalysisImages::load)
    val instagramApi = GraphInstagramApi()
    val publisher = InstagramPublisher(instagramApi, S3MediaHost(settings, credentials), instagram, content, media, credentials, activity)
    val instagramAnalytics = InstagramAnalytics(instagramApi, instagram, credentials)
    val backup = BackupService(context, db, BuildConfig.VERSION_NAME)
}
