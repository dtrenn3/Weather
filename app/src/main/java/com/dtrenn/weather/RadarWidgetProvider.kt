package com.dtrenn.weather

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class RadarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        RadarRefreshWorker.ensureScheduled(context)
        RadarRefreshWorker.enqueueNow(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        RadarRefreshWorker.enqueueNow(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = ConnectorStore(context)
        appWidgetIds.forEach { store.deleteWidget(it) }
    }

    override fun onEnabled(context: Context) {
        RadarRefreshWorker.ensureScheduled(context)
    }

    override fun onDisabled(context: Context) {
        RadarRefreshWorker.cancel(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.dtrenn.weather.action.REFRESH"
    }
}
