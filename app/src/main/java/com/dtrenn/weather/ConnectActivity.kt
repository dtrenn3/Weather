package com.dtrenn.weather

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.dtrenn.weather.accu.AccuWeatherClient
import com.dtrenn.weather.accu.AccuWeatherException
import com.dtrenn.weather.accu.Place
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class ConnectActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: ConnectorStore
    private var widgetId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        store = ConnectorStore(this)
        val incoming = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        widgetId = incoming.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        if (widgetId != null) {
            setResult(RESULT_CANCELED)
        }

        val hint = findViewById<TextView>(R.id.hint)
        hint.setText(if (widgetId == null) R.string.hint_launcher else R.string.hint_widget)
        if (store.apiKey() != null) {
            findViewById<TextInputLayout>(R.id.key_layout).hint = getString(R.string.api_key_saved)
        }

        findViewById<View>(R.id.find_city).setOnClickListener { search() }
        findViewById<View>(R.id.use_location).setOnClickListener { useLocation() }
        findViewById<View>(R.id.subscriptions).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.accuweather.com/")),
            )
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun search() {
        val query = findViewById<TextInputEditText>(R.id.city_input).text?.toString().orEmpty()
        if (query.isBlank()) {
            showStatus(getString(R.string.need_city))
            return
        }
        val key = resolveKey() ?: return
        setBusy(true)
        showStatus(getString(R.string.searching))
        io.execute {
            try {
                val places = AccuWeatherClient(key, AndroidTransport()).searchCities(query).take(5)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                    showPlaces(key, places)
                }
            } catch (error: AccuWeatherException) {
                showFailure(error)
            } catch (_: Exception) {
                showFailure(null)
            }
        }
    }

    private fun useLocation() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST,
            )
            return
        }
        val key = resolveKey() ?: return
        val manager = getSystemService(LocationManager::class.java)
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
        if (location == null) {
            showStatus(getString(R.string.location_off))
            return
        }
        setBusy(true)
        showStatus(getString(R.string.searching))
        io.execute {
            try {
                val place = AccuWeatherClient(key, AndroidTransport())
                    .placeForCoordinates(location.latitude, location.longitude)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    save(key, place)
                }
            } catch (error: AccuWeatherException) {
                showFailure(error)
            } catch (_: Exception) {
                showFailure(null)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_REQUEST) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            useLocation()
        } else {
            showStatus(getString(R.string.location_denied))
        }
    }

    private fun showPlaces(key: String, places: List<Place>) {
        val results = findViewById<LinearLayout>(R.id.results)
        results.removeAllViews()
        if (places.isEmpty()) {
            showStatus(getString(R.string.no_match))
            return
        }
        showStatus("")
        places.forEach { place ->
            val button = layoutInflater.inflate(R.layout.item_city, results, false) as MaterialButton
            button.text = place.label
            button.setOnClickListener { save(key, place) }
            results.addView(button)
        }
    }

    private fun save(key: String, place: Place) {
        setBusy(true)
        showStatus(getString(R.string.checking))
        io.execute {
            try {
                val frame = AccuWeatherClient(key, AndroidTransport()).load(place.key)
                store.saveApiKey(key)
                val id = widgetId
                if (id != null) {
                    store.saveWidget(id, place, frame.mobileLink)
                } else {
                    store.saveDefault(place, frame.mobileLink)
                }
                RadarRefreshWorker.ensureScheduled(applicationContext)
                RadarUpdater(applicationContext).refreshAll()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (id != null) {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                        )
                    }
                    finish()
                }
            } catch (error: AccuWeatherException) {
                showFailure(error)
            } catch (_: Exception) {
                showFailure(null)
            }
        }
    }

    private fun resolveKey(): String? {
        val typed = findViewById<TextInputEditText>(R.id.key_input).text?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty()) return typed
        val saved = store.apiKey()
        if (!saved.isNullOrBlank()) return saved
        showStatus(getString(R.string.need_key))
        return null
    }

    private fun showFailure(error: AccuWeatherException?) {
        val message = error?.message ?: getString(R.string.offline)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            setBusy(false)
            showStatus(message)
        }
    }

    private fun showStatus(message: String) {
        findViewById<TextView>(R.id.status).text = message
    }

    private fun setBusy(busy: Boolean) {
        findViewById<View>(R.id.find_city).isEnabled = !busy
        findViewById<View>(R.id.use_location).isEnabled = !busy
    }

    companion object {
        private const val LOCATION_REQUEST = 21
    }
}
