package io.yiner.browser.update

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.yiner.browser.BuildConfig
import io.yiner.browser.core.update.CheckPolicy
import io.yiner.browser.core.update.ReleaseParser
import io.yiner.browser.core.update.TrustedSource
import io.yiner.browser.core.update.UpdateCandidate
import io.yiner.browser.core.update.UpdateDecision
import io.yiner.browser.core.update.UpdatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val remoteVersionName: String) : UpdateStatus
    data object NoRelease : UpdateStatus
    data class Available(val candidate: UpdateCandidate) : UpdateStatus
    data class Queued(val candidate: UpdateCandidate, val waitingForUnmetered: Boolean, val note: String? = null) : UpdateStatus
    data class Downloading(val candidate: UpdateCandidate, val done: Long, val total: Long) : UpdateStatus
    data class Verifying(val candidate: UpdateCandidate) : UpdateStatus
    data class Ready(val candidate: UpdateCandidate) : UpdateStatus
    data class Installing(val candidate: UpdateCandidate) : UpdateStatus
    data class Failed(val message: String, val candidate: UpdateCandidate?) : UpdateStatus
}

/**
 * 应用内更新：检查本仓库 GitHub Releases 的最新正式版 → 自动下载 → 校验 → 通知 → 由系统确认安装。
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {
    val source = TrustedSource(BuildConfig.UPDATE_REPO)
    val store = UpdateStore(context)
    private val client = GitHubReleaseClient(source)
    private val checkLock = Mutex()
    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _settings = MutableStateFlow(store.settings)
    val settings: StateFlow<UpdateSettings> = _settings.asStateFlow()

    val updatesDir: File get() = File(context.filesDir, "updates").apply { mkdirs() }
    fun apkFile(versionCode: Long) = File(updatesDir, "yiner-$versionCode.apk")
    fun partFile(versionCode: Long) = File(updatesDir, "yiner-$versionCode.apk.part")

    init {
        restoreStatus()
    }

    private fun restoreStatus() {
        val c = store.candidate
        _status.value = when {
            c == null -> UpdateStatus.Idle
            c.versionCode <= currentVersionCode -> {
                // 已经装上了这个（或更新的）版本：清理下载文件
                clearCandidate(); UpdateStatus.Idle
            }
            store.readyVersionCode == c.versionCode && apkFile(c.versionCode).isFile -> UpdateStatus.Ready(c)
            store.lastError != null -> UpdateStatus.Failed(store.lastError!!, c)
            else -> UpdateStatus.Available(c)
        }
    }

    fun setStatus(s: UpdateStatus) {
        _status.value = s
    }

    fun updateSettings(transform: (UpdateSettings) -> UpdateSettings) {
        val s = transform(_settings.value)
        store.settings = s
        _settings.value = s
        schedulePeriodic()
    }

    /** 应用启动时调用：同步定期任务，按设置做一次（限频的）启动检查。 */
    fun onAppStart() {
        schedulePeriodic()
        if (_settings.value.checkOnStartup) scope.launch { check(manual = false) }
    }

    fun schedulePeriodic() {
        val wm = WorkManager.getInstance(context)
        if (_settings.value.periodicCheck) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(CheckPolicy.PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(UpdateWorker.KEY_MODE to UpdateWorker.MODE_CHECK))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
        }
    }

    /**
     * 检查更新。返回给用户看的一句话结果。
     * 自动检查至少间隔 12 小时，手动至少间隔 1 分钟；被 GitHub 限流时在解除前不再请求。
     */
    suspend fun check(manual: Boolean): String = checkLock.withLock {
        val now = System.currentTimeMillis()
        val busy = _status.value.let {
            it is UpdateStatus.Queued || it is UpdateStatus.Downloading || it is UpdateStatus.Verifying || it is UpdateStatus.Installing
        }
        if (busy) return "正在处理更新，请稍候"
        if (!CheckPolicy.mayCheck(manual, now, store.lastCheckAt, store.rateLimitResetAt)) {
            return if (now < store.rateLimitResetAt) {
                "GitHub API 暂时限流，请在 ${formatTime(store.rateLimitResetAt)} 之后再试"
            } else {
                "刚刚检查过，请稍后再试"
            }
        }
        val previous = _status.value
        _status.value = UpdateStatus.Checking
        val message = try {
            withContext(Dispatchers.IO) { doCheck(manual, now) }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败", e)
            val msg = when (e) {
                is IOException -> "网络不可用，无法检查更新"
                else -> "检查更新失败：${e.message}"
            }
            _status.value = UpdateStatus.Failed(msg, store.candidate)
            msg
        }
        if (!manual && _status.value is UpdateStatus.Failed && previous is UpdateStatus.Ready) {
            // 自动检查失败不应该盖掉“可以安装”的状态
            _status.value = previous
        }
        message
    }

    private fun doCheck(manual: Boolean, now: Long): String {
        val cached = store.cachedRelease()
        val releaseJson = when (val r = client.fetchLatest(cached?.first)) {
            is ApiResult.Ok -> {
                store.lastCheckAt = now
                store.saveRelease(r.etag, r.body)
                r.body
            }
            ApiResult.NotModified -> {
                store.lastCheckAt = now
                cached?.second ?: run { store.clearReleaseCache(); return "请重试" }
            }
            ApiResult.NotFound -> {
                store.lastCheckAt = now
                store.clearReleaseCache()
                _status.value = UpdateStatus.NoRelease
                return "仓库还没有发布正式版"
            }
            is ApiResult.RateLimited -> {
                store.rateLimitResetAt = r.resetAt
                val msg = "GitHub API 限流，请在 ${formatTime(r.resetAt)} 之后再试"
                _status.value = UpdateStatus.Failed(msg, store.candidate)
                return msg
            }
            is ApiResult.HttpError -> {
                store.lastCheckAt = now
                val msg = "GitHub 返回错误（HTTP ${r.code}）"
                _status.value = UpdateStatus.Failed(msg, store.candidate)
                return msg
            }
            is ApiResult.NetworkError -> throw r.error
        }

        val release = ReleaseParser.parseRelease(releaseJson)
        val metaAsset = UpdatePolicy.metadataAsset(release, source).getOrElse { e ->
            val msg = e.message ?: "Release 信息无效"
            _status.value = UpdateStatus.Failed(msg, null)
            return msg
        }
        val metaJson = store.cachedMetadata(release.tag) ?: try {
            SafeDownloader.fetchSmall(metaAsset.downloadUrl, io.yiner.browser.core.update.UpdateMetadata.MAX_BYTES)
                .also { store.saveMetadata(release.tag, it) }
        } catch (e: HttpStatusException) {
            val msg = if (e.code == 404) "Release 中的更新元数据不存在（404）" else "下载更新元数据失败（HTTP ${e.code}）"
            _status.value = UpdateStatus.Failed(msg, null)
            return msg
        }
        val metadata = ReleaseParser.parseMetadata(metaJson)

        return when (val d = UpdatePolicy.decide(release, metadata, source, context.packageName, currentVersionCode, Build.VERSION.SDK_INT)) {
            is UpdateDecision.UpToDate -> {
                clearCandidate()
                _status.value = UpdateStatus.UpToDate(d.remoteVersionName)
                "已是最新版本（${BuildConfig.VERSION_NAME}）"
            }
            is UpdateDecision.Unsupported -> {
                _status.value = UpdateStatus.Failed(d.reason, null)
                d.reason
            }
            is UpdateDecision.Invalid -> {
                _status.value = UpdateStatus.Failed("发布信息未通过校验：${d.reason}", null)
                "发布信息未通过校验：${d.reason}"
            }
            is UpdateDecision.Available -> {
                onCandidate(d.candidate, manual)
                "发现新版本 ${d.candidate.versionName}"
            }
        }
    }

    private fun onCandidate(c: UpdateCandidate, manual: Boolean) {
        val old = store.candidate
        if (old != null && old.versionCode == c.versionCode && old.sha256 == c.sha256 &&
            store.readyVersionCode == c.versionCode && apkFile(c.versionCode).isFile
        ) {
            _status.value = UpdateStatus.Ready(old)
            return
        }
        if (old != null && (old.versionCode != c.versionCode || old.sha256 != c.sha256)) {
            cancelWork()
            deleteFiles()
        }
        store.candidate = c
        store.readyVersionCode = -1
        store.lastError = null
        _status.value = UpdateStatus.Available(c)
        val s = _settings.value
        if (s.autoDownload) {
            startDownload(allowMetered = !s.autoDownloadWifiOnly)
        } else if (!manual) {
            UpdateNotifications.notifyAvailable(context, c)
        }
    }

    /** 开始（或继续）下载当前候选版本。 */
    fun startDownload(allowMetered: Boolean) {
        val c = store.candidate ?: return
        if (store.readyVersionCode == c.versionCode && apkFile(c.versionCode).isFile) {
            _status.value = UpdateStatus.Ready(c); return
        }
        store.lastError = null
        val network = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf(UpdateWorker.KEY_MODE to UpdateWorker.MODE_DOWNLOAD, UpdateWorker.KEY_VERSION_CODE to c.versionCode))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.REPLACE, req)
        _status.value = UpdateStatus.Queued(c, waitingForUnmetered = !allowMetered)
    }

    fun cancelDownload() {
        cancelWork()
        val c = store.candidate
        if (c != null) partFile(c.versionCode).delete()
        UpdateNotifications.cancelProgress(context)
        _status.value = if (c != null) UpdateStatus.Available(c) else UpdateStatus.Idle
    }

    /** 放弃这个候选版本（例如校验失败后）。 */
    fun discard(reason: String?) {
        val c = store.candidate
        cancelWork()
        deleteFiles()
        store.readyVersionCode = -1
        store.lastError = reason
        _status.value = if (reason != null) UpdateStatus.Failed(reason, c) else UpdateStatus.Idle
    }

    private fun cancelWork() {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_WORK)
    }

    private fun clearCandidate() {
        cancelWork()
        deleteFiles()
        store.candidate = null
        store.readyVersionCode = -1
        store.lastError = null
    }

    private fun deleteFiles() {
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "UpdateManager"
        const val PERIODIC_WORK = "yiner-update-check"
        const val DOWNLOAD_WORK = "yiner-update-download"

        fun formatTime(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
    }
}
