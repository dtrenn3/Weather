package com.dtrenn.weather

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RadarRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            RadarUpdater(applicationContext).refreshAll()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "radar-refresh"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<RadarRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
