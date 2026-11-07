package com.nexiolab.socialai.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.nexiolab.socialai.data.db.NexioDatabase
import com.nexiolab.socialai.domain.backup.ArchiveReader
import com.nexiolab.socialai.domain.backup.ArchiveWriter
import com.nexiolab.socialai.domain.backup.BackupCrypto
import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.backup.BackupManifest
import com.nexiolab.socialai.domain.backup.BackupSecret
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.AgentRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A validated copy waiting for the owner's final confirmation. The live data is still untouched. */
data class StagedRestore(val manifest: BackupManifest, val mediaFiles: Int, val posts: Int, val providers: Int, val missingMedia: Int)

/**
 * Backup and restore share one pipeline with device transfer: snapshot, archive, encrypt on the way
 * out; decrypt, unpack to staging, verify, swap on the way in. API keys are not part of it: they sit
 * in noBackupFilesDir under a device-bound Keystore key and must be entered again on a new phone.
 */
class BackupService(
    private val context: Context,
    private val db: NexioDatabase,
    private val appVersion: String,
) {
    private val filesDir get() = context.filesDir
    private val staging get() = File(filesDir, STAGING)

    /** Takes a consistent database snapshot, writes the archive and removes the snapshot. */
    suspend fun export(output: OutputStream, secret: BackupSecret, onBytes: (Long, Long) -> Unit = { _, _ -> }): BackupManifest =
        withContext(Dispatchers.IO) {
            val snapshot = File(filesDir, SNAPSHOT).apply { parentFile?.mkdirs(); delete() }
            try {
                val escaped = snapshot.path.replace("'", "''")
                db.openHelper.writableDatabase.execSQL("VACUUM INTO '$escaped'")
                val media = db.media().all().map { "media/${it.relativePath}" }.filter { File(filesDir, it).isFile }
                val manifest = ArchiveWriter.manifest(filesDir, listOf(SNAPSHOT) + media, NexioDatabase.VERSION, appVersion, System.currentTimeMillis())
                BackupCrypto.encrypting(output, secret).use { encrypted ->
                    ArchiveWriter.write(encrypted, filesDir, manifest) { done -> onBytes(done, manifest.totalBytes) }
                }
                manifest
            } finally {
                snapshot.delete()
            }
        }

    /** Decrypts into staging and validates everything. Any failure leaves the live data as it was. */
    suspend fun stage(input: InputStream, secret: BackupSecret, onBytes: (Long, Long) -> Unit = { _, _ -> }): StagedRestore =
        withContext(Dispatchers.IO) {
            discardStaged()
            staging.mkdirs()
            try {
                val manifest = BackupCrypto.decrypting(input, secret).use { plain ->
                    ArchiveReader.extract(plain, staging, filesDir.usableSpace, onBytes)
                }
                val summary = validateDatabase(File(staging, SNAPSHOT), manifest)
                summary
            } catch (e: Exception) {
                discardStaged()
                throw e
            }
        }

    /**
     * Raw SQLite checks first, then the real test: the file must open through Room exactly as the app
     * will open it after the swap (identity hash, schema, and MIGRATIONS for an older file). Room runs
     * before localise() so that an older file is already upgraded when its columns are updated.
     * A file Room would reject can therefore never replace the live data.
     */
    private suspend fun validateDatabase(file: File, manifest: BackupManifest): StagedRestore {
        if (!file.isFile) throw BackupException(Msg.ARCHIVE_NO_DATABASE())
        if (manifest.schemaVersion > NexioDatabase.VERSION) throw BackupException(Msg.DATA_NEWER_VERSION())
        openRaw(file).use { sqlite ->
            sqlite.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw BackupException(Msg.DATABASE_DAMAGED())
            }
            if (sqlite.version > NexioDatabase.VERSION) throw BackupException(Msg.DATA_NEWER_VERSION())
        }
        openThroughRoom(file)
        try {
            openRaw(file).use { sqlite ->
                fun count(table: String) = runCatching {
                    sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getInt(0) }
                }.getOrElse { throw BackupException(Msg.DATABASE_INCOMPLETE()) }
                val media = count("media_file")
                val posts = count("content_item")
                val providers = count("ai_provider")
                count("brand_profile")
                val missing = ArrayList<String>()
                sqlite.rawQuery("SELECT relativePath FROM media_file", null).use { c ->
                    while (c.moveToNext()) if (!File(staging, "media/${c.getString(0)}").isFile) missing += c.getString(0)
                }
                localise(sqlite)
                // One complete file gets swapped in, so fold the write-ahead log back into it.
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                sqlite.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                return StagedRestore(manifest, media, posts, providers, missing.size)
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            throw BackupException(Msg.DATABASE_INCOMPLETE())
        } finally {
            removeSidecars(file)
        }
    }

    private fun openRaw(file: File): SQLiteDatabase = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
    } catch (e: android.database.sqlite.SQLiteException) {
        throw BackupException(Msg.DATABASE_CANNOT_OPEN())
    }

    /** Opens the staged file the way the app will, with one real query, then closes it again. */
    private suspend fun openThroughRoom(file: File) {
        try {
            val room = NexioDatabase.build(context, file.path)
            try {
                room.agents().agent(AgentRole.MAIN)
            } finally {
                runCatching { room.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(Msg.DATABASE_NOT_COMPATIBLE())
        } finally {
            removeSidecars(file)
        }
    }

    private fun removeSidecars(file: File) {
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }

    /**
     * Facts that belong to the old phone. Scheduled posts become approved again, otherwise both
     * phones would publish the same post. Connections need fresh credentials here.
     */
    private fun localise(sqlite: SQLiteDatabase) {
        val interrupted = arrayOf<Any>(Msg.INTERRUPTED_BY_MOVE())
        sqlite.beginTransaction()
        try {
            sqlite.execSQL("UPDATE content_item SET status = 'APPROVED' WHERE status = 'SCHEDULED'")
            sqlite.execSQL("UPDATE content_item SET status = 'FAILED', errorMessage = ? WHERE status = 'PUBLISHING'", interrupted)
            sqlite.execSQL("UPDATE instagram_account SET needsReconnect = 1")
            sqlite.execSQL(
                "UPDATE instagram_post SET state = 'FAILED', lastError = ? " +
                    "WHERE state IN ('PENDING','MEDIA_UPLOADED','CONTAINER_CREATED','READY_TO_PUBLISH')",
                interrupted,
            )
            sqlite.execSQL("UPDATE ai_provider SET lastTestOk = NULL, lastTestAt = NULL, lastError = NULL")
            sqlite.execSQL("UPDATE agent_task SET status = 'CANCELLED' WHERE status IN ('QUEUED','RUNNING','NEEDS_CORRECTION')")
            sqlite.execSQL(
                "UPDATE video_project SET status = 'FAILED', errorMessage = ? " +
                    "WHERE status IN ('GENERATING','RENDERING','QC_REVIEW')",
                interrupted,
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    fun discardStaged() {
        staging.deleteRecursively()
    }

    /**
     * Replaces live data with the staged copy. The journal lets [recover] undo a swap that was cut
     * short. The caller must restart the process afterwards, even when this throws, because the
     * database is closed here. If any step fails the old data is put back before the exception leaves.
     */
    fun commit() {
        if (!staging.isDirectory) throw BackupException(Msg.NOTHING_TO_RESTORE())
        val staged = File(staging, SNAPSHOT)
        val paths = Paths(context)
        try {
            paths.journal.writeText(JOURNAL_SWAP)
            db.close()
            listOf(paths.database, paths.wal, paths.shm).forEach { it.renameIfPresent(File(it.path + OLD)) }
            paths.media.renameIfPresent(paths.oldMedia)
            // A missing staged database fails here, after the old one moved aside, and is rolled back.
            check(staged.isFile && staged.renameTo(paths.database)) { Msg.RESTORE_PLACE_FAILED() }
            File(staging, "media").renameIfPresent(paths.media)
            paths.journal.writeText(JOURNAL_DONE)
        } catch (e: Exception) {
            runCatching { recover(context) }
            throw BackupException(Msg.RESTORE_ROLLED_BACK())
        }
        recover(context)
    }

    private fun File.renameIfPresent(target: File) {
        if (exists()) check(renameTo(target)) { Msg.MOVE_FILE_FAILED(name) }
    }

    private class Paths(context: Context) {
        val database: File = context.getDatabasePath(NexioDatabase.FILE_NAME)
        val wal = File(database.path + "-wal")
        val shm = File(database.path + "-shm")
        val media = File(context.filesDir, "media")
        val oldMedia = File(context.filesDir, "media$OLD")
        val journal = File(context.filesDir, "restore.journal")
    }

    companion object {
        private const val SNAPSHOT = "db/nexio.db"
        private const val STAGING = "restore-staging"
        private const val OLD = ".pre-restore"
        private const val JOURNAL_SWAP = "SWAP"
        private const val JOURNAL_DONE = "DONE"

        /** Runs at start-up, before the database opens. Finishes or rolls back an interrupted swap. */
        fun recover(context: Context) {
            val paths = Paths(context)
            val state = paths.journal.takeIf(File::isFile)?.readText() ?: return
            val backups = listOf(paths.database, paths.wal, paths.shm).map { it to File(it.path + OLD) }
            if (state == JOURNAL_SWAP) {
                // Only files that were actually moved aside are put back; a live file whose old copy
                // never moved is still the original and stays.
                backups.forEach { (live, old) ->
                    if (old.isFile) {
                        live.delete()
                        old.renameTo(live)
                    }
                }
                if (paths.oldMedia.exists()) {
                    paths.media.deleteRecursively()
                    paths.oldMedia.renameTo(paths.media)
                }
            } else {
                backups.forEach { (_, old) -> old.delete() }
                paths.oldMedia.deleteRecursively()
            }
            File(context.filesDir, STAGING).deleteRecursively()
            paths.journal.delete()
        }
    }
}
