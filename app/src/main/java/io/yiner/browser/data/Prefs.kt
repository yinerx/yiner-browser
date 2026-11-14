package io.yiner.browser.data

import android.content.Context
import androidx.core.content.edit
import io.yiner.browser.core.ArchiveAfter
import io.yiner.browser.core.SearchEngine
import io.yiner.browser.core.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PinnedSite(val url: String, val title: String)

data class Settings(
    val searchEngine: SearchEngine = SearchEngine.DEFAULT,
    val searchSuggestions: Boolean = true,
    val archiveAfter: ArchiveAfter = ArchiveAfter.DEFAULT,
    /** 拦截广告（EasyList）。 */
    val adBlockEnabled: Boolean = true,
    /** 拦截跟踪器（EasyPrivacy）。 */
    val blockTrackers: Boolean = true,
    /** 允许第三方 Cookie（部分网站的嵌入式登录需要；默认关闭）。 */
    val thirdPartyCookies: Boolean = false,
    val httpsOnly: Boolean = false,
    /** 打开应用时自动弹出搜索面板与键盘（05 “Auto-Open Keyboard”）。 */
    val autoOpenKeyboard: Boolean = true,
    /** 网页文字缩放（百分比，WebView textZoom）。 */
    val textZoomPercent: Int = 100,
    /** 外观：跟随系统（默认）/ 浅色 / 深色。 */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
)

/** SharedPreferences 封装，同时暴露为 StateFlow 供界面观察。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("yiner_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun read() = Settings(
        searchEngine = SearchEngine.fromId(sp.getString("search_engine", null)),
        searchSuggestions = sp.getBoolean("search_suggestions", true),
        archiveAfter = ArchiveAfter.fromHours(sp.getInt("archive_hours", ArchiveAfter.DEFAULT.hours)),
        adBlockEnabled = sp.getBoolean("adblock", true),
        blockTrackers = sp.getBoolean("block_trackers", true),
        thirdPartyCookies = sp.getBoolean("third_party_cookies", false),
        httpsOnly = sp.getBoolean("https_only", false),
        autoOpenKeyboard = sp.getBoolean("auto_open_keyboard", true),
        textZoomPercent = sp.getInt("text_zoom", 100),
        themeMode = ThemeMode.fromId(sp.getString("theme_mode", null)),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        sp.edit {
            putString("search_engine", s.searchEngine.id)
            putBoolean("search_suggestions", s.searchSuggestions)
            putInt("archive_hours", s.archiveAfter.hours)
            putBoolean("adblock", s.adBlockEnabled)
            putBoolean("block_trackers", s.blockTrackers)
            putBoolean("third_party_cookies", s.thirdPartyCookies)
            putBoolean("https_only", s.httpsOnly)
            putBoolean("auto_open_keyboard", s.autoOpenKeyboard)
            putInt("text_zoom", s.textZoomPercent)
            putString("theme_mode", s.themeMode.id)
        }
        _settings.value = s
    }

    var selectedTabId: String?
        get() = sp.getString("selected_tab", null)
        set(v) = sp.edit { putString("selected_tab", v) }

    /** 固定的网站（02 菜单“Pin”）：按固定顺序保存 URL 与标题。 */
    private val _pinned = MutableStateFlow(readPinned())
    val pinned: StateFlow<List<PinnedSite>> = _pinned.asStateFlow()

    private fun readPinned(): List<PinnedSite> = runCatching {
        val arr = org.json.JSONArray(sp.getString("pinned_sites", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PinnedSite(o.getString("url"), o.optString("title")) } }
    }.getOrDefault(emptyList())

    fun isPinned(url: String) = _pinned.value.any { it.url == url }

    fun togglePin(url: String, title: String): Boolean {
        val now = if (isPinned(url)) _pinned.value.filter { it.url != url } else _pinned.value + PinnedSite(url, title)
        val arr = org.json.JSONArray()
        now.forEach { arr.put(org.json.JSONObject().put("url", it.url).put("title", it.title)) }
        sp.edit { putString("pinned_sites", arr.toString()) }
        _pinned.value = now
        return isPinned(url)
    }

    /** 对单个网站关闭拦截的名单（按近似可注册域名保存）。 */
    private val _blockingAllowlist = MutableStateFlow(sp.getStringSet("blocking_allowlist", emptySet())!!.toSet())
    val blockingAllowlist: StateFlow<Set<String>> = _blockingAllowlist.asStateFlow()

    fun setSiteBlocking(baseDomain: String, enabled: Boolean) {
        val now = if (enabled) _blockingAllowlist.value - baseDomain else _blockingAllowlist.value + baseDomain
        sp.edit { putStringSet("blocking_allowlist", now) }
        _blockingAllowlist.value = now
    }

    /** 已向用户说明过“内核已换为系统 WebView、需重新登录”。 */
    var webViewMigrationNoticeShown: Boolean
        get() = sp.getBoolean("webview_migration_notice", false)
        set(v) = sp.edit { putBoolean("webview_migration_notice", v) }

    /** 本次运行中已就 Google 登录限制提示过（不持久化，每次启动提示一次）。 */
    @Volatile var googleSignInNoticeShown: Boolean = false

    var filtersUpdatedAt: Long
        get() = sp.getLong("filters_updated_at", 0)
        set(v) = sp.edit { putLong("filters_updated_at", v) }
}
