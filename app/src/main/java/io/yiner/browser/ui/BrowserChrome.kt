package io.yiner.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yiner.browser.MainActivity
import io.yiner.browser.browser.Tab
import io.yiner.browser.browser.TabState
import io.yiner.browser.core.UrlInput
import io.yiner.browser.ui.arc.ArcIcons
import io.yiner.browser.ui.arc.ArcSheet
import io.yiner.browser.ui.arc.ArcSwitch
import io.yiner.browser.ui.arc.ArcTokens
import io.yiner.browser.ui.arc.SettingsRow
import org.mozilla.geckoview.GeckoSession

/**
 * 带遮罩的底部面板容器。点遮罩关闭。
 * 进出场用上滑/淡入，这是银耳浏览器的选择：参考截图是静态的，不能证明原版的动效与曲线。
 */
@Composable
fun SheetOverlay(scrim: Color, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            Box(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** 页面菜单（参考图 02）。 */
@Composable
fun PageMenu(activity: MainActivity, tab: Tab, state: TabState, onDismiss: () -> Unit) {
    val ui = activity.ui
    val tm = activity.app.tabManager
    val prefs = activity.app.prefs
    val settings by prefs.settings.collectAsState()
    val pinned by prefs.pinned.collectAsState()

    fun act(block: () -> Unit): () -> Unit = {
        onDismiss()
        block()
    }

    SheetOverlay(ArcTokens.MenuScrim, onDismiss) {
        PageMenuContent(
            PageMenuModel(
                url = state.url,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                loading = state.loading,
                pinned = pinned.any { it.url == state.url },
                textZoomPercent = settings.textZoomPercent,
                desktopMode = state.desktopMode,
                blockedCount = state.blockedCount,
            ),
            PageMenuActions(
                onBack = act { tab.session?.goBack() },
                onForward = act { tab.session?.goForward() },
                onEditAddress = act { ui.search = SearchRequest(state.url, inCurrentTab = true, isPrivate = tab.isPrivate) },
                onCopyLink = act { activity.copyUrl(tab) },
                onRefreshOrStop = act { if (state.loading) tab.session?.stop() else tab.session?.reload() },
                onTogglePin = act {
                    val now = prefs.togglePin(state.url, tab.displayTitle)
                    activity.toast(if (now) "已固定，会显示在搜索面板中" else "已取消固定")
                },
                onFind = act { ui.findOpen = true },
                onShare = act { activity.shareUrl(tab) },
                onZoom = { activity.setTextZoom(it) },
                onDesktop = { v -> onDismiss(); tm.setDesktopMode(tab, v) },
                onSiteSettings = { ui.menuOpen = false; ui.siteSettingsOpen = true },
                onNewPrivateTab = act { ui.search = SearchRequest("", inCurrentTab = false, isPrivate = true) },
            ),
        )
    }
}

/**
 * 单个网站的设置面板（参考 Google Play 宣传图中的 “Site Settings” 面板）。
 * “拦截 Cookie 横幅”没有做：GeckoView 160 未提供对应接口。
 */
@Composable
fun SiteSettingsSheet(activity: MainActivity, tab: Tab, state: TabState, onDismiss: () -> Unit) {
    val app = activity.app
    val settings by app.prefs.settings.collectAsState()
    SheetOverlay(ArcTokens.MenuScrim, onDismiss) {
        ArcSheet {
            Text("网站设置", Modifier.padding(start = 24.dp, top = 28.dp), fontSize = 28.sp, color = ArcTokens.TextPrimary)
            Text(
                "${UrlInput.displayHost(state.url).ifBlank { "此网站" }} 设置",
                Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp), fontSize = 16.sp, color = ArcTokens.TextSecondary,
            )
            if (state.trackingToggleAvailable) {
                SettingsRow(ArcIcons.Shield, "拦截跟踪器", subtitle = "仅对此网站生效", trailing = {
                    ArcSwitch(!state.trackingExempt) { enabled -> app.tabManager.setTrackingExempt(tab, exempt = !enabled) }
                })
            } else {
                SettingsRow(ArcIcons.Shield, "拦截跟踪器", subtitle = "此页面不支持单独设置")
            }
            SettingsRow(ArcIcons.Shield, "拦截广告（全局）", subtitle = "要放行单个网站，请在 uBlock Origin 的信任站点中添加", trailing = {
                ArcSwitch(settings.adBlockEnabled) { v -> app.adBlocker.setEnabled(app.runtime, v) }
            })
            SettingsRow(null, "打开 uBlock Origin 信任站点", onClick = {
                val url = app.adBlocker.optionsPageUrl
                onDismiss()
                if (url != null) activity.openInNewTab(url) else activity.toast("uBlock Origin 尚未就绪")
            })
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** 页内查找栏（截图中没有出现查找界面，这里沿用银耳浏览器自己的样式，配色与底栏一致）。 */
@Composable
fun FindBar(tab: Tab, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun find(backwards: Boolean, again: Boolean) {
        val finder = tab.session?.finder ?: return
        if (query.isEmpty()) {
            finder.clear(); status = ""; return
        }
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
        finder.find(if (again) null else query, flags).accept { r ->
            status = when {
                r == null -> ""
                !r.found -> "无结果"
                else -> "${r.current}/${r.total}"
            }
        }
    }

    fun close() {
        tab.session?.finder?.clear()
        onClose()
    }

    Column(Modifier.fillMaxWidth().background(ArcTokens.PanelGray)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ArcTokens.BarDivider))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(CircleShape).background(Color.White).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("在页面中查找", fontSize = 17.sp, color = ArcTokens.Placeholder)
                    BasicTextField(
                        value = query, onValueChange = { query = it; find(backwards = false, again = false) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = ArcTokens.TextPrimary),
                        cursorBrush = SolidColor(ArcTokens.SwitchOn),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { find(backwards = false, again = true) }),
                    )
                }
                Text(status, fontSize = 14.sp, color = ArcTokens.TextSecondary)
            }
            IconButton(onClick = { find(backwards = true, again = true) }) { Icon(ArcIcons.ChevronUp, "上一个", tint = ArcTokens.TextPrimary) }
            IconButton(onClick = { find(backwards = false, again = true) }) {
                Icon(ArcIcons.ChevronUp, "下一个", Modifier.size(24.dp).rotate(180f), tint = ArcTokens.TextPrimary)
            }
            IconButton(onClick = ::close) { Icon(ArcIcons.Close, "关闭查找", tint = ArcTokens.TextPrimary) }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 设置页中的开关行（供“更新”分区使用）。 */
@Composable
fun SwitchItem(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    SettingsRow(null, label, subtitle = subtitle, onClick = { onChange(!checked) }, trailing = { ArcSwitch(checked, onChange) })
}
