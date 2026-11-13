package com.dtrenn.weather.accu

data class Place(
    val key: String,
    val city: String,
    val region: String,
    val countryCode: String,
) {
    val label: String
        get() = if (region.isBlank()) city else "$city, $region"
}

data class Observation(
    val summary: String,
    val temperatureF: Int?,
    val realFeelF: Int?,
    val observedAt: String?,
)

enum class RadarProblem {
    NOT_ON_PLAN,
    UNAVAILABLE,
    RATE_LIMITED,
}

data class RadarFrame(
    val observation: Observation,
    val minuteCast: String?,
    val image: ByteArray?,
    val imageTime: String?,
    val mobileLink: String?,
    val radarProblem: RadarProblem?,
) {
    val displayTime: String?
        get() = imageTime ?: observation.observedAt
}

sealed class AccuWeatherException(message: String) : Exception(message) {
    class Unauthorized : AccuWeatherException(
        "AccuWeather rejected that key. Copy it again from the subscriptions page.",
    )

    class Forbidden : AccuWeatherException(
        "This key signed in, but the plan does not include that AccuWeather product.",
    )

    class NotFound : AccuWeatherException(
        "AccuWeather has no match for that place.",
    )

    class RateLimited : AccuWeatherException(
        "AccuWeather rate limit. Wait a little, then try again.",
    )

    class BadResponse : AccuWeatherException(
        "AccuWeather sent a response this widget could not read.",
    )

    class Offline : AccuWeatherException(
        "No connection to AccuWeather.",
    )
}

data class HttpBody(
    val status: Int,
    val bytes: ByteArray,
)

fun interface Transport {
    fun get(url: String, headers: Map<String, String>): HttpBody
}
