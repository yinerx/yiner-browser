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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig
import org.mozilla.geckoview.StorageController
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { actions() },
            )
            content()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatTime(t: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

// ---------------------------------------------------------------------------- 历史

@Composable
fun HistoryPage(activity: MainActivity) {
    val ui = activity.ui
    val db = activity.app.db
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, version) { items = withContext(Dispatchers.IO) { db.history(query) } }

    PageScaffold("历史记录", onBack = { ui.closeTopOverlay() }, actions = {
        TextButton(onClick = { confirmClear = true }) { Text("清空") }
    }) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("搜索历史记录") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        if (items.isEmpty()) EmptyHint(if (query.isBlank()) "暂无历史记录" else "没有匹配的记录")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.url }) { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { activity.openInNewTab(h.url) }
                        .padding(start = 20.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${UrlInput.displayHost(h.url)} · ${formatTime(h.visitedAt)}", maxLines = 1,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { db.deleteHistory(h.url) }; version++ }
                    }) { Icon(Icons.Filled.Close, "删除") }
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
    val ui = activity.ui
    val db = activity.app.db
    val changes by activity.app.downloads.version.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(changes, local) { items = withContext(Dispatchers.IO) { db.downloads() } }
    // 下载进行中时定期刷新进度
    LaunchedEffect(items) {
        if (items.any { it.status == DownloadStatus.RUNNING }) {
            kotlinx.coroutines.delay(1000); local++
        }
    }

    PageScaffold("下载", onBack = { ui.closeTopOverlay() }, actions = {
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.clearDownloads() }; local++ } }) {
            Text("清空列表")
        }
    }) {
        Text(
            "文件保存在系统“下载”目录。从列表移除不会删除文件。",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (items.isEmpty()) EmptyHint("暂无下载")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = d.status == DownloadStatus.DONE && d.contentUri != null) {
                            activity.openDownload(d.contentUri!!, d.mimeType)
                        }
                        .padding(start = 20.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when (d.status) {
                            DownloadStatus.RUNNING -> "下载中 ${DownloadNaming.formatSize(d.downloadedBytes)}" +
                                if (d.totalBytes > 0) " / ${DownloadNaming.formatSize(d.totalBytes)}" else ""
                            DownloadStatus.DONE -> "已完成 · ${DownloadNaming.formatSize(d.downloadedBytes)}"
                            DownloadStatus.FAILED -> "失败"
                            DownloadStatus.CANCELLED -> "已取消"
                        }
                        Text(
                            "$status · ${formatTime(d.createdAt)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (d.status != DownloadStatus.RUNNING) {
                        IconButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { db.deleteDownload(d.id) }; local++ }
                        }) { Icon(Icons.Filled.Close, "从列表移除") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 已归档标签

@Composable
fun ArchivedPage(activity: MainActivity) {
    val ui = activity.ui
    val db = activity.app.db
    val tm = activity.app.tabManager
    val archiveVersion by tm.archiveVersion.collectAsState()
    val settings by activity.app.prefs.settings.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<ArchivedTab>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(archiveVersion, local) { items = withContext(Dispatchers.IO) { db.archivedTabs() } }

    PageScaffold("已归档的标签", onBack = { ui.closeTopOverlay() }, actions = {
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { db.clearArchived() }; local++ } }) {
            Text("清空")
        }
    }) {
        Text(
            if (settings.archiveAfter == ArchiveAfter.OFF) "自动归档已关闭。"
            else "超过 ${settings.archiveAfter.label} 未使用的标签会自动移到这里。点按即可恢复。",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (items.isEmpty()) EmptyHint("没有已归档的标签")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        tm.restoreArchived(a)
                        activity.showBrowser()
                    }.padding(start = 20.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${UrlInput.displayHost(a.url)} · 归档于 ${formatTime(a.archivedAt)}", maxLines = 1,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { db.removeArchived(a.id) }; local++ }
                    }) { Icon(Icons.Filled.Close, "删除") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 设置

@Composable
fun SettingsPage(activity: MainActivity) {
    val ui = activity.ui
    val app = activity.app
    val settings by app.prefs.settings.collectAsState()
    val adStatus by app.adBlocker.status.collectAsState()
    val isDefault by activity.isDefaultBrowser
    var showClear by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    fun update(transform: (io.yiner.browser.data.Settings) -> io.yiner.browser.data.Settings) {
        app.prefs.update(transform)
        AdBlocker.applyRuntimeSettings(app.runtime, app.prefs.settings.value)
    }

    PageScaffold("设置", onBack = { ui.closeTopOverlay() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("默认浏览器")
            MenuItem(
                if (isDefault) "银耳浏览器已是默认浏览器" else "设为默认浏览器",
                subtitle = if (isDefault) "其它应用中的链接会用银耳浏览器打开" else "需要在系统对话框或系统设置中确认",
            ) { if (isDefault) activity.openDefaultAppsSettings() else activity.requestDefaultBrowser() }

            Section("搜索")
            SearchEngine.entries.forEach { e ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = settings.searchEngine == e, role = Role.RadioButton) {
                            update { it.copy(searchEngine = e) }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.searchEngine == e, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(e.label)
                }
            }
            SwitchItem(
                "搜索建议", settings.searchSuggestions,
                subtitle = "输入时将关键词发送给所选搜索引擎以获取联想词；无痕标签中不会发送",
            ) { v -> update { it.copy(searchSuggestions = v) } }

            Section("标签页")
            Text(
                "自动归档未使用的标签",
                Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyLarge,
            )
            ArchiveAfter.entries.forEach { a ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = settings.archiveAfter == a, role = Role.RadioButton) {
                            update { it.copy(archiveAfter = a) }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.archiveAfter == a, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(a.label)
                }
            }
            MenuItem("查看已归档的标签") { ui.openPage(Screen.ARCHIVED) }

            Section("隐私与屏蔽")
            val adSubtitle = when (val s = adStatus) {
                AdBlocker.Status.Installing -> "正在安装 uBlock Origin…"
                is AdBlocker.Status.Ready -> "uBlock Origin ${s.version}" + if (s.enabled) " · 已启用" else " · 已停用"
                is AdBlocker.Status.Failed -> "不可用：${s.message}"
            }
            SwitchItem("广告屏蔽", settings.adBlockEnabled, subtitle = adSubtitle) { v ->
                app.adBlocker.setEnabled(app.runtime, v)
            }
            if (adStatus is AdBlocker.Status.Ready) {
                MenuItem("uBlock Origin 设置", subtitle = "过滤规则列表、信任站点（白名单）、自定义规则") {
                    val url = app.adBlocker.optionsPageUrl
                    if (url != null) activity.openInNewTab(url) else activity.toast("uBlock Origin 未提供设置页")
                }
                MenuItem("检查 uBlock Origin 更新", subtitle = "通过 addons.mozilla.org 检查（过滤规则由 uBlock Origin 自动更新）") {
                    activity.toast("正在检查…")
                    app.adBlocker.checkForUpdate(app.runtime) { activity.toast(it) }
                }
            }
            SwitchItem(
                "严格跟踪保护", settings.strictTrackingProtection,
                subtitle = "额外拦截全部社交媒体跟踪器与指纹识别脚本；少数网站或登录流程可能异常，可在页面菜单中对单个网站关闭",
            ) { v -> update { it.copy(strictTrackingProtection = v) } }
            SwitchItem(
                "仅 HTTPS 模式", settings.httpsOnly,
                subtitle = "自动升级到加密连接；不支持 HTTPS 的网站将无法打开",
            ) { v -> update { it.copy(httpsOnly = v) } }
            MenuItem("清除浏览数据") { showClear = true }

            Section("关于")
            MenuItem(
                "银耳浏览器 ${BuildConfig.VERSION_NAME}",
                subtitle = "内核 GeckoView ${GeckoBuildConfig.MOZ_APP_VERSION}（Mozilla）。个人自用的独立项目，与任何浏览器厂商无关。",
            ) {}
            MenuItem("开源许可") { showLicenses = true }
            Spacer(Modifier.height(24.dp))
        }
    }

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

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title, Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
    )
}

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
