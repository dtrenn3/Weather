package com.dtrenn.weather

import com.dtrenn.weather.accu.AccuWeatherException
import com.dtrenn.weather.accu.HttpBody
import com.dtrenn.weather.accu.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class AndroidTransport : Transport {
    override fun get(url: String, headers: Map<String, String>): HttpBody {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.let(::readLimited) ?: ByteArray(0)
            return HttpBody(status, bytes)
        } catch (error: AccuWeatherException) {
            throw error
        } catch (_: IOException) {
            throw AccuWeatherException.Offline()
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): ByteArray {
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw AccuWeatherException.BadResponse()
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    companion object {
        private const val MAX_BYTES = 4_000_000
    }
}
