package com.nexiolab.socialai.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Room's own check of 1.json -> 2.json: after the real migration the tables must match schema 2
 * exactly, and what the owner already had must survive. Runs on the JVM under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val file = File(Files.createTempDirectory("nexio-migration").toFile(), "nexio.db")

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), NexioDatabase::class, { NexioDatabase_Impl() },
    )

    private fun SQLiteConnection.text(sql: String): List<String?> =
        prepare(sql).use { s -> if (s.step()) (0 until s.getColumnCount()).map { if (s.isNull(it)) null else s.getText(it) } else emptyList() }

    private fun seedV1() {
        val db = helper.createDatabase(1)
        db.execSQL("INSERT INTO media_file (id, kind, relativePath, displayName, mimeType, sizeBytes, sha256, source, createdAt) VALUES (1, 'IMAGE', 'image/a.jpg', 'a', 'image/jpeg', 10, 'x', 'UPLOADED', 1)")
        db.execSQL(
            "INSERT INTO content_item (id, title, description, caption, hashtags, type, status, mediaId, agentRole, qualityNotes, approvedAt, createdAt, updatedAt) " +
                "VALUES (7, 'Old post', '', 'Hello', '#a', 'IMAGE_POST', 'APPROVED', 1, 'CONTENT', '', 5, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO instagram_post (id, contentItemId, igUserId, idempotencyKey, state, containerId, attempts, createdAt, updatedAt) " +
                "VALUES (3, 7, 'ig', 'content-7-5', 'PUBLISH_UNCERTAIN', 'c9', 1, 1, 1)",
        )
        db.close()
    }

    @Test fun version1To2KeepsExistingPostsAndMatchesTheSchema() {
        seedV1()
        val db = helper.runMigrationsAndValidate(2, listOf(NexioDatabase.MIGRATION_1_2))
        assertEquals(listOf<String?>("Old post", "1", "APPROVED"), db.text("SELECT title, mediaId, status FROM content_item WHERE id = 7"))
        assertEquals(listOf<String?>("PUBLISH_UNCERTAIN", "c9", null), db.text("SELECT state, containerId, carouselProgress FROM instagram_post WHERE id = 3"))
        assertEquals(listOf<String?>("0"), db.text("SELECT COUNT(*) FROM content_item_media"))
        db.close()
    }

    /** The path a restored v1 backup takes: the file is opened through the real builder with MIGRATIONS. */
    @Test fun aVersion1FileOpensThroughTheAppBuilderAndKeepsItsData() = runBlocking {
        seedV1()
        val db = NexioDatabase.build(ApplicationProvider.getApplicationContext<Context>(), file.path)
        try {
            val item = db.content().item(7)!!
            assertEquals("Old post", item.title)
            assertEquals(1L, item.mediaId)
            assertEquals("c9", db.instagram().post(3)!!.containerId)
            assertEquals(null, db.instagram().post(3)!!.carouselProgress)
            // The new table is usable and cascades with its owner.
            db.content().setCarousel(7, listOf(1))
            assertEquals(listOf(1L), db.content().carouselMedia(7).map { it.id })
            db.content().delete(item)
            assertFalse(db.content().carouselMedia(7).isNotEmpty())
        } finally {
            db.close()
        }
    }
}
