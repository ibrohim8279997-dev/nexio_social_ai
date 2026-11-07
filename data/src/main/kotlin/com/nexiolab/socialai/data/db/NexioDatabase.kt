package com.nexiolab.socialai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexiolab.socialai.domain.model.*

@Database(
    version = NexioDatabase.VERSION,
    exportSchema = true,
    entities = [
        UserProfile::class, BrandProfile::class, AIProvider::class, AIModel::class, Agent::class,
        AgentTask::class, ContentPlan::class, ContentItem::class, ContentItemMedia::class, MediaFile::class, VideoProject::class,
        VideoProjectPhoto::class, InstagramAccount::class, InstagramPost::class, AnalyticsRecord::class,
        ExpenseRecord::class, MonthlyBudget::class, ProviderBudget::class, AppSetting::class,
        ActivityEntry::class, AppNotice::class, MigrationSession::class, MigrationManifestRecord::class,
        BackupRecord::class,
    ],
)
abstract class NexioDatabase : RoomDatabase() {
    abstract fun brand(): BrandDao
    abstract fun settings(): SettingsDao
    abstract fun media(): MediaDao
    abstract fun providers(): ProviderDao
    abstract fun agents(): AgentDao
    abstract fun content(): ContentDao
    abstract fun video(): VideoDao
    abstract fun instagram(): InstagramDao
    abstract fun analytics(): AnalyticsDao
    abstract fun expenses(): ExpenseDao
    abstract fun activity(): ActivityDao
    abstract fun migration(): MigrationDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "nexio.db"

        /**
         * 1 to 2: official Instagram carousels. A table for the images of a carousel post and a progress
         * column on instagram_post. Existing rows are untouched. The SQL is what Room generates for
         * schemas/2.json, because Room refuses to open a database whose tables differ from it.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `content_item_media` (`contentItemId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`contentItemId`, `position`), " +
                        "FOREIGN KEY(`contentItemId`) REFERENCES `content_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `media_file`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_item_media_mediaId` ON `content_item_media` (`mediaId`)")
                db.execSQL("ALTER TABLE `instagram_post` ADD COLUMN `carouselProgress` TEXT")
            }
        }

        /** Upgrades go here. There is deliberately no destructive fallback: updates must keep user data. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        private val seedAgents = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                AgentRole.entries.forEach { role ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO agent (role, enabled, extraInstructions) VALUES (?, 1, '')",
                        arrayOf(role.name),
                    )
                }
            }
        }

        fun build(context: Context, name: String? = FILE_NAME): NexioDatabase {
            val builder = if (name != null) {
                Room.databaseBuilder(context, NexioDatabase::class.java, name)
            } else {
                Room.inMemoryDatabaseBuilder(context, NexioDatabase::class.java)
            }
            return builder.addMigrations(*MIGRATIONS).addCallback(seedAgents).build()
        }
    }
}
