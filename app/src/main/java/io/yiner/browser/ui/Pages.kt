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
import io.yiner.browser.browser.AdBlocker
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
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig
import org.mozilla.geckoview.StorageController
import java.text.DateFormat
import java.util.Date

private fun formatTime(t: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

private fun MainActivity.updateSettings(transform: (Settings) -> Settings) {
    app.prefs.update(transform)
    AdBlocker.applyRuntimeSettings(app.runtime, app.prefs.settings.value)
}

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

    AppSettingsContent(
        AppSettingsModel(
            isDefaultBrowser = isDefault,
            searchEngineLabel = settings.searchEngine.label,
            archiveLabel = if (settings.archiveAfter == ArchiveAfter.OFF) "从不" else "${settings.archiveAfter.label}后",
            autoOpenKeyboard = settings.autoOpenKeyboard,
            searchSuggestions = settings.searchSuggestions,
            versionName = BuildConfig.VERSION_NAME,
            engineDescription = "内核 GeckoView ${GeckoBuildConfig.MOZ_APP_VERSION}（Mozilla）· 个人自用的独立项目",
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
            }
        },
        onAutoOpenKeyboard = { v -> activity.updateSettings { it.copy(autoOpenKeyboard = v) } },
        onSearchSuggestions = { v -> activity.updateSettings { it.copy(searchSuggestions = v) } },
        updateSection = { UpdateSection(activity) },
    )

    if (showClear) ClearDataDialog(activity) { showClear = false }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "GeckoView — Mozilla Public License 2.0\nhttps://www.mozilla.org/MPL/2.0/\n\n" +
                        "uBlock Origin ${AdBlocker.BUNDLED_VERSION}（Raymond Hill 等）— GPL-3.0\n" +
                        "源代码：https://github.com/gorhill/uBlock\n本应用内置的是 addons.mozilla.org 发布的官方签名版本，未做任何修改。\n\n" +
                        "AndroidX / Jetpack Compose / Kotlin — Apache License 2.0",
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
    val adStatus by app.adBlocker.status.collectAsState()
    SettingsScaffold("全局网站设置", onBack = { activity.ui.closeTopOverlay() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val adSubtitle = when (val s = adStatus) {
                AdBlocker.Status.Installing -> "正在安装 uBlock Origin…"
                is AdBlocker.Status.Ready -> "uBlock Origin ${s.version}"
                is AdBlocker.Status.Failed -> "不可用：${s.message}"
            }
            SectionHeader("拦截")
            SettingsRow(ArcIcons.Shield, "拦截广告", subtitle = adSubtitle, trailing = {
                ArcSwitch(settings.adBlockEnabled) { v -> app.adBlocker.setEnabled(app.runtime, v) }
            })
            SettingsRow(ArcIcons.Shield, "严格跟踪保护", subtitle = "额外拦截社交跟踪与指纹识别；个别网站或登录流程可能异常", trailing = {
                ArcSwitch(settings.strictTrackingProtection) { v -> activity.updateSettings { it.copy(strictTrackingProtection = v) } }
            })
            SettingsRow(ArcIcons.Lock, "仅 HTTPS 模式", subtitle = "不支持 HTTPS 的网站将无法打开", trailing = {
                ArcSwitch(settings.httpsOnly) { v -> activity.updateSettings { it.copy(httpsOnly = v) } }
            })
            if (adStatus is AdBlocker.Status.Ready) {
                SectionHeader("uBlock Origin")
                SettingsRow(null, "过滤规则与信任站点", subtitle = "打开 uBlock Origin 控制面板") {
                    val url = app.adBlocker.optionsPageUrl
                    if (url != null) activity.openInNewTab(url) else activity.toast("uBlock Origin 未提供设置页")
                }
                SettingsRow(null, "检查 uBlock Origin 更新", subtitle = "通过 addons.mozilla.org") {
                    activity.toast("正在检查…")
                    app.adBlocker.checkForUpdate(app.runtime) { activity.toast(it) }
                }
            }
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
                var flags = 0L
                if (cookies) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA or
                    StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
                if (cache) flags = flags or StorageController.ClearFlags.ALL_CACHES
                if (flags != 0L) app.runtime.storageController.clearData(flags)
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
