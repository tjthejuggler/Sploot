package com.example.sploot

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Canonical night-date convention: a night belongs to the calendar date it
 * ENDS on (the wake-up morning) — the sleep² convention, so dates in Sploot
 * match the sleep² diary (one day later than the evening the sleep started).
 */
object SleepDates {

    /** Date of the wake-up morning; falls back to the start date for open sessions. */
    fun nightDate(startEpochMs: Long, endEpochMs: Long?): LocalDate =
        Instant.ofEpochMilli(endEpochMs ?: startEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
}
