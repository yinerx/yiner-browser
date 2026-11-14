package io.yiner.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yiner.browser.core.UrlInput
import io.yiner.browser.ui.arc.ActionTiles
import io.yiner.browser.ui.arc.AddressPill
import io.yiner.browser.ui.arc.ArcIcons
import io.yiner.browser.ui.arc.ArcSheet
import io.yiner.browser.ui.arc.ArcSwitch
import io.yiner.browser.ui.arc.ArcTokens
import io.yiner.browser.ui.arc.ChoiceRow
import io.yiner.browser.ui.arc.MenuRow
import io.yiner.browser.ui.arc.PrivateTint
import io.yiner.browser.ui.arc.SearchPill
import io.yiner.browser.ui.arc.SectionHeader
import io.yiner.browser.ui.arc.SettingsRow
import io.yiner.browser.ui.arc.SettingsScaffold
import io.yiner.browser.ui.arc.SiteBadge
import io.yiner.browser.ui.arc.SuggestionRow
import io.yiner.browser.ui.arc.TileItem

/*
 * 无状态的界面内容：只接收数据与回调，不依赖 Activity。
 * 应用里由 PageMenu / SearchLayer / SettingsPage 等接线后使用，测试中可直接离线渲染与参考图对照。
 */

// ============================================================ 页面菜单（02）

data class PageMenuModel(
    val url: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val loading: Boolean,
    val pinned: Boolean,
    val textZoomPercent: Int,
    val desktopMode: Boolean,
    val blockedCount: Int,
)

class PageMenuActions(
    val onBack: () -> Unit = {},
    val onForward: () -> Unit = {},
    val onEditAddress: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onRefreshOrStop: () -> Unit = {},
    val onTogglePin: () -> Unit = {},
    val onFind: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onZoom: (Int) -> Unit = {},
    val onDesktop: (Boolean) -> Unit = {},
    val onSiteSettings: () -> Unit = {},
    val onNewPrivateTab: () -> Unit = {},
)

