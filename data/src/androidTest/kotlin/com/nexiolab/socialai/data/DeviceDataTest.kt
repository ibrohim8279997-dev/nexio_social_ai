package com.nexiolab.socialai.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexiolab.socialai.data.backup.BackupService
import com.nexiolab.socialai.data.db.NexioDatabase
import com.nexiolab.socialai.data.security.KeystoreCredentialStore
import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.backup.BackupSecret
import com.nexiolab.socialai.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Real Android Keystore, real SQLite and real files on a device or emulator. */
@RunWith(AndroidJUnit4::class)
class DeviceDataTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: NexioDatabase
    private val password = BackupSecret.Password("test password".toCharArray())

    private fun wipe() {
        context.deleteDatabase(NexioDatabase.FILE_NAME)
        File(context.filesDir, "media").deleteRecursively()
        File(context.filesDir, "media.pre-restore").deleteRecursively()
        File(context.filesDir, "restore-staging").deleteRecursively()
        File(context.filesDir, "restore.journal").delete()
    }

    @Before fun open() {
        wipe()
        db = NexioDatabase.build(context)
    }

    @After fun close() {
        db.close()
        wipe()
    }

    private fun service() = BackupService(context, db, "test")

    private suspend fun seed(): MediaFile {
        db.brand().save(BrandProfile(businessName = "Kofe Uyi"))
        val file = File(context.filesDir, "media/image/a.png").apply { parentFile?.mkdirs(); writeBytes(ByteArray(5_000) { it.toByte() }) }
        val media = MediaFile(kind = MediaKind.IMAGE, relativePath = "image/a.png", displayName = "a", mimeType = "image/png", sizeBytes = file.length(), sha256 = "x", createdAt = 1)
        val id = db.media().insert(media)
        db.content().upsert(ContentItem(title = "Post", mediaId = id, status = ContentStatus.SCHEDULED, scheduledAt = 5, approvedAt = 1, createdAt = 1, updatedAt = 1))
        db.providers().upsertProvider(AIProvider(type = ProviderType.OPENAI, name = "OpenAI", baseUrl = "https://x", lastTestOk = true))
        db.instagram().saveAccount(InstagramAccount("ig", "kofe", connectedAt = 1))
        return media.copy(id = id)
    }

    private suspend fun exported(): ByteArray {
        val out = ByteArrayOutputStream()
        service().export(out, password)
        return out.toByteArray()
    }

    @Test fun keystoreRoundTripWithMaskingAndTamperDetection() = runBlocking {
        val store = KeystoreCredentialStore(context)
        val alias = "test.key"
        store.put(alias, "sk-secret-value-1234")
        assertEquals("sk-secret-value-1234", store.get(alias))
        assertEquals("••••••••1234", store.masked(alias))
        val file = File(context.noBackupFilesDir, "credentials/$alias.enc")
        assertFalse("ciphertext must not contain the secret", "sk-secret" in file.readText())
        val tampered = file.readText().toCharArray().also { it[30] = if (it[30] == 'A') 'B' else 'A' }
        file.writeText(String(tampered))
        assertThrows(Exception::class.java) { runBlocking { store.get(alias) } }
        store.delete(alias)
        assertNull(store.get(alias))
    }

    @Test fun credentialBoundToItsAlias() = runBlocking {
        val store = KeystoreCredentialStore(context)
        store.put("alias.one", "secret")
        val dir = File(context.noBackupFilesDir, "credentials")
        File(dir, "alias.one.enc").copyTo(File(dir, "alias.two.enc"), overwrite = true)
        assertThrows(Exception::class.java) { runBlocking { store.get("alias.two") } }
        store.delete("alias.one"); store.delete("alias.two")
    }

    @Test fun agentsAreSeededOnOpen() = runBlocking {
        assertEquals(7, db.agents().observeAgents().first().size)
    }

    @Test fun deletingAProviderCascadesToItsModels() = runBlocking {
        val id = db.providers().upsertProvider(AIProvider(type = ProviderType.GROQ, name = "G", baseUrl = "https://x"))
        val row = db.providers().upsertModel(AIModel(providerId = id, modelId = "m"))
        db.agents().saveAgent(Agent(AgentRole.CONTENT, modelId = row))
        db.providers().deleteProvider(db.providers().provider(id)!!)
        assertEquals(0, db.providers().modelsOf(id).size)
        assertNull("agent keeps working with the default model", db.agents().agent(AgentRole.CONTENT)!!.modelId)
    }

    @Test fun samePublicationCannotBeInsertedTwice() = runBlocking {
        val item = db.content().upsert(ContentItem(title = "p", createdAt = 1, updatedAt = 1))
        val post = InstagramPost(contentItemId = item, igUserId = "i", idempotencyKey = "key", createdAt = 1, updatedAt = 1)
        db.instagram().insert(post)
        try {
            db.instagram().insert(post)
            fail("expected a constraint failure")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            assertNotNull(e)
        }
    }

    @Test fun planAndItemsSaveTogether() = runBlocking {
        val plan = ContentPlan(yearMonth = "2026-11", title = "p", createdAt = 1)
        val id = db.content().replacePlan(plan, listOf(ContentItem(title = "a", scheduledAt = 1, createdAt = 1, updatedAt = 1)))
        assertEquals(id, db.content().observeAll().first().single().planId)
    }

    @Test fun regeneratingAPlanKeepsApprovedPostsAndReplacesDrafts() = runBlocking {
        val plan = ContentPlan(yearMonth = "2026-12", title = "p", createdAt = 1)
        fun post(title: String, status: ContentStatus) = ContentItem(title = title, status = status, scheduledAt = 1, createdAt = 1, updatedAt = 1)
        db.content().replacePlan(plan, listOf(post("keep", ContentStatus.APPROVED), post("old draft", ContentStatus.REVIEW)))
        db.content().replacePlan(plan, listOf(post("new draft", ContentStatus.REVIEW)))
        assertEquals(setOf("keep", "new draft"), db.content().observeAll().first().map { it.title }.toSet())
    }

    @Test fun backupRestoreBringsDataBackAndLocalisesIt() = runBlocking {
        seed()
        val archive = exported()
        db.brand().save(BrandProfile(businessName = "Changed after backup"))
        File(context.filesDir, "media/image/a.png").delete()
        val staged = service().stage(ByteArrayInputStream(archive), password)
        assertEquals(1, staged.mediaFiles)
        assertEquals(1, staged.posts)
        assertEquals(0, staged.missingMedia)
        assertEquals("live data is untouched until commit", "Changed after backup", db.brand().get()!!.businessName)
        service().commit()
        db = NexioDatabase.build(context)
        assertEquals("Kofe Uyi", db.brand().get()!!.businessName)
        assertEquals(5_000, File(context.filesDir, "media/image/a.png").length())
        assertEquals("a restored post must not double-publish", ContentStatus.APPROVED, db.content().observeAll().first().single().status)
        assertTrue(db.instagram().account()!!.needsReconnect)
        assertNull(db.providers().providers().single().lastTestOk)
    }

    @Test fun wrongPasswordLeavesLiveDataAlone() = runBlocking {
        seed()
        val archive = exported()
        try {
            service().stage(ByteArrayInputStream(archive), BackupSecret.Password("wrong".toCharArray()))
            fail("expected refusal")
        } catch (e: BackupException) {
            assertNotNull(e.message)
        }
        assertEquals("Kofe Uyi", db.brand().get()!!.businessName)
        assertFalse(File(context.filesDir, "restore-staging").exists())
    }

    @Test fun damagedFileIsRejectedBeforeAnythingChanges() = runBlocking {
        seed()
        val archive = exported()
        archive[archive.size / 2] = (archive[archive.size / 2].toInt() xor 1).toByte()
        try {
            service().stage(ByteArrayInputStream(archive), password)
            fail("expected refusal")
        } catch (e: BackupException) {
            assertNotNull(e.message)
        }
        assertEquals("Kofe Uyi", db.brand().get()!!.businessName)
    }

    @Test fun interruptedSwapRollsBackAtNextStart() = runBlocking {
        seed()
        val archive = exported()
        db.brand().save(BrandProfile(businessName = "Live data"))
        service().stage(ByteArrayInputStream(archive), password)
        db.close()
        // State a process death would leave between moving the old data out and finishing.
        val database = context.getDatabasePath(NexioDatabase.FILE_NAME)
        File(context.filesDir, "restore.journal").writeText("SWAP")
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
        database.renameTo(File(database.path + ".pre-restore"))
        File(context.filesDir, "media").renameTo(File(context.filesDir, "media.pre-restore"))
        File(context.filesDir, "restore-staging/db/nexio.db").renameTo(database)
        BackupService.recover(context)
        db = NexioDatabase.build(context)
        assertEquals("Live data", db.brand().get()!!.businessName)
        assertTrue(File(context.filesDir, "media/image/a.png").isFile)
        assertFalse(File(context.filesDir, "restore.journal").exists())
    }

    @Test fun stagedArchiveStillOpensThroughRoom() = runBlocking {
        seed()
        service().stage(ByteArrayInputStream(exported()), password)
        val staged = File(context.filesDir, "restore-staging/db/nexio.db")
        assertFalse("the single file that is swapped in must be complete", File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        val copy = NexioDatabase.build(context, staged.path)
        try {
            assertEquals("Kofe Uyi", copy.brand().get()!!.businessName)
            assertNotNull(copy.agents().agent(AgentRole.MAIN))
            assertEquals(ContentStatus.APPROVED, copy.content().observeAll().first().single().status)
        } finally {
            copy.close()
        }
        service().discardStaged()
    }

    @Test fun databaseThatRoomRejectsIsNeverStaged() = runBlocking {
        seed()
        db.openHelper.writableDatabase.execSQL("UPDATE room_master_table SET identity_hash = 'not-this-schema'")
        val archive = exported()
        try {
            service().stage(ByteArrayInputStream(archive), password)
            fail("expected refusal")
        } catch (e: BackupException) {
            assertNotNull(e.message)
        }
        assertFalse(File(context.filesDir, "restore-staging").exists())
        assertEquals("Kofe Uyi", db.brand().get()!!.businessName)
    }

    @Test fun commitFailureRollsBackAndKeepsTheOldData() = runBlocking {
        seed()
        val archive = exported()
        db.brand().save(BrandProfile(businessName = "Live data"))
        service().stage(ByteArrayInputStream(archive), password)
        // The swap starts, the old data moves aside, and then placing the new database fails.
        File(context.filesDir, "restore-staging/db/nexio.db").delete()
        try {
            service().commit()
            fail("expected the swap to fail")
        } catch (e: BackupException) {
            assertNotNull(e.message)
        }
        val database = context.getDatabasePath(NexioDatabase.FILE_NAME)
        assertFalse(File(database.path + ".pre-restore").exists())
        assertFalse(File(context.filesDir, "media.pre-restore").exists())
        assertFalse(File(context.filesDir, "restore.journal").exists())
        db = NexioDatabase.build(context)
        assertEquals("Live data", db.brand().get()!!.businessName)
        assertEquals(5_000, File(context.filesDir, "media/image/a.png").length())
    }

    @Test fun backupContainsNoCredentialFiles() = runBlocking {
        seed()
        val store = KeystoreCredentialStore(context)
        store.put("provider.1", "sk-should-not-travel")
        val staged = service().stage(ByteArrayInputStream(exported()), password)
        assertTrue(staged.manifest.files.none { "credential" in it.path || it.path.endsWith(".enc") })
        assertTrue(staged.manifest.files.all { it.path.startsWith("db/") || it.path.startsWith("media/") })
        service().discardStaged()
        store.delete("provider.1")
    }
}
