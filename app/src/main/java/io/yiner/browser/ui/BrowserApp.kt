package io.yiner.browser.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import io.yiner.browser.MainActivity
import io.yiner.browser.core.BackAction
import io.yiner.browser.core.BackContext
import io.yiner.browser.core.BackPolicy
import io.yiner.browser.ui.arc.ArcBottomBar
import kotlinx.coroutines.delay

@Composable
fun BrowserApp(activity: MainActivity) {
    val ui = activity.ui
    val tm = activity.app.tabManager
    val tabs by tm.tabs.collectAsState()
    val selectedId by tm.selectedId.collectAsState()
    val selected = tabs.firstOrNull { it.id == selectedId }
    val sessionEpoch by tm.sessionEpoch.collectAsState()

    // 选中的标签（或其 WebView）变化时，把它的 WebView 放进界面中唯一的网页容器
    LaunchedEffect(selected, sessionEpoch) {
        val container = activity.webContainer
        if (selected == null) {
            container.removeAllViews()
            if (ui.screen == Screen.BROWSER) ui.screen = Screen.TABS
            if (ui.fullscreen) activity.exitCustomView()
        } else {
            val view = tm.ensureView(selected)
            if (view.parent !== container) {
                container.removeAllViews()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                container.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            view.onResume()
        }
    }

    val selectedState = selected?.state?.collectAsState()?.value
    val currentThumb = selected?.thumbnail?.collectAsState()?.value

    // 页面加载完成后截一张缩略图，用于底栏和标签卡片
    LaunchedEffect(selectedId, selectedState?.loading, ui.screen) {
        if (ui.screen == Screen.BROWSER && selectedState?.loading == false) {
            delay(900)
            activity.captureThumbnail()
        }
    }

    val backAction = BackPolicy.decide(
        BackContext(
            inFullscreen = ui.fullscreen,
            overlayOpen = ui.overlayOpen,
            showingHome = ui.screen == Screen.TABS,
            hasTab = selected != null,
            canGoBack = selectedState?.canGoBack == true,
            tabOpenedByOtherApp = selected?.openedByOtherApp == true,
            tabHasParent = selected?.parentId?.let { pid -> tabs.any { it.id == pid } } == true,
        ),
    )
    BackHandler(enabled = backAction != BackAction.SYSTEM) {
        when (backAction) {
            BackAction.EXIT_FULLSCREEN -> activity.exitCustomView()
            BackAction.CLOSE_OVERLAY -> {
                if (ui.findOpen) selected?.view?.clearMatches()
                ui.closeTopOverlay()
            }
            BackAction.GO_BACK_IN_PAGE -> selected?.view?.goBack()
            BackAction.CLOSE_TAB_AND_RETURN_TO_APP -> {
                selected?.let { tm.close(it.id) }
                ui.screen = Screen.TABS
                activity.returnToCallingApp()
            }
            BackAction.CLOSE_TAB_AND_SHOW_PARENT -> selected?.let { tm.close(it.id) }
            BackAction.SHOW_HOME -> activity.showTabs()
            BackAction.SYSTEM -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(io.yiner.browser.ui.arc.LocalArcPalette.current.window)) {
        // 浏览界面（01）：网页占满主体，底部是轻薄工具栏；网页容器始终保留在界面树里
        Column(
            Modifier.fillMaxSize()
                .then(if (ui.fullscreen) Modifier else Modifier.statusBarsPadding().imePadding()),
        ) {
            AndroidView(
                factory = { activity.webContainer },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { it.visibility = if (ui.screen == Screen.BROWSER) View.VISIBLE else View.INVISIBLE },
            )
            if (!ui.fullscreen && selected != null && selectedState != null) {
                if (ui.findOpen) {
                    FindBar(selected, onClose = { ui.findOpen = false })
                } else {
                    val behind = tabs.filter { it.id != selected.id }.maxByOrNull { it.lastActiveAt }
                    ArcBottomBar(
                        current = currentThumb,
                        behind = behind?.thumbnail?.collectAsState()?.value,
                        tabCount = tabs.size,
                        isPrivate = selected.isPrivate,
                        progress = if (selectedState.loading) selectedState.progress / 100f else null,
                        onTabs = { activity.showTabs() },
                        onPlus = { ui.search = SearchRequest("", inCurrentTab = false, isPrivate = selected.isPrivate) },
                        onExpand = { activity.captureThumbnail(); ui.menuOpen = true },
                    )
                }
            }
        }

        if (ui.screen == Screen.TABS) TabSwitcherScreen(activity)

        when (ui.screen) {
            Screen.SETTINGS -> SettingsPage(activity)
            Screen.SEARCH_ENGINES -> SearchEnginesPage(activity)
            Screen.SITE_SETTINGS_GLOBAL -> GlobalSiteSettingsPage(activity)
            Screen.ARCHIVE_AFTER -> ArchiveAfterPage(activity)
            Screen.APPEARANCE -> AppearancePage(activity)
            Screen.HISTORY -> HistoryPage(activity)
            Screen.DOWNLOADS -> DownloadsPage(activity)
            Screen.ARCHIVED -> ArchivedPage(activity)
            else -> Unit
        }

        if (ui.menuOpen && selected != null && selectedState != null) {
            PageMenu(activity, selected, selectedState, onDismiss = { ui.menuOpen = false })
        }
        if (ui.siteSettingsOpen && selected != null && selectedState != null) {
            SiteSettingsSheet(activity, selected, selectedState, onDismiss = { ui.siteSettingsOpen = false })
        }

        ui.search?.let { SearchLayer(activity, it) }

        // 网页全屏（例如全屏视频）：覆盖整个界面
        if (ui.fullscreen) {
            AndroidView(factory = { activity.fullscreenContainer }, modifier = Modifier.fillMaxSize())
        }
    }
}
