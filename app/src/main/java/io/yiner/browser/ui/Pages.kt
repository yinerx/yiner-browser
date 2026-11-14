package io.yiner.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yiner.browser.BuildConfig
import io.yiner.browser.MainActivity
import io.yiner.browser.blocking.ContentBlocker
import io.yiner.browser.browser.TabManager
import io.yiner.browser.core.ArchiveAfter
import io.yiner.browser.core.DownloadNaming
import io.yiner.browser.core.SearchEngine
import io.yiner.browser.core.UrlInput
import io.yiner.browser.data.ArchivedTab
import io.yiner.browser.data.DownloadRecord
import io.yiner.browser.data.DownloadStatus
import io.yiner.browser.data.HistoryEntry
import io.yiner.browser.data.Settings
import io.yiner.browser.ui.arc.ArcIcons
import io.yiner.browser.ui.arc.ArcSwitch
import io.yiner.browser.ui.arc.ArcTokens
import io.yiner.browser.ui.arc.ChoiceRow
import io.yiner.browser.ui.arc.SectionHeader
import io.yiner.browser.ui.arc.SettingsNote
import io.yiner.browser.ui.arc.SettingsRow
import io.yiner.browser.ui.arc.SettingsScaffold
import io.yiner.browser.ui.arc.SiteBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun formatTime(t: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

private fun MainActivity.updateSettings(transform: (Settings) -> Settings) {
    app.prefs.update(transform)
    app.tabManager.applyThirdPartyCookies(app.prefs.settings.value.thirdPartyCookies)
}

/** 旧内核（GeckoView，0.2.0 及更早）留下的配置目录。不会自动删除，只能由用户在设置中手动清理。 */
private fun oldEngineDirs(activity: MainActivity) = listOf(File(activity.filesDir, "mozilla"), File(activity.cacheDir, "mozilla"))
    .filter { it.exists() }

private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

@Composable
private fun Empty(text: String) = SettingsNote(text)

// ---------------------------------------------------------------------------- 应用设置（05）

@Composable
fun SettingsPage(activity: MainActivity) {
    val ui = activity.ui
    val app = activity.app
    val settings by app.prefs.settings.collectAsState()
    val isDefault by activity.isDefaultBrowser
    var showClear by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showOldEngine by remember { mutableStateOf(false) }
    var oldEngineBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showOldEngine) {
        oldEngineBytes = withContext(Dispatchers.IO) {
            oldEngineDirs(activity).takeIf { it.isNotEmpty() }?.sumOf { dirSize(it) }
        }
    }

    AppSettingsContent(
        AppSettingsModel(
            isDefaultBrowser = isDefault,
            searchEngineLabel = settings.searchEngine.label,
            archiveLabel = if (settings.archiveAfter == ArchiveAfter.OFF) "从不" else "${settings.archiveAfter.label}后",
            autoOpenKeyboard = settings.autoOpenKeyboard,
            searchSuggestions = settings.searchSuggestions,
            versionName = BuildConfig.VERSION_NAME,
            engineDescription = "内核：基于 Chromium 的系统 WebView（${TabManager.webViewPackageDescription(activity)}）· 个人自用的独立项目",
            oldEngineDataLabel = oldEngineBytes?.let { DownloadNaming.formatSize(it) },
            themeLabel = settings.themeMode.label,
        ),
        onBack = { ui.closeTopOverlay() },
        onOpen = { t ->
            when (t) {
                SettingsTarget.GLOBAL_SITE -> ui.openPage(Screen.SITE_SETTINGS_GLOBAL)
                SettingsTarget.DEFAULT_BROWSER -> if (isDefault) activity.openDefaultAppsSettings() else activity.requestDefaultBrowser()
                SettingsTarget.SEARCH_ENGINES -> ui.openPage(Screen.SEARCH_ENGINES)
                SettingsTarget.ARCHIVED -> ui.openPage(Screen.ARCHIVED)
                SettingsTarget.ARCHIVE_AFTER -> ui.openPage(Screen.ARCHIVE_AFTER)
                SettingsTarget.CLEAR_DATA -> showClear = true
                SettingsTarget.DOWNLOADS -> ui.openPage(Screen.DOWNLOADS)
                SettingsTarget.HISTORY -> ui.openPage(Screen.HISTORY)
                SettingsTarget.LICENSES -> showLicenses = true
                SettingsTarget.OLD_ENGINE_DATA -> showOldEngine = true
                SettingsTarget.APPEARANCE -> ui.openPage(Screen.APPEARANCE)
            }
        },
        onAutoOpenKeyboard = { v -> activity.updateSettings { it.copy(autoOpenKeyboard = v) } },
        onSearchSuggestions = { v -> activity.updateSettings { it.copy(searchSuggestions = v) } },
        updateSection = { UpdateSection(activity) },
    )

    if (showClear) ClearDataDialog(activity) { showClear = false }
    if (showOldEngine) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showOldEngine = false },
            title = { Text("清理旧内核数据？") },
            text = {
                Text(
                    "这是 0.2.0 及更早版本（GeckoView 内核）留下的 Cookie、网站存储和缓存，当前的系统 WebView 内核无法读取。" +
                        "清理后不可恢复；历史记录、下载列表和标签不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { oldEngineDirs(activity).forEach { it.deleteRecursively() } }
                        activity.toast("已清理旧内核数据")
                        showOldEngine = false
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showOldEngine = false }) { Text("取消") } },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "网页内核：设备上的 Android System WebView（基于 Chromium，由系统 / Google Play 提供和更新，不随本应用分发）\n\n" +
                        "EasyList 与 EasyPrivacy 过滤规则 — The EasyList authors (https://easylist.to/)，GPL-3.0 / CC BY-SA 3.0 双许可，未修改\n\n" +
                        "AndroidX（含 androidx.webkit）/ Jetpack Compose / Kotlin — Apache License 2.0",
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("好") } },
        )
    }
}

