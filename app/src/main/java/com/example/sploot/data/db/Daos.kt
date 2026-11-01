package com.example.sploot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startEpochMs DESC")
    suspend fun all(): List<SessionEntity>

    @Query(
        "UPDATE sessions SET endEpochMs = :endEpochMs, notes = :notes " +
            "WHERE id = :id"
    )
    suspend fun complete(id: String, endEpochMs: Long, notes: String?)

    @Query("UPDATE sessions SET sleepSummaryJson = :summaryJson WHERE id = :id")
    suspend fun storeSummary(id: String, summaryJson: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface EpochFeatureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EpochFeatureEntity>)

    @Query("SELECT * FROM epoch_features WHERE sessionId = :sessionId ORDER BY epochIndex")
    suspend fun forSession(sessionId: String): List<EpochFeatureEntity>
}

@Dao
interface StageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<StageEntity>)

    @Query("SELECT * FROM stages WHERE sessionId = :sessionId ORDER BY epochIndex")
    suspend fun forSession(sessionId: String): List<StageEntity>
}

@Dao
interface RawIntervalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RawIntervalEntity>)

    @Query("SELECT * FROM raw_intervals WHERE sessionId = :sessionId ORDER BY timestampMs")
    suspend fun forSession(sessionId: String): List<RawIntervalEntity>

    @Query("SELECT COUNT(*) FROM raw_intervals WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int
}

@Dao
interface ComparisonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ComparisonEntity>)

    @Query("SELECT * FROM comparisons WHERE sessionId = :sessionId ORDER BY epochIndex")
    suspend fun forSession(sessionId: String): List<ComparisonEntity>

    @Query("SELECT DISTINCT sourceLabel FROM comparisons WHERE sessionId = :sessionId")
    suspend fun labelsForSession(sessionId: String): List<String>
}

@Dao
interface DailyBaselineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyBaselineEntity)

    @Query("SELECT * FROM daily_baselines WHERE date = :date")
    suspend fun byDate(date: String): DailyBaselineEntity?

    /** Inclusive range query ordered chronologically. */
    @Query(
        "SELECT * FROM daily_baselines WHERE date BETWEEN :fromDate AND :toDate " +
            "ORDER BY date"
    )
    suspend fun range(fromDate: String, toDate: String): List<DailyBaselineEntity>

    @Query("SELECT * FROM daily_baselines ORDER BY date DESC")
    suspend fun all(): List<DailyBaselineEntity>
}
