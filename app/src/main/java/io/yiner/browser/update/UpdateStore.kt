package io.yiner.browser.update

import android.content.Context
import androidx.core.content.edit
import io.yiner.browser.core.update.UpdateCandidate
import org.json.JSONObject

data class UpdateSettings(
    val checkOnStartup: Boolean = true,
    val periodicCheck: Boolean = true,
    val autoDownload: Boolean = true,
    /** 自动下载只在不计流量的网络（通常是 Wi-Fi）上进行；手动下载不受此限制。 */
    val autoDownloadWifiOnly: Boolean = true,
)

/** 更新相关的持久化数据（独立的 SharedPreferences 文件，不含任何凭据）。 */
class UpdateStore(context: Context) {
    private val sp = context.getSharedPreferences("yiner_updates", Context.MODE_PRIVATE)

    var settings: UpdateSettings
        get() = UpdateSettings(
            checkOnStartup = sp.getBoolean("check_on_startup", true),
            periodicCheck = sp.getBoolean("periodic_check", true),
            autoDownload = sp.getBoolean("auto_download", true),
            autoDownloadWifiOnly = sp.getBoolean("auto_download_wifi_only", true),
        )
        set(v) = sp.edit {
            putBoolean("check_on_startup", v.checkOnStartup)
            putBoolean("periodic_check", v.periodicCheck)
            putBoolean("auto_download", v.autoDownload)
            putBoolean("auto_download_wifi_only", v.autoDownloadWifiOnly)
        }

    var lastCheckAt: Long
        get() = sp.getLong("last_check_at", 0)
        set(v) = sp.edit { putLong("last_check_at", v) }

    var rateLimitResetAt: Long
        get() = sp.getLong("rate_limit_reset_at", 0)
        set(v) = sp.edit { putLong("rate_limit_reset_at", v) }

    /** ETag 与对应的响应体一起保存：收到 304 时用缓存的响应体重新判断。 */
    fun cachedRelease(): Pair<String, String>? {
        val etag = sp.getString("release_etag", null) ?: return null
        val body = sp.getString("release_body", null) ?: return null
        return etag to body
    }

    fun saveRelease(etag: String?, body: String) = sp.edit {
        if (etag.isNullOrBlank()) remove("release_etag") else putString("release_etag", etag)
        putString("release_body", body)
    }

    fun clearReleaseCache() = sp.edit { remove("release_etag"); remove("release_body") }

    fun cachedMetadata(tag: String): String? =
        if (sp.getString("metadata_tag", null) == tag) sp.getString("metadata_body", null) else null

    fun saveMetadata(tag: String, body: String) = sp.edit { putString("metadata_tag", tag); putString("metadata_body", body) }

    var candidate: UpdateCandidate?
        get() = sp.getString("candidate", null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit { if (v == null) remove("candidate") else putString("candidate", encode(v).toString()) }

    /** 已下载并通过校验的版本号（文件在 files/updates/ 下）。 */
    var readyVersionCode: Long
        get() = sp.getLong("ready_version_code", -1)
        set(v) = sp.edit { putLong("ready_version_code", v) }

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit { if (v == null) remove("last_error") else putString("last_error", v) }

    companion object {
        fun encode(c: UpdateCandidate): JSONObject = JSONObject()
            .put("tag", c.tag).put("releaseName", c.releaseName).put("notes", c.notes)
            .put("releasePage", c.releasePage ?: "").put("versionCode", c.versionCode).put("versionName", c.versionName)
            .put("apkName", c.apkName).put("apkUrl", c.apkUrl).put("size", c.size).put("sha256", c.sha256)
            .put("githubSha256", c.githubSha256 ?: "")

        fun decode(o: JSONObject) = UpdateCandidate(
            tag = o.getString("tag"),
            releaseName = o.optString("releaseName"),
            notes = o.optString("notes"),
            releasePage = o.optString("releasePage").ifBlank { null },
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            apkName = o.getString("apkName"),
            apkUrl = o.getString("apkUrl"),
            size = o.getLong("size"),
            sha256 = o.getString("sha256"),
            githubSha256 = o.optString("githubSha256").ifBlank { null },
        )
    }
}
