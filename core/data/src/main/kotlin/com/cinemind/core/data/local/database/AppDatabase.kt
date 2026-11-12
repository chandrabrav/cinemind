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
        com.cinemind.core.domain.captions.Caption::class,
        com.cinemind.core.domain.captions.CaptionStyle::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun trackDao(): TrackDao
    abstract fun clipDao(): ClipDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun editBriefDao(): EditBriefDao
    abstract fun captionDao(): com.cinemind.core.data.local.dao.CaptionDao

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


/** v3 → v4: clips record which transition leads into them. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clips ADD COLUMN transitionInType TEXT NOT NULL DEFAULT 'CUT'")
    }
}

/** v4 → v5: caption layer (one row per caption) and the project's caption style. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `captions` (" +
                "`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `startUs` INTEGER NOT NULL, `endUs` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, `wordTimes` TEXT NOT NULL, `language` TEXT NOT NULL, `script` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `uncertain` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captions_projectId` ON `captions` (`projectId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `caption_styles` (" +
                "`projectId` TEXT NOT NULL, `presetId` TEXT NOT NULL, `font` TEXT NOT NULL, `size` REAL NOT NULL, " +
                "`weight` INTEGER NOT NULL, `uppercase` INTEGER NOT NULL, `textColor` INTEGER NOT NULL, " +
                "`highlightColor` INTEGER NOT NULL, `outline` REAL NOT NULL, `outlineColor` INTEGER NOT NULL, " +
                "`shadow` INTEGER NOT NULL, `backgroundColor` INTEGER NOT NULL, `backgroundOpacity` REAL NOT NULL, " +
                "`positionY` REAL NOT NULL, `align` TEXT NOT NULL, `mode` TEXT NOT NULL, `karaoke` INTEGER NOT NULL, " +
                "`animation` TEXT NOT NULL, PRIMARY KEY(`projectId`), " +
                "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/** v5 → v6: each clip can carry a colour grade (look + adjustments). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clips ADD COLUMN grade TEXT NOT NULL DEFAULT ''")
    }
}

/** v6 → v7: speed ramps (constant speed already lived in clips.speed). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clips ADD COLUMN speedCurve TEXT NOT NULL DEFAULT ''")
    }
}

/** v7 → v8: clip framing (crop, rotation, flips, fill, motion keyframes). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clips ADD COLUMN framing TEXT NOT NULL DEFAULT ''")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
