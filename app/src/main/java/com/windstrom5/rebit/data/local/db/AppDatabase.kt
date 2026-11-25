package com.windstrom5.rebit.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.windstrom5.rebit.data.local.db.dao.ArtistInfoDao
import com.windstrom5.rebit.data.local.db.dao.AudioTrackDao
import com.windstrom5.rebit.data.local.db.dao.BenchmarkDao
import com.windstrom5.rebit.data.local.db.dao.DacProfileDao
import com.windstrom5.rebit.data.local.db.dao.ExperimentDao
import com.windstrom5.rebit.data.local.db.dao.WatchedFolderDao
import com.windstrom5.rebit.data.local.db.entity.ArtistInfoEntity
import com.windstrom5.rebit.data.local.db.entity.AudioTrackEntity
import com.windstrom5.rebit.data.local.db.entity.BenchmarkSampleEntity
import com.windstrom5.rebit.data.local.db.entity.DacProfileEntity
import com.windstrom5.rebit.data.local.db.entity.ExperimentRunEntity
import com.windstrom5.rebit.data.local.db.entity.PlaybackSessionEntity
import com.windstrom5.rebit.data.local.db.entity.WatchedFolderEntity

import com.windstrom5.rebit.data.local.db.dao.DacBenchmarkDao
import com.windstrom5.rebit.data.local.db.entity.DacBenchmarkEntity

@Database(
    entities = [
        AudioTrackEntity::class,
        DacProfileEntity::class,
        PlaybackSessionEntity::class,
        BenchmarkSampleEntity::class,
        ExperimentRunEntity::class,
        WatchedFolderEntity::class,
        DacBenchmarkEntity::class,
        ArtistInfoEntity::class
    ],
    // Bumped to 3: DacProfileEntity gained audioControlInterfaceNumber and
    // volumeFeatureUnitId (hardware volume addressing — see
    // UsbDacManager.setHardwareVolume). With fallbackToDestructiveMigration
    // below, existing rows are dropped rather than migrated: acceptable here
    // specifically because every table in this DB is either a cache of
    // re-derivable data (scanned tracks, DAC profiles — both rebuilt by
    // rescanning/re-analyzing) or research session logs the user can
    // re-record. A real migration would be warranted if any of it were
    // irreplaceable user-authored data; none of it is.
    // 4: tracks gained genre/genreSource and there is a new artist_info cache (see MIGRATION_3_4).
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioTrackDao(): AudioTrackDao
    abstract fun dacProfileDao(): DacProfileDao
    abstract fun benchmarkDao(): BenchmarkDao
    abstract fun experimentDao(): ExperimentDao
    abstract fun watchedFolderDao(): WatchedFolderDao
    abstract fun dacBenchmarkDao(): DacBenchmarkDao
    abstract fun artistInfoDao(): ArtistInfoDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Adds genre columns without dropping anything. File size is reset to -1 so the next scan sees
         * every track as changed and re-reads it once, which is how existing tracks pick up their genre
         * tag. Track ids are preserved by the scan, so favourites and listening history stay linked.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_tracks ADD COLUMN genre TEXT")
                db.execSQL("ALTER TABLE audio_tracks ADD COLUMN genreSource TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS artist_info (artistKey TEXT NOT NULL, mbid TEXT, displayName TEXT, " +
                        "genre TEXT, country TEXT, checkedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(artistKey))"
                )
                db.execSQL("UPDATE audio_tracks SET fileSizeBytes = -1")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bitperfect_usb.db"
                ).addMigrations(MIGRATION_3_4).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
