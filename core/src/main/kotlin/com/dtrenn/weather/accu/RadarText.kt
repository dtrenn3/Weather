package com.dtrenn.weather.accu

import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object RadarText {
    private val clock: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun temperature(fahrenheit: Int?): String =
        if (fahrenheit == null) "" else "$fahrenheit°"

    fun realFeel(fahrenheit: Int?): String =
        if (fahrenheit == null) "" else "RealFeel® $fahrenheit°"

    fun updatedLabel(iso: String?, zone: ZoneId): String {
        if (iso.isNullOrBlank()) return "AccuWeather"
        val time = try {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(clock)
        } catch (_: DateTimeException) {
            return "AccuWeather"
        }
        return "Updated $time · AccuWeather"
    }

    fun problemLabel(problem: RadarProblem): String = when (problem) {
        RadarProblem.NOT_ON_PLAN -> "Radar is not included on this API plan."
        RadarProblem.RATE_LIMITED -> "AccuWeather rate limit. Radar will retry."
        RadarProblem.UNAVAILABLE -> "Radar image is not available for this city."
    }
}
