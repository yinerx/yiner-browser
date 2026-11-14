package io.yiner.browser.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.yiner.browser.YinerApp
import io.yiner.browser.core.update.UpdateCandidate
import io.yiner.browser.core.update.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通过 PackageInstaller 会话安装已校验的更新包。
 *
 * - APK 只存放在应用私有目录（files/updates），直接写入安装会话，不经 FileProvider 对外暴露；
 * - 每次安装前重新校验哈希、包名、versionCode 和签名；
 * - 安装必须由用户在系统对话框中确认（Android 12+ 明确设置 USER_ACTION_REQUIRED），不做静默安装。
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"

    fun install(activity: Activity) {
        val app = activity.application as YinerApp
        val manager = app.updates
        // Installing 也允许重试：用户在系统确认界面直接按 Home 离开时不会收到结果回调
        val c = when (val s = manager.status.value) {
            is UpdateStatus.Ready -> s.candidate
            is UpdateStatus.Installing -> s.candidate
            else -> null
        } ?: run {
            Toast.makeText(activity, "没有可安装的更新", Toast.LENGTH_SHORT).show(); return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity, (activity as? io.yiner.browser.browser.UiHost)?.dialogTheme ?: 0)
                .setTitle("需要允许安装应用")
                .setMessage("为了安装更新，请在接下来的系统设置中允许“银耳浏览器”安装未知应用，然后返回并再次点按“安装”。\n\n这一许可只用于安装银耳浏览器自己的更新包。")
                .setPositiveButton("去设置") { _, _ ->
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
                    runCatching { activity.startActivity(i) }.onFailure {
                        Toast.makeText(activity, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        manager.setStatus(UpdateStatus.Installing(c))
        app.appScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateWorker.verifyFile(activity.applicationContext, manager, c) }
            if (result is VerifyResult.Rejected) {
                manager.discard("安装前校验失败：${result.reason}")
                Toast.makeText(activity, "安装包未通过校验，已删除", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { commitSession(activity.applicationContext, manager, c) }
            } catch (e: Exception) {
                Log.w(TAG, "创建安装会话失败", e)
                manager.setStatus(UpdateStatus.Ready(c))
                Toast.makeText(activity, "无法启动安装：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun commitSession(context: Context, manager: UpdateManager, c: UpdateCandidate) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(c.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            manager.apkFile(c.versionCode).inputStream().use { input ->
                session.openWrite("base.apk", 0, c.size).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            // 系统需要往这个 Intent 里填写安装状态，所以 Android 12+ 必须是 MUTABLE
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** 接收 PackageInstaller 的安装结果。 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as YinerApp).updates
        val c = manager.store.candidate
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 弹出系统安装确认界面；用户必须亲自点“安装”
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }.onFailure {
                        Log.w("InstallResult", "无法打开系统安装确认界面", it)
                        if (c != null) manager.setStatus(UpdateStatus.Ready(c))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // 一般到不了这里：新版本安装后旧进程会被系统结束。下次启动时清理下载文件。
                UpdateNotifications.cancelResult(context)
            }
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> null // 用户取消
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统或设备策略阻止"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装的应用冲突（签名不一致？）"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与此设备不兼容"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
                    else -> "安装失败"
                }
                // 安装包本身已通过校验，保持“可安装”状态，允许用户再次尝试
                if (c != null) manager.setStatus(UpdateStatus.Ready(c))
                if (reason != null) {
                    Toast.makeText(context, "$reason${message?.let { "：$it" } ?: ""}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
