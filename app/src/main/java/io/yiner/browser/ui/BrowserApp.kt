package io.yiner.browser.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.yiner.browser.MainActivity
import io.yiner.browser.browser.Tab
import io.yiner.browser.browser.TabState
import io.yiner.browser.core.BackAction
import io.yiner.browser.core.BackContext
import io.yiner.browser.core.BackPolicy
import io.yiner.browser.core.UrlInput

@Composable
fun BrowserApp(activity: MainActivity) {
    val ui = activity.ui
    val tm = activity.app.tabManager
    val tabs by tm.tabs.collectAsState()
    val selectedId by tm.selectedId.collectAsState()
    val selected = tabs.firstOrNull { it.id == selectedId }
    val sessionEpoch by tm.sessionEpoch.collectAsState()

    // 选中的标签（或其会话）变化时，把它的会话挂到唯一的 GeckoView 上
    LaunchedEffect(selected, sessionEpoch) {
        val gv = activity.geckoView
        if (selected == null) {
            if (gv.session != null) gv.releaseSession()
            if (ui.screen == Screen.BROWSER) ui.screen = Screen.HOME
            if (ui.fullscreen) activity.setFullscreen(false)
        } else {
            val session = tm.ensureSession(selected)
            if (gv.session !== session) {
                if (gv.session != null) gv.releaseSession()
                gv.setSession(session)
            }
            session.setActive(true)
        }
    }

    val selectedState = selected?.state?.collectAsState()?.value

    val backAction = BackPolicy.decide(
        BackContext(
            inFullscreen = ui.fullscreen,
            overlayOpen = ui.overlayOpen,
            showingHome = ui.screen == Screen.HOME,
            hasTab = selected != null,
            canGoBack = selectedState?.canGoBack == true,
            tabOpenedByOtherApp = selected?.openedByOtherApp == true,
            tabHasParent = selected?.parentId?.let { pid -> tabs.any { it.id == pid } } == true,
        ),
    )
    BackHandler(enabled = backAction != BackAction.SYSTEM) {
        when (backAction) {
            BackAction.EXIT_FULLSCREEN -> selected?.session?.exitFullScreen() ?: activity.setFullscreen(false)
            BackAction.CLOSE_OVERLAY -> {
                if (ui.findOpen) selected?.session?.finder?.clear()
                ui.closeTopOverlay()
            }
            BackAction.GO_BACK_IN_PAGE -> selected?.session?.goBack()
            BackAction.CLOSE_TAB_AND_RETURN_TO_APP -> {
                selected?.let { tm.close(it.id) }
                ui.screen = Screen.HOME
                activity.returnToCallingApp()
            }
            BackAction.CLOSE_TAB_AND_SHOW_PARENT -> selected?.let { tm.close(it.id) }
            BackAction.SHOW_HOME -> {
                ui.homePrivate = selected?.isPrivate == true
                ui.screen = Screen.HOME
            }
            BackAction.SYSTEM -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 浏览界面：GeckoView 始终保留在界面树里，避免反复重建 Surface
        Column(
            Modifier.fillMaxSize()
                .then(if (ui.fullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding().imePadding()),
        ) {
            AndroidView(
                factory = { activity.geckoView },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { it.visibility = if (ui.screen == Screen.BROWSER) View.VISIBLE else View.INVISIBLE },
            )
            if (!ui.fullscreen && selected != null && selectedState != null) {
                if (ui.findOpen) {
                    FindBar(selected, onClose = { ui.findOpen = false })
                } else {
                    BrowserBar(activity, selected, selectedState, tabCount = tabs.count { it.isPrivate == selected.isPrivate })
                }
            }
        }

        if (ui.screen == Screen.HOME) HomeScreen(activity)

        when (ui.screen) {
            Screen.HISTORY -> HistoryPage(activity)
            Screen.DOWNLOADS -> DownloadsPage(activity)
            Screen.ARCHIVED -> ArchivedPage(activity)
            Screen.SETTINGS -> SettingsPage(activity)
            else -> Unit
        }

        ui.search?.let { SearchLayer(activity, it) }

        if (ui.menuOpen && selected != null && selectedState != null) {
            PageMenu(activity, selected, selectedState, onDismiss = { ui.menuOpen = false })
        }
    }
}

@Composable
private fun BrowserBar(activity: MainActivity, tab: Tab, state: TabState, tabCount: Int) {
    val ui = activity.ui
    val private = tab.isPrivate
    Surface(color = if (private) PrivateAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            AnimatedVisibility(visible = state.loading) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (state.canGoBack) tab.session?.goBack() else ui.screen = Screen.HOME },
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退") }

                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            ui.search = SearchRequest(state.url, inCurrentTab = true, isPrivate = private)
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.secure) {
                        Icon(
                            Icons.Filled.Lock, contentDescription = "安全连接",
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (private) {
                        Text("无痕", color = PrivateAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        UrlInput.displayHost(state.url).ifBlank { state.title.ifBlank { "搜索或输入网址" } },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                TabCountButton(tabCount, private) {
                    ui.homePrivate = private
                    ui.screen = Screen.HOME
                }
                IconButton(onClick = { ui.menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                }
            }
        }
    }
}

@Composable
fun TabCountButton(count: Int, private: Boolean, onClick: () -> Unit) {
    val color = if (private) PrivateAccent else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.padding(start = 6.dp).size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "∞" else count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
