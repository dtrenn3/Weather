package com.dtrenn.weather

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.dtrenn.weather.accu.AccuWeatherClient
import com.dtrenn.weather.accu.AccuWeatherException
import com.dtrenn.weather.accu.RadarFrame
import com.dtrenn.weather.accu.RadarText
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

class RadarUpdater(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private val store = ConnectorStore(context)

    fun refreshAll() {
        widgetIds().forEach { id -> refresh(id) }
    }

    fun refresh(widgetId: Int) {
        val key = store.apiKey()
        val place = store.widget(widgetId) ?: store.defaultPlace()
        if (key.isNullOrBlank() || place == null) {
            showSetup(widgetId)
            return
        }
        try {
            val frame = AccuWeatherClient(key, AndroidTransport())
                .load(place.key, place.latitude, place.longitude)
            if (!frame.mobileLink.isNullOrBlank() && frame.mobileLink != place.link) {
                store.saveWidget(
                    widgetId,
                    com.dtrenn.weather.accu.Place(
                        place.key,
                        place.label,
                        "",
                        "",
                        place.latitude,
                        place.longitude,
                    ),
                    frame.mobileLink,
                )
            }
            showFrame(widgetId, place.label, frame.mobileLink ?: place.link, frame)
        } catch (error: AccuWeatherException) {
            showProblem(widgetId, place.label, place.link, error.message ?: context.getString(R.string.offline))
        } catch (_: Exception) {
            showProblem(widgetId, place.label, place.link, context.getString(R.string.offline))
        }
    }

    private fun showFrame(widgetId: Int, label: String, link: String?, frame: RadarFrame) {
        val views = baseViews(widgetId, link)
        val large = isLarge(widgetId)
        views.setTextViewText(R.id.place, label)
        views.setTextViewTextSize(
            R.id.temperature,
            TypedValue.COMPLEX_UNIT_SP,
            if (large) 40f else 28f,
        )
        val temperature = RadarText.temperature(frame.observation.temperatureF)
        views.setTextViewText(R.id.temperature, temperature)
        views.setViewVisibility(R.id.temperature, if (temperature.isEmpty()) View.GONE else View.VISIBLE)
        val realFeel = RadarText.realFeel(frame.observation.realFeelF)
        views.setTextViewText(R.id.realfeel, realFeel)
        views.setViewVisibility(R.id.realfeel, if (realFeel.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.condition, frame.observation.summary)
        views.setViewVisibility(R.id.condition, if (frame.observation.summary.isBlank()) View.GONE else View.VISIBLE)
        val minute = frame.minuteCast.orEmpty()
        views.setTextViewText(R.id.minute, minute)
        views.setViewVisibility(R.id.minute, if (minute.isBlank()) View.GONE else View.VISIBLE)
        views.setTextViewText(
            R.id.updated,
            RadarText.updatedLabel(frame.displayTime, ZoneId.systemDefault()),
        )
        val bitmap = frame.image?.let(::decode)
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.radar_image, bitmap)
            views.setViewVisibility(R.id.radar_image, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_image, View.GONE)
        }
        val problem = frame.radarProblem?.let(RadarText::problemLabel)
        if (problem != null && bitmap == null) {
            views.setTextViewText(R.id.message, problem)
            views.setViewVisibility(R.id.message, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.message, View.GONE)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun showProblem(widgetId: Int, label: String, link: String?, message: String) {
        val views = baseViews(widgetId, link)
        views.setTextViewText(R.id.place, label)
        views.setViewVisibility(R.id.temperature, View.GONE)
        views.setViewVisibility(R.id.realfeel, View.GONE)
        views.setViewVisibility(R.id.condition, View.GONE)
        views.setViewVisibility(R.id.minute, View.GONE)
        views.setViewVisibility(R.id.radar_image, View.GONE)
        views.setTextViewText(R.id.updated, context.getString(R.string.attribution))
        views.setTextViewText(R.id.message, message)
        views.setViewVisibility(R.id.message, View.VISIBLE)
        manager.updateAppWidget(widgetId, views)
    }

    private fun showSetup(widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_setup)
        views.setOnClickPendingIntent(R.id.root, connectIntent(widgetId))
        manager.updateAppWidget(widgetId, views)
    }

    private fun baseViews(widgetId: Int, link: String?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_radar)
        views.setOnClickPendingIntent(R.id.root, openIntent(widgetId, link))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(widgetId))
        return views
    }

    private fun isLarge(widgetId: Int): Boolean {
        val height = manager.getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return height >= 180
    }

    private fun widgetIds(): IntArray {
        return manager.getAppWidgetIds(ComponentName(context, RadarWidgetProvider::class.java))
    }

    private fun connectIntent(widgetId: Int): PendingIntent {
        val intent = Intent(context, ConnectActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openIntent(widgetId: Int, link: String?): PendingIntent {
        val intent = if (!link.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
        } else {
            Intent(context, ConnectActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun refreshIntent(widgetId: Int): PendingIntent {
        val intent = Intent(context, RadarWidgetProvider::class.java).apply {
            action = RadarWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId + 50_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: return null
        val longest = max(decoded.width, decoded.height).toFloat()
        if (longest <= MAX_EDGE) return decoded
        val scale = MAX_EDGE / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    companion object {
        private const val MAX_EDGE = 480
    }
}
