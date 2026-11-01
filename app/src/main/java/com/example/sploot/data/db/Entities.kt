package com.example.sploot.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** AASM sleep stage codes shared by [StageEntity] and [ComparisonEntity]. */
object SleepStages {
    const val WAKE = 0
    const val LIGHT = 1
    const val DEEP = 2
    const val REM = 3

    val NAMES = arrayOf("Wake", "Light", "Deep", "REM")

    fun name(stage: Int): String = NAMES.getOrElse(stage) { "Unknown($stage)" }
}

/** Ingestion origin of a session. */
object SessionSources {
    const val POLAR_SDK = "POLAR_SDK"
    const val SNOOP_IMPORT = "SNOOP_IMPORT"
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val deviceId: String,
    val deviceModel: String?,
    /** One of [SessionSources]. */
    val source: String,
    val notes: String?,
    /** Serialized [com.example.sploot.data.repo.SleepSummary] once computed. */
    val sleepSummaryJson: String?,
)

@Entity(
    tableName = "epoch_features",
    primaryKeys = ["sessionId", "epochIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class EpochFeatureEntity(
    val sessionId: String,
    val epochIndex: Int,
    val epochStartMs: Long,
    /** 30 s feature vector (stored as JSON text via [Converters]). */
    val featureVector: List<Float>?,
    val validIbiCount: Int,
)

@Entity(
    tableName = "stages",
    primaryKeys = ["sessionId", "epochIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class StageEntity(
    val sessionId: String,
    val epochIndex: Int,
    /** One of [SleepStages]. */
    val stage: Int,
    /** Per-class probabilities [wake, light, deep, rem], nullable for hard labels. */
    val probabilities: List<Float>?,
    /** Which engine produced the stage (e.g. "onnx-seq", "gbdt-viterbi"). */
    val engineId: String,
)

/**
 * Denormalized inter-beat interval row for fast nightly IBI queries without
 * touching the binary raw log.
 */
@Entity(
    tableName = "raw_intervals",
    primaryKeys = ["sessionId", "timestampMs"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class RawIntervalEntity(
    val sessionId: String,
    val timestampMs: Long,
    val intervalMs: Int,
    /** IbiSource name: PPI | RR | HR_SERVICE. */
    val source: String,
    val blockerBit: Boolean?,
    val skinContact: Boolean?,
    val error: Int?,
)

/** Imported reference hypnogram (e.g. sleep² export) for A/B comparison. */
@Entity(
    tableName = "comparisons",
    primaryKeys = ["sessionId", "sourceLabel", "epochIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ComparisonEntity(
    val sessionId: String,
    /** Human label of the reference source, e.g. "sleep2". */
    val sourceLabel: String,
    val epochIndex: Int,
    /** One of [SleepStages]. */
    val stage: Int,
)

/** One row per morning: nightly HRV aggregates feeding the recovery model. */
@Entity(tableName = "daily_baselines")
data class DailyBaselineEntity(
    /** ISO local date, e.g. "2026-10-31". */
    @PrimaryKey val date: String,
    val nocturnalRmssd: Double,
    val nocturnalRhrMin: Double,
    val sleepScore: Int?,
    val recoveryScore: Int?,
)
