package com.morningsearch.guard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TriggerEventEntity::class,
        TamperEventEntity::class,
        PersonalReasonEntity::class,
        KeywordEntity::class,
        FocusSessionEntity::class,
        RecoverEventEntity::class,
        DeviceStatusSnapshotEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun guardianDao(): GuardianDao

    companion object {
        @Volatile private var instance: GuardianDatabase? = null

        fun get(context: Context): GuardianDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GuardianDatabase::class.java,
                "guardian_lock.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS focus_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        plannedEndAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        durationMs INTEGER NOT NULL,
                        completed INTEGER NOT NULL,
                        interruptions INTEGER NOT NULL,
                        allowedPackages TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_focus_sessions_startedAt ON focus_sessions (startedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_focus_sessions_endedAt ON focus_sessions (endedAt)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS recover_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        detail TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recover_events_createdAt ON recover_events (createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recover_events_type ON recover_events (type)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS device_status_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        capturedAt INTEGER NOT NULL,
                        batteryPercent INTEGER NOT NULL,
                        charging INTEGER NOT NULL,
                        networkSummary TEXT NOT NULL,
                        locationSummary TEXT NOT NULL,
                        lostModeActive INTEGER NOT NULL,
                        locked INTEGER NOT NULL,
                        simSummary TEXT NOT NULL,
                        ipAddress TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_device_status_snapshots_capturedAt ON device_status_snapshots (capturedAt)")
            }
        }
    }
}
