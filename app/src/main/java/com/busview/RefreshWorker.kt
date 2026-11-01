package com.busview

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Re-downloads the timetable every few days so the widget never runs out of dates. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val home = Prefs.home(applicationContext) ?: return Result.success()
        val work = Prefs.work(applicationContext) ?: return Result.success()
        return try {
            Gtfs.build(applicationContext, home, work)
            BusWidget.refresh(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(3, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
