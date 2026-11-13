package com.dtrenn.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RadarRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RadarWidgetProvider.ACTION_REFRESH) return
        RadarRefreshWorker.enqueueNow(context)
    }
}
