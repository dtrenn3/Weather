package com.dtrenn.weather

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

class RadarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        RadarRefreshWorker.ensureScheduled(context)
        val pending = goAsync()
        val appContext = context.applicationContext
        io.execute {
            try {
                val updater = RadarUpdater(appContext)
                appWidgetIds.forEach { updater.refresh(it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        io.execute {
            try {
                RadarUpdater(appContext).refresh(appWidgetId)
            } finally {
                pending.finish()
            }
        }
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) {
            super.onReceive(context, intent)
            return
        }
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val pending = goAsync()
        val appContext = context.applicationContext
        io.execute {
            try {
                val updater = RadarUpdater(appContext)
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    updater.refreshAll()
                } else {
                    updater.refresh(widgetId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dtrenn.weather.action.REFRESH"

        private val io = Executors.newSingleThreadExecutor()
    }
}