// ---------------------------------------------------------------------------- 搜索引擎（06）

@Composable
fun SearchEnginesPage(activity: MainActivity) {
    val settings by activity.app.prefs.settings.collectAsState()
    val engines = SearchEngine.entries
    SearchEnginesContent(
        labels = engines.map { it.label },
        selected = engines.indexOf(settings.searchEngine),
        onSelect = { i -> activity.updateSettings { it.copy(searchEngine = engines[i]) } },
        onBack = { activity.ui.closeTopOverlay() },
    )
}

// ---------------------------------------------------------------------------- 全局网站设置

@Composable
fun GlobalSiteSettingsPage(activity: MainActivity) {
    val app = activity.app
    val settings by app.prefs.settings.collectAsState()
    val status by app.blocker.status.collectAsState()
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    SettingsScaffold("全局网站设置", onBack = { activity.ui.closeTopOverlay() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader("拦截")
            SettingsRow(ArcIcons.Shield, "拦截广告", subtitle = "EasyList 规则：拦截广告请求并隐藏广告位", trailing = {
                ArcSwitch(settings.adBlockEnabled) { v -> activity.updateSettings { it.copy(adBlockEnabled = v) } }
            })
            SettingsRow(ArcIcons.Shield, "拦截跟踪器", subtitle = "EasyPrivacy 规则：拦截分析与跟踪请求", trailing = {
                ArcSwitch(settings.blockTrackers) { v -> activity.updateSettings { it.copy(blockTrackers = v) } }
            })
            val rules = when (val s = status) {
                ContentBlocker.Status.Loading -> "正在加载规则…"
                is ContentBlocker.Status.Ready ->
                    "${s.versions}\n可执行的网络规则 ${s.ads.networkRules + s.trackers.networkRules} 条，元素隐藏规则 ${s.ads.cosmeticRules + s.trackers.cosmeticRules} 条；" +
                        "跳过 ${s.ads.skippedRules + s.trackers.skippedRules} 条不支持的规则（通配符、正则、资源类型选项、脚本注入等）"
                is ContentBlocker.Status.Failed -> "规则加载失败：${s.message}"
            }
            SettingsRow(null, if (updating) "正在更新规则…" else "更新过滤规则", subtitle = rules, onClick = {
                if (updating) return@SettingsRow
                updating = true
                scope.launch {
                    activity.toast(app.blocker.updateLists())
                    updating = false
                }
            })
            SettingsNote("拦截在 WebView 的请求拦截接口中进行，只覆盖网页发起的普通网络请求；不能修改网页脚本，也无法拦截 WebSocket。对单个网站关闭拦截请用页面菜单中的“网站设置”。")

            SectionHeader("隐私")
            SettingsRow(ArcIcons.Lock, "仅 HTTPS 模式", subtitle = "自动把网页地址从 http 升级为 https；不支持 HTTPS 的网站将无法打开", trailing = {
                ArcSwitch(settings.httpsOnly) { v -> activity.updateSettings { it.copy(httpsOnly = v) } }
            })
            SettingsRow(ArcIcons.Globe, "允许第三方 Cookie", subtitle = "默认关闭；部分网站的嵌入式登录或评论组件需要开启", trailing = {
                ArcSwitch(settings.thirdPartyCookies) { v -> activity.updateSettings { it.copy(thirdPartyCookies = v) } }
            })
        }
    }
}

