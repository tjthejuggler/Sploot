package com.example.sploot.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Sploot Room database. Holds derived session data (features, stages,
 * denormalized IBIs, reference hypnograms) and daily recovery baselines.
 * Raw high-rate samples stay in the binary session files written by
 * `RawRecorder`; only IBI intervals are mirrored here for querying.
 */
@Database(
    entities = [
        SessionEntity::class,
        EpochFeatureEntity::class,
        StageEntity::class,
        RawIntervalEntity::class,
        ComparisonEntity::class,
        DailyBaselineEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SplootDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun epochFeatureDao(): EpochFeatureDao
    abstract fun stageDao(): StageDao
    abstract fun rawIntervalDao(): RawIntervalDao
    abstract fun comparisonDao(): ComparisonDao
    abstract fun dailyBaselineDao(): DailyBaselineDao

    companion object {
        private const val NAME = "sploot.db"

        @Volatile private var instance: SplootDatabase? = null

        fun get(context: Context): SplootDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SplootDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
