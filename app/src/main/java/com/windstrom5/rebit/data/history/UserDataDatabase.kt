package com.windstrom5.rebit.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.windstrom5.rebit.data.news.FeedItemEntity
import com.windstrom5.rebit.data.news.NewsDao
import com.windstrom5.rebit.data.news.WatchedArtistEntity

/**
 * User-authored data (listening history, favorites). Deliberately a separate database from
 * AppDatabase: that one uses fallbackToDestructiveMigration() because everything in it is a
 * rebuildable cache, which would silently wipe irreplaceable history on any schema bump.
 * This one has NO destructive fallback — a schema change must ship a real Migration.
 */
@Database(
    entities = [PlayEventEntity::class, FavoriteEntity::class, WatchedArtistEntity::class, FeedItemEntity::class],
    version = 2,
    exportSchema = false
)
abstract class UserDataDatabase : RoomDatabase() {
    abstract fun listeningDao(): ListeningDao
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile private var instance: UserDataDatabase? = null

        /** v1 -> v2: artist watchlist + news/release feed. Pure additions, existing history untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watched_artists` (`name` TEXT NOT NULL, `mbid` TEXT, " +
                        "`mbidResolved` INTEGER NOT NULL, `lastCheckedMs` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `addedAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feed_items` (`id` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, `url` TEXT, " +
                        "`publishedAtMs` INTEGER NOT NULL, `discoveredAtMs` INTEGER NOT NULL, " +
                        "`notified` INTEGER NOT NULL, `read` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_items_publishedAtMs` ON `feed_items` (`publishedAtMs`)")
            }
        }

        fun getInstance(context: Context): UserDataDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UserDataDatabase::class.java,
                    "bitperfect_user.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
