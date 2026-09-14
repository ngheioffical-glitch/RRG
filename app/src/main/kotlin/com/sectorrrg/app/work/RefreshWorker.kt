package com.sectorrrg.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sectorrrg.app.MainActivity
import com.sectorrrg.app.R
import com.sectorrrg.app.RrgApp
import com.sectorrrg.data.Snapshot
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as RrgApp).repo
        val res = repo.refresh(force = false)
        return if (res.isSuccess) {
            notifyIfNeeded(res.getOrNull())
            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun notifyIfNeeded(snap: Snapshot?) {
        snap ?: return
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 推送嘅不對稱：離場要即刻知，排名日常變動唔出聲
        val breaches = snap.plan.selected.filter {
            it.mom.price <= it.mom.trailStop || !it.mom.aboveSma200
        }
        val gateShut = !snap.plan.marketOn
        val rebalanceSoon = snap.daysToRebalance in 0..3
        if (breaches.isEmpty() && !gateShut && !rebalanceSoon) return

        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "輪動訊號", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val title = when {
            breaches.isNotEmpty() -> "要離場：" + breaches.joinToString(", ") { it.symbol }
            gateShut -> "SPY 跌穿 200 日線，總閘關閉"
            else -> "還有 " + snap.daysToRebalance + " 日月底換倉"
        }

        val top = snap.plan.rows.take(3).joinToString(", ") { it.symbol }

        NotificationManagerCompat.from(ctx).notify(
            1001,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rrg)
                .setContentTitle(title)
                .setContentText(if (top.isEmpty()) "點開睇詳情" else "動能榜首：$top")
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 0, Intent(ctx, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
        )
    }

    companion object {
        const val CHANNEL = "rotation"
    }
}

object RefreshScheduler {

    private const val NAME = "daily-rrg-refresh"

    /** 每日本地時間 06:00 —— 美股收完、數據已 settle。 */
    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var next = now.with(LocalTime.of(6, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build(),
        )
    }
}
