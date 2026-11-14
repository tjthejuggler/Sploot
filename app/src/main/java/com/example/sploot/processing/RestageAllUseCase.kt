package com.example.sploot.processing

import com.example.sploot.data.db.StageEntity
import com.example.sploot.data.repo.RecoveryRepository
import com.example.sploot.data.repo.SessionRepository
import com.example.sploot.SleepDates
import com.example.sploot.ml.SleepStagingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Re-stages every stored night with the currently-selected engine's raw-IBI
 * path (sploot-ibi-cnn), from the persisted raw interval rows. Used when a new
 * model ships: existing nights get the new hypnogram, summary and morning
 * score without re-importing the sensor recording.
 *
 * Nights are skipped (left untouched) when the engine has no raw-IBI path or
 * too little usable IBI — never degraded to a different engine silently.
 */
class RestageAllUseCase(
    private val repo: SessionRepository,
    private val recoveryRepo: RecoveryRepository,
    private val engineProvider: () -> SleepStagingEngine,
) {

    data class Outcome(val restaged: Int, val skipped: Int)

    suspend fun restageAll(): Outcome = withContext(Dispatchers.Default) {
        var restaged = 0
        var skipped = 0
        for (session in repo.sessions()) {
            val endMs = session.endEpochMs ?: run { skipped++; continue }
            val intervals = repo.intervals(session.id)
            val nEpochs = ((endMs - session.startEpochMs) / 30_000L).toInt()
            val result = if (intervals.isEmpty() || nEpochs < 1) {
                null
            } else {
                engineProvider().stageRawIbi(
                    session.startEpochMs,
                    intervals.map { it.timestampMs to it.intervalMs },
                    nEpochs,
                )
            }
            if (result == null) {
                skipped++
                continue
            }
            repo.replaceStages(
                session.id,
                result.stages.indices.map { i ->
                    StageEntity(
                        sessionId = session.id,
                        epochIndex = i,
                        stage = result.stages[i],
                        probabilities = result.probabilities[i].toList(),
                        engineId = result.engineId,
                    )
                },
            )
            repo.computeAndStoreSummary(session.id)
            val date = com.example.sploot.SleepDates.nightDate(session.startEpochMs, endMs)
            recoveryRepo.computeMorningScore(date)
            restaged++
        }
        // Drop baseline rows orphaned by the wake-up-date (sleep²) convention
        // change: rows not matching any session's wake date are stale
        // duplicates of the same nights and would skew rolling baselines.
        val wakeDates = repo.sessions()
            .mapNotNull { s -> s.endEpochMs?.let { SleepDates.nightDate(s.startEpochMs, it).toString() } }
            .toSet()
        recoveryRepo.allBaselines()
            .filter { it.date !in wakeDates && it.date <= java.time.LocalDate.now().toString() }
            .forEach { recoveryRepo.deleteBaseline(java.time.LocalDate.parse(it.date)) }
        Outcome(restaged, skipped)
    }

    /**
     * One-shot migration helper: true when any stored night is still staged by
     * a different engine than the target, or when its baseline row is missing
     * under the sleep² wake-up-date convention (used at app start to migrate
     * legacy nights automatically, exactly once per condition).
     */
    suspend fun hasLegacyStagedNights(targetEngineId: String): Boolean {
        val wakeDates = mutableSetOf<String>()
        for (session in repo.sessions()) {
            val stages = repo.hypnogram(session.id)
            if (stages.isEmpty()) continue
            if (stages.any { it.engineId != targetEngineId }) return true
            val endMs = session.endEpochMs ?: continue
            wakeDates += SleepDates.nightDate(session.startEpochMs, endMs).toString()
            if (recoveryRepo.baselineByDate(SleepDates.nightDate(session.startEpochMs, endMs)) == null) {
                return true
            }
        }
        // Orphaned baseline rows from the pre-wake-date convention also need a pass.
        val today = java.time.LocalDate.now().toString()
        return recoveryRepo.allBaselines().any { it.date !in wakeDates && it.date <= today }
    }
}
