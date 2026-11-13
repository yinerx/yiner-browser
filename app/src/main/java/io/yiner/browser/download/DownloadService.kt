package io.yiner.browser.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.yiner.browser.R
import io.yiner.browser.YinerApp
import io.yiner.browser.core.DownloadNaming
import io.yiner.browser.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 前台服务：保证切到后台时下载不被系统中断。每个下载一个协程，全部结束后自动停止。
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Job>()
    private val app get() = application as YinerApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val id = intent?.getLongExtra(EXTRA_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        when (intent?.action) {
            ACTION_START -> app.downloads.take(id)?.let { start(it) }
            ACTION_CANCEL -> running[id]?.cancel()
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在下载")
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, FOREGROUND_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun start(job: DownloadJob) {
        running[job.id] = scope.launch {
            var done = 0L
            var status = DownloadStatus.FAILED
            var finalName = job.fileName
            var contentUri: android.net.Uri? = null
            val body = job.response.body
            try {
                if (body == null) error("服务器没有返回内容")
                val target = app.downloads.createTarget(job.fileName, job.mimeType)
                contentUri = target.uri
                var ok = false
                try {
                    target.output.use { out ->
                        body.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var lastNotify = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastNotify > 700) {
                                    lastNotify = now
                                    notifyProgress(job, done)
                                    if (!job.isPrivate) app.db.updateDownload(job.id, done, DownloadStatus.RUNNING)
                                }
                            }
                        }
                    }
                    ok = true
                } finally {
                    finalName = target.finish(ok)
                }
                status = DownloadStatus.DONE
            } catch (e: Exception) {
                status = if (e is kotlinx.coroutines.CancellationException) DownloadStatus.CANCELLED else DownloadStatus.FAILED
                Log.w(TAG, "下载失败：${job.fileName}", e)
                runCatching { body?.close() }
            } finally {
                if (!job.isPrivate) {
                    app.db.updateDownload(job.id, done, status, contentUri?.takeIf { status == DownloadStatus.DONE }?.toString(), finalName)
                }
                notifyFinished(job, finalName, status, contentUri)
                running.remove(job.id)
                withContext(NonCancellable + Dispatchers.Main) {
                    app.downloads.changed()
                    stopIfIdle()
                }
            }
        }
    }

    private fun stopIfIdle() {
        if (running.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifyProgress(job: DownloadJob, done: Long) {
        if (!canNotify()) return
        val cancel = PendingIntent.getService(
            this, job.id.toInt(),
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, job.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val total = job.totalBytes
        val text = if (total > 0) "${DownloadNaming.formatSize(done)} / ${DownloadNaming.formatSize(total)}" else DownloadNaming.formatSize(done)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.fileName)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .addAction(0, "取消", cancel)
            .build()
        notify(job, n)
    }

    private fun notifyFinished(job: DownloadJob, name: String, status: DownloadStatus, uri: android.net.Uri?) {
        if (!canNotify()) return
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (status == DownloadStatus.DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(name)
            .setAutoCancel(true)
            .setContentText(
                when (status) {
                    DownloadStatus.DONE -> "下载完成，点按打开"
                    DownloadStatus.CANCELLED -> "已取消"
                    else -> "下载失败"
                },
            )
        if (status == DownloadStatus.DONE && uri != null) {
            b.setContentIntent(
                PendingIntent.getActivity(
                    this, job.id.toInt(), Downloads.openIntent(uri, job.mimeType),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        notify(job, b.build())
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(job: DownloadJob, n: Notification) {
        if (canNotify()) NotificationManagerCompat.from(this).notify(NOTIFY_BASE + job.id.toInt(), n)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_START = "io.yiner.browser.download.START"
        const val ACTION_CANCEL = "io.yiner.browser.download.CANCEL"
        const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "downloads"
        private const val FOREGROUND_ID = 1
        private const val NOTIFY_BASE = 10_000
    }
}