@Composable
fun PageMenuContent(m: PageMenuModel, a: PageMenuActions) {
    ArcSheet {
        Spacer(Modifier.height(ArcTokens.SheetTopPadding))
        AddressPill(
            host = UrlInput.displayHost(m.url),
            canGoBack = m.canGoBack,
            canGoForward = m.canGoForward,
            loading = m.loading,
            onBack = a.onBack,
            onForward = a.onForward,
            onHost = a.onEditAddress,
            onCopyLink = a.onCopyLink,
            onRefresh = a.onRefreshOrStop,
        )
        Spacer(Modifier.height(ArcTokens.TilesTopGap))
        // 02 中第一格是 Translate；翻译不在银耳浏览器的功能范围内，不做也不留占位
        ActionTiles(
            listOf(
                TileItem(ArcIcons.Pin, if (m.pinned) "取消固定" else "固定", a.onTogglePin),
                TileItem(ArcIcons.Find, "查找", a.onFind),
                TileItem(ArcIcons.Share, "分享", a.onShare),
            ),
        )
        // 02：四宫格底部到第一行中心约 174 px ≈ 64dp（行高一半 36.5dp + 28dp）
        Spacer(Modifier.height(28.dp))
        MenuRow(ArcIcons.Zoom, "文字缩放", subtitle = "${m.textZoomPercent}%") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { a.onZoom(m.textZoomPercent + 10) }) {
                    Icon(ArcIcons.Plus, "放大", Modifier.size(24.dp), tint = ArcTokens.TextPrimary)
                }
                Spacer(Modifier.size(12.dp))
                IconButton(onClick = { a.onZoom(m.textZoomPercent - 10) }) {
                    Icon(ArcIcons.Minus, "缩小", Modifier.size(24.dp), tint = ArcTokens.TextPrimary)
                }
            }
        }
        MenuRow(ArcIcons.Desktop, "桌面版网站", onClick = { a.onDesktop(!m.desktopMode) }) {
            Checkbox(
                checked = m.desktopMode,
                onCheckedChange = { a.onDesktop(it) },
                colors = CheckboxDefaults.colors(checkedColor = ArcTokens.SwitchOn, uncheckedColor = ArcTokens.TextPrimary),
            )
        }
        MenuRow(ArcIcons.Gear, "网站设置", onClick = a.onSiteSettings)
        // 截图中没有无痕入口的证据，此行是银耳浏览器自己的补充
        MenuRow(ArcIcons.Incognito, "新建无痕标签页", onClick = a.onNewPrivateTab)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(ArcTokens.MenuDivider))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text(
                if (m.blockedCount > 0) "已拦截 ${m.blockedCount} 个广告和跟踪请求" else "本页未拦截到广告和跟踪请求",
                fontSize = ArcTokens.MenuTitleSize, color = ArcTokens.TextPrimary,
            )
            Text(
                "有问题？可在网站设置中关闭",
                fontSize = ArcTokens.MenuSubtitleSize, color = ArcTokens.TextSecondary,
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

// ============================================================ 搜索面板（03）

/** 搜索面板里的一行。 */
sealed interface SearchItem {
    val key: String

    data class OpenTab(val tabId: String, val title: String, val host: String, val isPrivate: Boolean) : SearchItem {
        override val key get() = "tab:$tabId"
    }
    data class Pinned(val url: String, val title: String) : SearchItem {
        override val key get() = "pin:$url"
    }
    data class History(val url: String, val title: String) : SearchItem {
        override val key get() = "his:$url"
    }
    data class Suggest(val text: String) : SearchItem {
        override val key get() = "sug:$text"
    }
}

@Composable
fun SearchPanelContent(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isPrivate: Boolean,
    items: List<SearchItem>,
    focusRequester: FocusRequester,
    onGo: () -> Unit,
    onItem: (SearchItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(ArcTokens.SearchScrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Spacer(Modifier.weight(1f))
            ArcSheet {
                Spacer(Modifier.height(ArcTokens.SheetTopPadding))
                if (isPrivate) {
                    Text(
                        "无痕标签：不记录历史，关闭全部无痕标签后清除 Cookie",
                        Modifier.padding(start = 28.dp, bottom = 10.dp), fontSize = 14.sp, color = PrivateTint, fontWeight = FontWeight.Medium,
                    )
                }
                SearchPill(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = if (isPrivate) "无痕搜索…" else "搜索…",
                    focusRequester = focusRequester,
                    onGo = onGo,
                    trailing = if (value.text.isNotEmpty()) {
                        {
                            IconButton(onClick = { onValueChange(TextFieldValue("")) }) {
                                Icon(ArcIcons.Close, "清空", Modifier.size(22.dp), tint = ArcTokens.Placeholder)
                            }
                        }
                    } else {
                        null
                    },
                )
                // 03：胶囊底到第一行中心约 144 px ≈ 53dp（半行 33dp + 20dp）
                Spacer(Modifier.height(20.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = ArcTokens.SuggestionRowHeight * 4.5f)) {
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is SearchItem.OpenTab -> SuggestionRow(
                                item.title,
                                leading = { SiteBadge(item.host.ifBlank { item.title }, ArcTokens.SuggestionIcon, item.isPrivate) },
                                onClick = { onItem(item) },
                            )
                            is SearchItem.Pinned -> SuggestionRow(
                                item.title.ifBlank { item.url },
                                leading = { SiteBadge(UrlInput.displayHost(item.url), ArcTokens.SuggestionIcon) },
                                onClick = { onItem(item) },
                                trailing = { Icon(ArcIcons.Pin, "已固定", Modifier.size(22.dp), tint = ArcTokens.TextSecondary) },
                            )
                            is SearchItem.History -> SuggestionRow(
                                item.title.ifBlank { item.url },
                                leading = { SiteBadge(UrlInput.displayHost(item.url), ArcTokens.SuggestionIcon) },
                                onClick = { onItem(item) },
                            )
                            is SearchItem.Suggest -> SuggestionRow(
                                item.text,
                                leading = { Icon(ArcIcons.Search, null, Modifier.size(24.dp), tint = ArcTokens.TextSecondary) },
                                onClick = { onItem(item) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

// ============================================================ 应用设置（05）

data class AppSettingsModel(
    val isDefaultBrowser: Boolean,
    val searchEngineLabel: String,
    val archiveLabel: String,
    val autoOpenKeyboard: Boolean,
    val searchSuggestions: Boolean,
    val versionName: String,
    val engineDescription: String,
    /** 外观的当前选择（跟随系统 / 浅色 / 深色）。 */
    val themeLabel: String = "跟随系统",
    /** 旧内核（GeckoView）遗留数据的大小；为 null 表示没有遗留数据，不显示该项。 */
    val oldEngineDataLabel: String? = null,
)

enum class SettingsTarget { GLOBAL_SITE, DEFAULT_BROWSER, SEARCH_ENGINES, ARCHIVED, ARCHIVE_AFTER, CLEAR_DATA, DOWNLOADS, HISTORY, LICENSES, OLD_ENGINE_DATA, APPEARANCE }

@Composable
fun AppSettingsContent(
    m: AppSettingsModel,
    onBack: () -> Unit,
    onOpen: (SettingsTarget) -> Unit,
    onAutoOpenKeyboard: (Boolean) -> Unit,
    onSearchSuggestions: (Boolean) -> Unit,
    updateSection: @Composable () -> Unit,
) {
    SettingsScaffold("设置", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader("全局")
            SettingsRow(ArcIcons.Globe, "全局网站设置", subtitle = "广告屏蔽、跟踪保护、仅 HTTPS") { onOpen(SettingsTarget.GLOBAL_SITE) }

            SectionHeader("默认")
            SettingsRow(
                ArcIcons.BrowserWindow,
                if (m.isDefaultBrowser) "已是默认浏览器" else "将银耳浏览器设为默认浏览器",
                onClick = { onOpen(SettingsTarget.DEFAULT_BROWSER) },
            )
            SettingsRow(ArcIcons.Search, "搜索引擎", subtitle = m.searchEngineLabel) { onOpen(SettingsTarget.SEARCH_ENGINES) }

            SectionHeader("存储")
            SettingsRow(ArcIcons.Archive, "已归档", onClick = { onOpen(SettingsTarget.ARCHIVED) })
            SettingsRow(ArcIcons.ArchiveTimer, "归档不活跃的标签", subtitle = m.archiveLabel) { onOpen(SettingsTarget.ARCHIVE_AFTER) }
            SettingsRow(ArcIcons.Eraser, "清除浏览数据", onClick = { onOpen(SettingsTarget.CLEAR_DATA) })
            SettingsRow(ArcIcons.Folder, "下载", onClick = { onOpen(SettingsTarget.DOWNLOADS) })
            SettingsRow(ArcIcons.History, "历史记录", onClick = { onOpen(SettingsTarget.HISTORY) })
            if (m.oldEngineDataLabel != null) {
                SettingsRow(ArcIcons.Eraser, "清理旧内核数据", subtitle = "GeckoView 内核遗留 ${m.oldEngineDataLabel}，当前内核无法使用") {
                    onOpen(SettingsTarget.OLD_ENGINE_DATA)
                }
            }

            SectionHeader("选项")
            SettingsRow(ArcIcons.ThemeAuto, "外观", subtitle = m.themeLabel) { onOpen(SettingsTarget.APPEARANCE) }
            SettingsRow(ArcIcons.Keyboard, "自动弹出键盘", subtitle = "打开应用时直接显示搜索面板", trailing = {
                ArcSwitch(m.autoOpenKeyboard, onAutoOpenKeyboard)
            })
            SettingsRow(ArcIcons.Search, "搜索建议", subtitle = "向所选搜索引擎请求联想词；无痕标签不请求", trailing = {
                ArcSwitch(m.searchSuggestions, onSearchSuggestions)
            })

            SectionHeader("更新")
            updateSection()

            SectionHeader("关于")
            SettingsRow(ArcIcons.Info, "银耳浏览器 ${m.versionName}", subtitle = m.engineDescription)
            SettingsRow(null, "开源许可", onClick = { onOpen(SettingsTarget.LICENSES) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================ 搜索引擎（06）

@Composable
fun SearchEnginesContent(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    SettingsScaffold("搜索引擎", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("默认搜索引擎")
            labels.forEachIndexed { i, label ->
                ChoiceRow(label, selected == i, onClick = { onSelect(i) }) { SiteBadge(label, ArcTokens.EngineIcon) }
            }
        }
    }
}

// ============================================================ 外观（与 06 搜索引擎页同样的单选列表样式）

@Composable
fun AppearanceContent(selected: io.yiner.browser.core.ThemeMode, onSelect: (io.yiner.browser.core.ThemeMode) -> Unit, onBack: () -> Unit) {
    SettingsScaffold("外观", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("主题")
            io.yiner.browser.core.ThemeMode.entries.forEach { mode ->
                val icon = when (mode) {
                    io.yiner.browser.core.ThemeMode.SYSTEM -> ArcIcons.ThemeAuto
                    io.yiner.browser.core.ThemeMode.LIGHT -> ArcIcons.Sun
                    io.yiner.browser.core.ThemeMode.DARK -> ArcIcons.Moon
                }
                ChoiceRow(mode.label, selected == mode, onClick = { onSelect(mode) }) {
                    Icon(icon, null, Modifier.size(ArcTokens.EngineIcon * 0.8f), tint = ArcTokens.TextPrimary)
                }
            }
            io.yiner.browser.ui.arc.SettingsNote(
                "“跟随系统”时随手机的浅色/深色设置即时切换；选择浅色或深色后不再跟随系统。" +
                    "网页按其自身的深色样式（prefers-color-scheme）显示，没有深色样式的网页保持原样，不做反色；切换外观时已打开的网页会重新加载。",
            )
        }
    }
}
