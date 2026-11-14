package io.yiner.browser.blocking

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import io.yiner.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统 WebView 上的广告与跟踪拦截：
 * - 网络层：在 WebViewClient.shouldInterceptRequest（以及 Service Worker 请求）中，按 EasyList（广告）与
 *   EasyPrivacy（跟踪器）的网络规则子集拦截子资源请求，返回空响应；
 * - 页面层：按 EasyList 元素隐藏规则在页面开始加载时注入 CSS，隐藏广告位。
 *
 * 规则文件随 APK 打包（assets/filters），也可在设置中从 easylist.to 手动更新（固定地址，HTTPS）。
 * 能力边界见 [FilterEngine]。
 */
class ContentBlocker(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {

    sealed interface Status {
        data object Loading : Status
        data class Ready(val ads: FilterEngine.Stats, val trackers: FilterEngine.Stats, val versions: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Loading)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var ads: FilterEngine? = null
    @Volatile private var trackers: FilterEngine? = null
    private val cssCache = ConcurrentHashMap<String, String>()

    private val dir get() = File(context.filesDir, "filters")

    fun start() {
        scope.launch(Dispatchers.Default) { load() }
    }

    private fun readList(name: String): String {
        val updated = File(dir, name)
        return if (updated.isFile && updated.length() > 1000) updated.readText()
        else context.assets.open("filters/$name").bufferedReader().use { it.readText() }
    }

    private fun load() {
        try {
            val easylist = readList(EASYLIST)
            val easyprivacy = readList(EASYPRIVACY)
            val a = FilterEngine.parse(easylist)
            val t = FilterEngine.parse(easyprivacy)
            ads = a
            trackers = t
            cssCache.clear()
            _status.value = Status.Ready(a.stats, t.stats, "EasyList ${version(easylist)} · EasyPrivacy ${version(easyprivacy)}")
        } catch (e: Exception) {
            Log.w(TAG, "加载过滤规则失败", e)
            _status.value = Status.Failed(e.message ?: "未知错误")
        }
    }

    private fun version(list: String) = Regex("""! Version: (\d+)""").find(list.take(2000))?.groupValues?.get(1) ?: "未知版本"

    fun isSiteEnabled(pageHost: String?): Boolean =
        pageHost == null || FilterEngine.baseDomain(pageHost) !in prefs.blockingAllowlist.value

    /** 在 WebView 的后台线程调用。返回 true 表示应拦截该子资源。 */
    fun shouldBlock(url: String, pageHost: String?, accept: String? = null): Boolean {
        if (!isSiteEnabled(pageHost)) return false
        val s = prefs.settings.value
        val type = FilterEngine.typeFromAccept(accept)
        return (s.adBlockEnabled && ads?.shouldBlock(url, pageHost, type) == true) ||
            (s.blockTrackers && trackers?.shouldBlock(url, pageHost, type) == true)
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** 某页面应注入的元素隐藏 CSS（关闭广告拦截或对该站点关闭时为空）。 */
    fun cssFor(pageHost: String?): String {
        if (!prefs.settings.value.adBlockEnabled || !isSiteEnabled(pageHost)) return ""
        val engine = ads ?: return ""
        val key = pageHost?.lowercase() ?: ""
        return cssCache[key] ?: engine.cssFor(pageHost).also { if (cssCache.size < 200) cssCache[key] = it }
    }

    /** 从 easylist.to 下载最新规则（仅这两个固定地址）。返回给用户看的一句话结果。 */
    suspend fun updateLists(): String = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            for ((name, url) in listOf(EASYLIST to EASYLIST_URL, EASYPRIVACY to EASYPRIVACY_URL)) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                }
                try {
                    if (conn.responseCode != 200) return@withContext "更新失败：服务器返回 HTTP ${conn.responseCode}"
                    val bytes = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_LIST_BYTES) return@withContext "更新失败：规则文件过大"
                        }
                        out.toByteArray()
                    }
                    val text = String(bytes, Charsets.UTF_8)
                    if (!text.startsWith("[Adblock Plus") || !text.take(500).contains("Title: Easy")) {
                        return@withContext "更新失败：下载内容不是有效的规则文件"
                    }
                    val tmp = File(dir, "$name.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(File(dir, name))) return@withContext "更新失败：无法保存规则文件"
                } finally {
                    conn.disconnect()
                }
            }
            prefs.filtersUpdatedAt = System.currentTimeMillis()
            load()
            when (val s = _status.value) {
                is Status.Ready -> "已更新：${s.versions}"
                is Status.Failed -> "更新后加载失败：${s.message}"
                Status.Loading -> "已更新"
            }
        } catch (e: java.io.IOException) {
            "更新失败：网络不可用"
        }
    }

    companion object {
        private const val TAG = "ContentBlocker"
        const val EASYLIST = "easylist.txt"
        const val EASYPRIVACY = "easyprivacy.txt"
        const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
        private const val MAX_LIST_BYTES = 12 * 1024 * 1024
    }
}