// ---------------------------------------------------------------------------- 归档时长

@Composable
fun ArchiveAfterPage(activity: MainActivity) {
    val settings by activity.app.prefs.settings.collectAsState()
    SettingsScaffold("归档不活跃的标签", onBack = { activity.ui.closeTopOverlay() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("多久未使用后归档")
            ArchiveAfter.entries.forEach { a ->
                ChoiceRow(if (a == ArchiveAfter.OFF) "从不" else "${a.label}后", settings.archiveAfter == a, onClick = {
                    activity.updateSettings { it.copy(archiveAfter = a) }
                }) { Icon(ArcIcons.ArchiveTimer, null, Modifier.padding(start = 0.dp), tint = ArcTokens.TextPrimary) }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 历史

@Composable
fun HistoryPage(activity: MainActivity) {
    val db = activity.app.db
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, version) { items = withContext(Dispatchers.IO) { db.history(query) } }

    SettingsScaffold("历史记录", onBack = { activity.ui.closeTopOverlay() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜索历史记录") }, shape = CircleShape,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmClear = true }) { Text("清空") }
        }
        if (items.isEmpty()) Empty(if (query.isBlank()) "暂无历史记录" else "没有匹配的记录")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.url }) { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { activity.openInNewTab(h.url) }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteBadge(UrlInput.displayHost(h.url), ArcTokens.SuggestionIcon)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(h.title.ifBlank { h.url }, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ArcTokens.TextPrimary)
                        Text("${UrlInput.displayHost(h.url)} · ${formatTime(h.visitedAt)}", fontSize = 13.sp, maxLines = 1, color = ArcTokens.TextSecondary)
                    }
                    IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.deleteHistory(h.url) }; version++ } }) {
                        Icon(ArcIcons.Close, "删除", tint = ArcTokens.TextSecondary)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史记录？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { withContext(Dispatchers.IO) { db.clearHistory() }; version++ }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

// ---------------------------------------------------------------------------- 下载

@Composable
fun DownloadsPage(activity: MainActivity) {
    val db = activity.app.db
    val changes by activity.app.downloads.version.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(changes, local) { items = withContext(Dispatchers.IO) { db.downloads() } }
    LaunchedEffect(items) {
        if (items.any { it.status == DownloadStatus.RUNNING }) {
            kotlinx.coroutines.delay(1000); local++
        }
    }

    SettingsScaffold("下载", onBack = { activity.ui.closeTopOverlay() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "文件保存在系统“下载”目录。从列表移除不会删除文件。",
                Modifier.weight(1f).padding(start = 20.dp), fontSize = 14.sp, color = ArcTokens.TextSecondary,
            )
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.clearDownloads() }; local++ } }) { Text("清空列表") }
        }
        if (items.isEmpty()) Empty("暂无下载")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = d.status == DownloadStatus.DONE && d.contentUri != null) {
                            activity.openDownload(d.contentUri!!, d.mimeType)
                        }
                        .padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ArcIcons.Download, null, tint = ArcTokens.TextPrimary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(d.fileName, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ArcTokens.TextPrimary)
                        val status = when (d.status) {
                            DownloadStatus.RUNNING -> "下载中 ${DownloadNaming.formatSize(d.downloadedBytes)}" +
                                if (d.totalBytes > 0) " / ${DownloadNaming.formatSize(d.totalBytes)}" else ""
                            DownloadStatus.DONE -> "已完成 · ${DownloadNaming.formatSize(d.downloadedBytes)}"
                            DownloadStatus.FAILED -> "失败"
                            DownloadStatus.CANCELLED -> "已取消"
                        }
                        Text("$status · ${formatTime(d.createdAt)}", fontSize = 13.sp, color = ArcTokens.TextSecondary)
                    }
                    if (d.status != DownloadStatus.RUNNING) {
                        IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.deleteDownload(d.id) }; local++ } }) {
                            Icon(ArcIcons.Close, "从列表移除", tint = ArcTokens.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 已归档

@Composable
fun ArchivedPage(activity: MainActivity) {
    val db = activity.app.db
    val tm = activity.app.tabManager
    val archiveVersion by tm.archiveVersion.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<ArchivedTab>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(archiveVersion, local) { items = withContext(Dispatchers.IO) { db.archivedTabs() } }

    SettingsScaffold("已归档", onBack = { activity.ui.closeTopOverlay() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("点按即可恢复标签。", Modifier.weight(1f).padding(start = 20.dp), fontSize = 14.sp, color = ArcTokens.TextSecondary)
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.clearArchived() }; local++ } }) { Text("清空") }
        }
        if (items.isEmpty()) Empty("没有已归档的标签")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        tm.restoreArchived(a)
                        activity.showBrowser()
                    }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteBadge(UrlInput.displayHost(a.url), ArcTokens.SuggestionIcon)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(a.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ArcTokens.TextPrimary)
                        Text("${UrlInput.displayHost(a.url)} · 归档于 ${formatTime(a.archivedAt)}", fontSize = 13.sp, maxLines = 1, color = ArcTokens.TextSecondary)
                    }
                    IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.removeArchived(a.id) }; local++ } }) {
                        Icon(ArcIcons.Close, "删除", tint = ArcTokens.TextSecondary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 清除数据

@Composable
private fun ClearDataDialog(activity: MainActivity, onDismiss: () -> Unit) {
    val app = activity.app
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(true) }
    var downloads by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    @Composable
    fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(label)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清除浏览数据") },
        text = {
            Column {
                Check("历史记录", history) { history = it }
                Check("Cookie 和网站数据（会退出所有网站登录）", cookies) { cookies = it }
                Check("缓存的图片和文件", cache) { cache = it }
                Check("下载列表（不删除文件）", downloads) { downloads = it }
                Check("已归档的标签", archived) { archived = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 只清除普通标签（默认 Profile）的数据；无痕数据在关闭全部无痕标签时已清空
                if (cookies) {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                }
                if (cache) android.webkit.WebView(activity).apply { clearCache(true); destroy() }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (history) app.db.clearHistory()
                        if (downloads) app.db.clearDownloads()
                        if (archived) app.db.clearArchived()
                    }
                    app.downloads.changed()
                    activity.toast("已清除")
                    onDismiss()
                }
            }) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------- 外观

@Composable
fun AppearancePage(activity: MainActivity) {
    val settings by activity.app.prefs.settings.collectAsState()
    AppearanceContent(settings.themeMode, onSelect = { activity.setThemeMode(it) }, onBack = { activity.ui.closeTopOverlay() })
}
