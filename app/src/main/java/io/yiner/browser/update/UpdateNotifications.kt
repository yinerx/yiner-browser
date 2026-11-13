package io.yiner.browser.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.yiner.browser.MainActivity
import io.yiner.browser.core.DownloadNaming
import io.yiner.browser.core.update.UpdateCandidate

object UpdateNotifications {
    private const val CHANNEL = "updates"
    const val PROGRESS_ID = 20_001
    private const val RESULT_ID = 20_002

    const val ACTION_INSTALL_UPDATE = "io.yiner.browser.action.INSTALL_UPDATE"
    const val ACTION_SHOW_UPDATE = "io.yiner.browser.action.SHOW_UPDATE"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun activityIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getActivity(
            context, action.hashCode(),
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun progress(context: Context, versionName: String, done: Long, total: Long): Notification {
        ensureChannel(context)
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载银耳浏览器 $versionName")
            .setContentText("${DownloadNaming.formatSize(done)} / ${DownloadNaming.formatSize(total)}")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(activityIntent(context, ACTION_SHOW_UPDATE))
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showProgress(context: Context, versionName: String, done: Long, total: Long) {
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(PROGRESS_ID, progress(context, versionName, done, total))
    }

    fun cancelProgress(context: Context) = NotificationManagerCompat.from(context).cancel(PROGRESS_ID)

    @SuppressLint("MissingPermission")
    private fun post(context: Context, title: String, text: String, action: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, action))
            .build()
        NotificationManagerCompat.from(context).notify(RESULT_ID, n)
    }

    fun notifyAvailable(context: Context, c: UpdateCandidate) =
        post(context, "发现银耳浏览器新版本 ${c.versionName}", "点按查看并下载", ACTION_SHOW_UPDATE)

    fun notifyReady(context: Context, c: UpdateCandidate) =
        post(context, "银耳浏览器 ${c.versionName} 已下载", "点按安装。安装需要你在系统对话框中确认。", ACTION_INSTALL_UPDATE)

    fun notifyFailed(context: Context, c: UpdateCandidate, message: String) =
        post(context, "银耳浏览器 ${c.versionName} 更新失败", message, ACTION_SHOW_UPDATE)

    fun cancelResult(context: Context) = NotificationManagerCompat.from(context).cancel(RESULT_ID)
}
