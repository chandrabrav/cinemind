package com.cinemind.core.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cinemind.core.data.local.dao.ClipDao
import com.cinemind.core.data.local.dao.EditBriefDao
import com.cinemind.core.data.local.dao.MediaAssetDao
import com.cinemind.core.data.local.dao.ProjectDao
import com.cinemind.core.data.local.dao.TrackDao
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.Track

/**
 * Schema changes must ship with an explicit Migration (schemas are exported to core/data/schemas).
 * Destructive fallback is deliberately NOT enabled: it would silently wipe users' projects.
 */
@Database(
    entities = [
        Project::class,
        Track::class,
        Clip::class,
        MediaAsset::class,
        EditBrief::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun trackDao(): TrackDao
    abstract fun clipDao(): ClipDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun editBriefDao(): EditBriefDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cinemind_database",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}

/** v1 → v2: media assets gain a display name and file size. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_assets ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE media_assets ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
    }
}


/** v2 → v3: per-project AI edit briefs (prompt, style, length, pacing). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `edit_briefs` (" +
                "`projectId` TEXT NOT NULL, `prompt` TEXT NOT NULL, `style` TEXT NOT NULL, " +
                "`targetDurationSec` INTEGER, `pacing` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`projectId`), " +
                "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
