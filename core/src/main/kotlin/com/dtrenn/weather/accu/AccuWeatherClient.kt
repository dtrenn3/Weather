package com.dtrenn.weather.accu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.round

class AccuWeatherClient(
    private val apiKey: String,
    private val transport: Transport,
    private val dataHost: String = "https://dataservice.accuweather.com",
    private val mapsHost: String = "https://api.accuweather.com",
) {
    init {
        require(apiKey.isNotBlank()) { "API key is empty." }
    }

    fun searchCities(query: String): List<Place> {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "Enter a city." }
        val root = getJson(
            dataUrl(
                "/locations/v1/cities/search",
                mapOf("q" to trimmed, "language" to "en-us"),
            ),
        )
        val array = root as? JsonArray ?: throw AccuWeatherException.BadResponse()
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let(::parsePlace)
        }
    }

    fun placeForCoordinates(latitude: Double, longitude: Double): Place {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Location is out of range."
        }
        val pair = String.format(Locale.US, "%.4f,%.4f", latitude, longitude)
        val root = getJson(
            dataUrl(
                "/locations/v1/cities/geoposition/search",
                mapOf("q" to pair, "language" to "en-us"),
            ),
        )
        val obj = root as? JsonObject ?: throw AccuWeatherException.BadResponse()
        return parsePlace(obj) ?: throw AccuWeatherException.NotFound()
    }

    fun load(locationKey: String): RadarFrame {
        val key = locationKey.trim()
        require(key.isNotEmpty()) { "Missing location." }
        val conditionsRoot = getJson(
            dataUrl(
                "/currentconditions/v1/${encode(key)}",
                mapOf("details" to "true", "language" to "en-us"),
            ),
        )
        val conditions = (conditionsRoot as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AccuWeatherException.NotFound()
        val observation = parseObservation(conditions)
        val minuteCast = minutePhrase(key)
        val imagery = fetchImagery(key)
        return RadarFrame(
            observation = observation,
            minuteCast = minuteCast,
            image = imagery.image,
            imageTime = imagery.imageTime,
            mobileLink = imagery.mobileLink,
            radarProblem = imagery.problem,
        )
    }

    private fun minutePhrase(locationKey: String): String? {
        val body = try {
            getOptional(
                dataUrl(
                    "/forecasts/v1/minute/1minute/${encode(locationKey)}",
                    mapOf("language" to "en-us"),
                ),
            )
        } catch (_: AccuWeatherException) {
            return null
        } ?: return null
        val root = try {
            parseJson(body) as? JsonObject
        } catch (_: AccuWeatherException) {
            null
        } ?: return null
        val summary = (root["Summary"] as? JsonObject)?.let { str(it, "Phrase") }
        if (!summary.isNullOrBlank()) return summary
        val first = (root["Summaries"] as? JsonArray)
            ?.firstOrNull() as? JsonObject
        return first?.let { str(it, "Phrase") }?.takeIf { it.isNotBlank() }
    }

    private fun fetchImagery(locationKey: String): ImageryResult {
        val response = try {
            transport.get(
                dataUrl(
                    "/imagery/v1/maps/radsat/1024x1024/${encode(locationKey)}",
                    mapOf("language" to "en-us"),
                ),
                jsonHeaders(),
            )
        } catch (error: AccuWeatherException) {
            return staticFallback(locationKey, error)
        }
        when (response.status) {
            401 -> throw AccuWeatherException.Unauthorized()
            403 -> return staticFallback(locationKey, AccuWeatherException.Forbidden())
            429 -> return staticFallback(locationKey, AccuWeatherException.RateLimited())
        }
        if (response.status !in 200..299) {
            return staticFallback(locationKey, AccuWeatherException.BadResponse())
        }
        val root = parseJson(response.bytes) as? JsonObject
            ?: return staticFallback(locationKey, AccuWeatherException.BadResponse())
        val mobileLink = str(root, "MobileLink")
        val images = (root["Radar"] as? JsonObject)?.get("Images") as? JsonArray
        val latest = latestImage(images)
        val url = latest?.let { str(it, "Url") }
        if (url.isNullOrBlank()) {
            return staticFallback(locationKey, null, mobileLink)
        }
        val image = downloadImage(url)
        if (image != null) {
            return ImageryResult(image, str(latest, "Date"), mobileLink, null)
        }
        return staticFallback(locationKey, null, mobileLink)
    }

    private fun staticFallback(
        locationKey: String,
        error: AccuWeatherException?,
        mobileLink: String? = null,
    ): ImageryResult {
        if (error is AccuWeatherException.Unauthorized) throw error
        val response = try {
            transport.get(staticMapUrl(locationKey), imageHeaders())
        } catch (_: AccuWeatherException) {
            return ImageryResult(null, null, mobileLink, problemFor(error))
        }
        if (response.status == 401) throw AccuWeatherException.Unauthorized()
        if (response.status in 200..299 && looksLikeImage(response.bytes)) {
            return ImageryResult(response.bytes, null, mobileLink, null)
        }
        val problem = when (response.status) {
            403 -> RadarProblem.NOT_ON_PLAN
            429 -> RadarProblem.RATE_LIMITED
            else -> problemFor(error)
        }
        return ImageryResult(null, null, mobileLink, problem)
    }

    private fun downloadImage(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val response = try {
            transport.get(url, imageHeaders())
        } catch (_: AccuWeatherException) {
            return null
        }
        if (response.status !in 200..299 || !looksLikeImage(response.bytes)) return null
        return response.bytes
    }

    private fun staticMapUrl(locationKey: String): String {
        val query = listOf(
            "language" to "en-us",
            "theme" to "dark",
            "legend" to "false",
            "imgtype" to "png",
            "apikey" to apiKey,
        ).joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return "$mapsHost/maps/v1/radar/staticImage/768x432/cities/${encode(locationKey)}?$query"
    }

    private fun dataUrl(path: String, query: Map<String, String>): String {
        val encoded = query.entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        return "$dataHost$path?$encoded"
    }

    private fun getJson(url: String): JsonElement = parseJson(getRequired(url))

    private fun getRequired(url: String): ByteArray {
        val response = transport.get(url, jsonHeaders())
        when (response.status) {
            401 -> throw AccuWeatherException.Unauthorized()
            403 -> throw AccuWeatherException.Forbidden()
            404 -> throw AccuWeatherException.NotFound()
            429 -> throw AccuWeatherException.RateLimited()
        }
        if (response.status !in 200..299) throw AccuWeatherException.BadResponse()
        return response.bytes
    }

    private fun getOptional(url: String): ByteArray? {
        val response = transport.get(url, jsonHeaders())
        if (response.status == 401) throw AccuWeatherException.Unauthorized()
        if (response.status !in 200..299) return null
        return response.bytes
    }

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Accept" to "application/json",
    )

    private fun imageHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Accept" to "image/png,image/jpeg,*/*",
    )

    private fun parseJson(bytes: ByteArray): JsonElement {
        val text = bytes.toString(StandardCharsets.UTF_8)
        return try {
            json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            throw AccuWeatherException.BadResponse()
        }
    }

    private fun parsePlace(obj: JsonObject): Place? {
        val key = str(obj, "Key") ?: return null
        val city = str(obj, "LocalizedName") ?: return null
        if (key.isBlank() || city.isBlank()) return null
        val region = obj["AdministrativeArea"] as? JsonObject
        val country = obj["Country"] as? JsonObject
        val regionLabel = region?.let { str(it, "ID") }
            ?: region?.let { str(it, "LocalizedName") }
            ?: ""
        val countryCode = country?.let { str(it, "ID") } ?: ""
        return Place(key, city, regionLabel, countryCode)
    }

    private fun parseObservation(obj: JsonObject): Observation {
        return Observation(
            summary = str(obj, "WeatherText").orEmpty(),
            temperatureF = temperatureF(obj, "Temperature"),
            realFeelF = temperatureF(obj, "RealFeelTemperature"),
            observedAt = str(obj, "LocalObservationDateTime"),
        )
    }

    private fun temperatureF(obj: JsonObject, name: String): Int? {
        val imperial = (obj[name] as? JsonObject)?.get("Imperial") as? JsonObject ?: return null
        val value = imperial["Value"] as? JsonPrimitive ?: return null
        val number = value.doubleOrNull ?: return null
        return round(number).toInt()
    }

    private fun latestImage(images: JsonArray?): JsonObject? {
        val frames = images?.mapNotNull { it as? JsonObject }.orEmpty()
        if (frames.isEmpty()) return null
        return frames
            .mapIndexed { index, frame -> Triple(imageInstant(frame) ?: Instant.EPOCH, index, frame) }
            .maxWithOrNull(compareBy<Triple<Instant, Int, JsonObject>> { it.first }.thenBy { it.second })
            ?.third
    }

    private fun imageInstant(obj: JsonObject): Instant? {
        val raw = str(obj, "Date") ?: return null
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun str(obj: JsonObject, key: String): String? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    private fun problemFor(error: AccuWeatherException?): RadarProblem = when (error) {
        is AccuWeatherException.Forbidden -> RadarProblem.NOT_ON_PLAN
        is AccuWeatherException.RateLimited -> RadarProblem.RATE_LIMITED
        else -> RadarProblem.UNAVAILABLE
    }

    private data class ImageryResult(
        val image: ByteArray?,
        val imageTime: String?,
        val mobileLink: String?,
        val problem: RadarProblem?,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        fun looksLikeImage(bytes: ByteArray): Boolean {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) {
                return true
            }
            return bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
        }
    }
}
