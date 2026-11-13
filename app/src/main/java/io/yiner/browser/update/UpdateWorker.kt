package io.yiner.browser.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.yiner.browser.YinerApp
import io.yiner.browser.core.update.ApkVerifier
import io.yiner.browser.core.update.UpdateCandidate
import io.yiner.browser.core.update.VerifyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

/**
 * 两种模式：
 * - check：定期检查（由 WorkManager 每天调度一次，实际请求仍受 12 小时限频）；
 * - download：下载候选版本的 APK，支持断点续传，完成后立即校验。
 *
 * 在后台被系统停止（Doze、网络断开、执行时长上限）时保留已下载部分，WorkManager 会按退避策略重试。
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val manager get() = (applicationContext as YinerApp).updates

    override suspend fun doWork(): Result = when (inputData.getString(KEY_MODE)) {
        MODE_CHECK -> {
            manager.check(manual = false)
            Result.success()
        }
        MODE_DOWNLOAD -> download()
        else -> Result.failure()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val c = manager.store.candidate
        return foregroundInfo(UpdateNotifications.progress(applicationContext, c?.versionName ?: "", 0, c?.size ?: 0))
    }

    private fun foregroundInfo(n: android.app.Notification) =
        if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(UpdateNotifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(UpdateNotifications.PROGRESS_ID, n)
        }

    private suspend fun download(): Result {
        val c = manager.store.candidate ?: return Result.failure()
        if (c.versionCode != inputData.getLong(KEY_VERSION_CODE, -1)) return Result.success()
        // 纵深防御：下载前再次确认地址属于本仓库该次 Release
        if (!manager.source.isTrustedAssetUrl(c.apkUrl, c.tag, c.apkName)) return fail(c, "安装包地址不可信")

        // 应用在前台时转为前台任务，避免切到后台被中断；在后台启动时系统可能不允许，此时作为普通任务继续
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.i(TAG, "无法转为前台任务，继续以普通后台任务下载：${e.javaClass.simpleName}")
        }

        return try {
            withContext(Dispatchers.IO) { fetch(c) }
            verify(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            when (e.code) {
                404, 410 -> fail(c, "Release 中的安装包已不存在（HTTP ${e.code}）")
                416 -> {
                    manager.partFile(c.versionCode).delete(); retry(c, "下载需要重新开始")
                }
                403, 429 -> retry(c, "下载被 GitHub 限制，稍后自动重试")
                else -> if (e.code >= 500) retry(c, "GitHub 服务器暂时不可用，稍后自动重试") else fail(c, "下载失败（HTTP ${e.code}）")
            }
        } catch (e: InsufficientSpaceException) {
            fail(c, "存储空间不足，需要约 ${io.yiner.browser.core.DownloadNaming.formatSize(e.needed)}")
        } catch (e: IOException) {
            Log.w(TAG, "下载中断", e)
            retry(c, "网络中断，恢复后自动继续下载")
        } finally {
            UpdateNotifications.cancelProgress(applicationContext)
        }
    }

    private class InsufficientSpaceException(val needed: Long) : IOException()

    private fun fetch(c: UpdateCandidate) {
        val part = manager.partFile(c.versionCode)
        var offset = if (part.isFile && part.length() in 1 until c.size) part.length() else 0L
        if (offset == 0L) part.delete()

        val needed = c.size - offset + 32L * 1024 * 1024
        // getAllocatableBytes 会把系统可以清理的缓存也算进去，比 usableSpace 更准确
        val sm = applicationContext.getSystemService(android.os.storage.StorageManager::class.java)
        val allocatable = runCatching { sm.getAllocatableBytes(sm.getUuidForPath(manager.updatesDir)) }
            .getOrElse { manager.updatesDir.usableSpace }
        if (allocatable < needed) throw InsufficientSpaceException(needed)

        val resp = SafeDownloader.open(c.apkUrl, offset)
        try {
            if (offset > 0 && resp.code == 200) {
                offset = 0 // 服务器不支持续传，从头开始
            } else if (offset > 0) {
                val range = resp.connection.getHeaderField("Content-Range").orEmpty()
                if (!range.startsWith("bytes $offset-")) throw IOException("续传位置不一致")
            }
            manager.setStatus(UpdateStatus.Downloading(c, offset, c.size))
            var done = offset
            var lastReport = 0L
            FileOutputStream(part, offset > 0).use { out ->
                resp.connection.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (isStopped) throw IOException("任务被系统暂停")
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > c.size) throw IOException("下载内容超出预期大小")
                        out.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 700) {
                            lastReport = now
                            manager.setStatus(UpdateStatus.Downloading(c, done, c.size))
                            UpdateNotifications.showProgress(applicationContext, c.versionName, done, c.size)
                        }
                    }
                }
            }
            if (done != c.size) throw IOException("下载不完整")
        } finally {
            resp.connection.disconnect()
        }
    }

    private suspend fun verify(c: UpdateCandidate): Result {
        manager.setStatus(UpdateStatus.Verifying(c))
        val part = manager.partFile(c.versionCode)
        val apk = manager.apkFile(c.versionCode)
        val result = withContext(Dispatchers.IO) {
            apk.delete()
            if (!part.renameTo(apk)) return@withContext VerifyResult.Rejected("无法保存安装包")
            verifyFile(applicationContext, manager, c)
        }
        return when (result) {
            VerifyResult.Ok -> {
                manager.store.readyVersionCode = c.versionCode
                manager.store.lastError = null
                manager.setStatus(UpdateStatus.Ready(c))
                UpdateNotifications.notifyReady(applicationContext, c)
                Result.success()
            }
            is VerifyResult.Rejected -> fail(c, "安装包未通过校验：${result.reason}")
        }
    }

    private fun fail(c: UpdateCandidate, message: String): Result {
        manager.discard(message)
        UpdateNotifications.notifyFailed(applicationContext, c, message)
        return Result.failure()
    }

    private fun retry(c: UpdateCandidate, message: String): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) return fail(c, "$message（已重试 $MAX_ATTEMPTS 次，请稍后手动重试）")
        manager.setStatus(UpdateStatus.Queued(c, waitingForUnmetered = false, note = message))
        manager.store.lastError = null
        Log.i(TAG, message)
        return Result.retry()
    }

    companion object {
        private const val TAG = "UpdateWorker"
        const val KEY_MODE = "mode"
        const val KEY_VERSION_CODE = "version_code"
        const val MODE_CHECK = "check"
        const val MODE_DOWNLOAD = "download"
        private const val MAX_ATTEMPTS = 8

        /** 对 files/updates 下的安装包做完整校验（下载完成后和安装前各做一次）。 */
        fun verifyFile(context: Context, manager: UpdateManager, c: UpdateCandidate): VerifyResult {
            val apk = manager.apkFile(c.versionCode)
            if (!apk.isFile) return VerifyResult.Rejected("安装包文件不存在")
            return ApkVerifier.verify(
                candidate = c,
                actualSize = apk.length(),
                actualSha256 = PackageFacts.sha256Hex(apk),
                archive = runCatching { PackageFacts.archive(context, apk) }.getOrNull(),
                installedPackageName = context.packageName,
                installedVersionCode = manager.currentVersionCode,
                installedSignerDigests = runCatching { PackageFacts.installedSignerDigests(context) }.getOrDefault(emptySet()),
            )
        }
    }
}
