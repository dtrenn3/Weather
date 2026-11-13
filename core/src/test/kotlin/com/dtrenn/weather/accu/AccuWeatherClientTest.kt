package com.dtrenn.weather.accu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AccuWeatherClientTest {
    private val apiKey = "test-key-should-not-leak"

    @Test
    fun searchSendsBearerHeaderAndParsesCities() {
        val transport = RouteTransport { url, _ ->
            assertTrue(url.startsWith("https://dataservice.accuweather.com/locations/v1/cities/search?"))
            assertTrue(url.contains("q=New%20York"))
            assertFalse(url.contains(apiKey))
            ok(
                """
                [
                  {"Key":"349727","LocalizedName":"New York","AdministrativeArea":{"ID":"NY"},"Country":{"ID":"US"}},
                  {"Key":"","LocalizedName":"Skip me"}
                ]
                """.trimIndent(),
            )
        }
        val places = client(transport).searchCities("  New York ")
        assertEquals(listOf(Place("349727", "New York", "NY", "US")), places)
        assertEquals("New York, NY", places.single().label)
        assertEquals("Bearer $apiKey", transport.calls.single().second["Authorization"])
    }

    @Test
    fun blankKeyIsRejected() {
        try {
            AccuWeatherClient("  ", RouteTransport { _, _ -> ok("{}") })
            throw AssertionError("Expected an empty key to fail.")
        } catch (error: IllegalArgumentException) {
            assertEquals("API key is empty.", error.message)
        }
    }

    @Test
    fun rejectedKeyDoesNotEchoTheSecret() {
        val transport = RouteTransport { _, _ -> HttpBody(401, """{"Message":"$apiKey"}""".toByteArray()) }
        try {
            client(transport).searchCities("Boston")
            throw AssertionError("Expected a rejection.")
        } catch (error: AccuWeatherException.Unauthorized) {
            assertFalse(error.message!!.contains(apiKey))
        }
    }

    @Test
    fun coordinatesUseADotDecimal() {
        val transport = RouteTransport { url, _ ->
            assertTrue(url.contains("q=39.9526%2C-75.1652"))
            ok("""{"Key":"350540","LocalizedName":"Philadelphia","AdministrativeArea":{"ID":"PA"},"Country":{"ID":"US"}}""")
        }
        val place = client(transport).placeForCoordinates(39.95258, -75.16522)
        assertEquals("Philadelphia, PA", place.label)
    }

    @Test
    fun loadUsesTheNewestRadarFrame() {
        val png = pngBytes()
        val transport = RouteTransport { url, _ ->
            when {
                url.contains("/currentconditions/") -> ok(
                    """
                    [{
                      "LocalObservationDateTime":"2026-11-13T14:40:00-05:00",
                      "WeatherText":"Light rain",
                      "Temperature":{"Imperial":{"Value":47.5,"Unit":"F"}},
                      "RealFeelTemperature":{"Imperial":{"Value":44.2,"Unit":"F"}},
                      "HasPrecipitation":true
                    }]
                    """.trimIndent(),
                )
                url.contains("/minute/1minute/") -> ok(
                    """{"Summary":{"Phrase":"Rain tapering off over the next 20 minutes"}}""",
                )
                url.contains("/imagery/") -> ok(
                    """
                    {
                      "MobileLink":"https://www.accuweather.com/mobile",
                      "Radar":{"Images":[
                        {"Date":"2026-11-13T18:30:00Z","Url":"https://images.example/older.png"},
                        {"Date":"2026-11-13T19:10:00Z","Url":"https://images.example/newer.png"}
                      ]}
                    }
                    """.trimIndent(),
                )
                url == "https://images.example/newer.png" -> HttpBody(200, png)
                else -> HttpBody(404, ByteArray(0))
            }
        }
        val frame = client(transport).load("350540")
        assertEquals(48, frame.observation.temperatureF)
        assertEquals(44, frame.realFeelFOrNull())
        assertEquals("Light rain", frame.observation.summary)
        assertEquals("Rain tapering off over the next 20 minutes", frame.minuteCast)
        assertTrue(png.contentEquals(frame.image))
        assertEquals("2026-11-13T19:10:00Z", frame.imageTime)
        assertEquals("https://www.accuweather.com/mobile", frame.mobileLink)
        assertNull(frame.radarProblem)
        assertTrue(transport.calls.none { it.first.contains("apikey=") })
        assertTrue(transport.calls.none { it.first.contains(apiKey) })
    }

    @Test
    fun undatedFramesPreferTheLastImage() {
        val png = pngBytes()
        val transport = RouteTransport { url, _ ->
            when {
                url.contains("/currentconditions/") -> ok("""[{"WeatherText":"Cloudy","Temperature":{"Imperial":{"Value":60}}}]""")
                url.contains("/minute/") -> HttpBody(403, ByteArray(0))
                url.contains("/imagery/") -> ok(
                    """{"Radar":{"Images":[{"Url":"https://images.example/a.png"},{"Url":"https://images.example/b.png"}]}}""",
                )
                url == "https://images.example/b.png" -> HttpBody(200, png)
                else -> HttpBody(404, ByteArray(0))
            }
        }
        val frame = client(transport).load("1")
        assertTrue(png.contentEquals(frame.image))
        assertNull(frame.minuteCast)
    }

    @Test
    fun missingImageryFallsBackToTheStaticMap() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val transport = RouteTransport { url, _ ->
            when {
                url.contains("/currentconditions/") -> ok("""[{"WeatherText":"Clear","Temperature":{"Imperial":{"Value":70}}}]""")
                url.contains("/minute/") -> HttpBody(404, ByteArray(0))
                url.contains("/imagery/") -> ok("""{"Radar":{"Images":[]},"MobileLink":"https://www.accuweather.com/phl"}""")
                url.contains("/maps/v1/radar/staticImage/") -> {
                    assertTrue(url.contains("theme=dark"))
                    assertTrue(url.contains("apikey=$apiKey"))
                    HttpBody(200, jpeg)
                }
                else -> HttpBody(500, ByteArray(0))
            }
        }
        val frame = client(transport).load("350540")
        assertTrue(jpeg.contentEquals(frame.image))
        assertNull(frame.radarProblem)
        assertEquals("https://www.accuweather.com/phl", frame.mobileLink)
    }

    @Test
    fun radarForbiddenStillReturnsTheObservation() {
        val transport = RouteTransport { url, _ ->
            when {
                url.contains("/currentconditions/") -> ok("""[{"WeatherText":"Cloudy","Temperature":{"Imperial":{"Value":51}}}]""")
                url.contains("/minute/") -> HttpBody(403, ByteArray(0))
                else -> HttpBody(403, ByteArray(0))
            }
        }
        val frame = client(transport).load("350540")
        assertEquals(51, frame.observation.temperatureF)
        assertNull(frame.image)
        assertEquals(RadarProblem.NOT_ON_PLAN, frame.radarProblem)
        assertEquals(
            "Radar is not included on this API plan.",
            RadarText.problemLabel(frame.radarProblem!!),
        )
    }

    @Test
    fun formatsWidgetCopy() {
        assertEquals("48°", RadarText.temperature(48))
        assertEquals("", RadarText.temperature(null))
        assertEquals("RealFeel® 44°", RadarText.realFeel(44))
        assertEquals(
            "Updated 2:40 PM · AccuWeather",
            RadarText.updatedLabel(
                "2026-11-13T14:40:00-05:00",
                ZoneId.of("America/New_York"),
            ),
        )
        assertEquals("AccuWeather", RadarText.updatedLabel("not-a-time", ZoneId.of("UTC")))
    }

    private fun client(transport: Transport) = AccuWeatherClient(apiKey, transport)

    private fun ok(json: String) = HttpBody(200, json.toByteArray())

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun RadarFrame.realFeelFOrNull(): Int? = observation.realFeelF

    private class RouteTransport(
        private val handler: (String, Map<String, String>) -> HttpBody,
    ) : Transport {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override fun get(url: String, headers: Map<String, String>): HttpBody {
            calls += url to headers
            return handler(url, headers)
        }
    }
}
