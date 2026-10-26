package com.comichub.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.comichub.app.App
import com.comichub.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前景服務：有東西在下載時讓 App 切到背景也不會被系統殺掉，通知列顯示目前進度。
 * 實際下載邏輯在 [Downloads]。
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFY_ID = 1001

        fun start() {
            val ctx = App.instance
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }

        fun update(t: Downloads.Task) {
            val ctx = App.instance
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching { nm.notify(NOTIFY_ID, build(ctx, t)) }
        }

        private fun build(ctx: Context, t: Downloads.Task?): Notification {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val queued = Downloads.tasks.count { it.status == Downloads.Status.Queued }
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (t != null && t.total > 0) "下載中 ${t.done}/${t.total}" else "準備下載…")
                .setContentText(
                    (t?.let { it.title + if (it.chapterTitle.isNotBlank()) " ${it.chapterTitle}" else "" } ?: "") +
                        if (queued > 0) "（還有 $queued 本排隊中）" else ""
                )
                .setProgress(t?.total ?: 0, t?.done ?: 0, t == null || t.total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .build()
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "下載", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = Downloads.tasks.firstOrNull { it.status == Downloads.Status.Running }
        ServiceCompat.startForeground(
            this, NOTIFY_ID, build(this, running),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        // 佇列清空就自己結束（不由外面 stopService，避免還沒 startForeground 就被停掉而閃退）
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                snapshotFlow { Downloads.activeCount > 0 }.first { !it }
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
