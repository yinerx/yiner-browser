package io.yiner.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.yiner.browser.MainActivity
import io.yiner.browser.browser.Tab
import io.yiner.browser.browser.TabState
import org.mozilla.geckoview.GeckoSession

/** 页内查找栏。 */
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

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it; find(backwards = false, again = false) },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                placeholder = { Text("在页面中查找") },
                suffix = { Text(status, style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find(backwards = false, again = true) }),
            )
            IconButton(onClick = { find(backwards = true, again = true) }) { Icon(Icons.Filled.KeyboardArrowUp, "上一个") }
            IconButton(onClick = { find(backwards = false, again = true) }) { Icon(Icons.Filled.KeyboardArrowDown, "下一个") }
            IconButton(onClick = ::close) { Icon(Icons.Filled.Close, "关闭查找") }
        }
    }
}

/** 页面菜单（底部弹出）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageMenu(activity: MainActivity, tab: Tab, state: TabState, onDismiss: () -> Unit) {
    val ui = activity.ui
    val tm = activity.app.tabManager
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun act(block: () -> Unit) {
        onDismiss()
        block()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(Icons.AutoMirrored.Filled.ArrowForward, "前进", enabled = state.canGoForward) {
                    act { tab.session?.goForward() }
                }
                QuickAction(if (state.loading) Icons.Filled.Close else Icons.Filled.Refresh, if (state.loading) "停止" else "刷新") {
                    act { if (state.loading) tab.session?.stop() else tab.session?.reload() }
                }
                QuickAction(Icons.Filled.Share, "分享") { act { activity.shareUrl(tab) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            MenuItem("新建标签页") { act { ui.search = SearchRequest("", inCurrentTab = false, isPrivate = false) } }
            MenuItem("新建无痕标签页") { act { ui.search = SearchRequest("", inCurrentTab = false, isPrivate = true) } }
            MenuItem("在页面中查找") { act { ui.findOpen = true } }
            MenuItem("复制链接") { act { activity.copyUrl(tab) } }
            SwitchItem("桌面版网站", state.desktopMode) { tm.setDesktopMode(tab, it); onDismiss() }
            if (state.trackingToggleAvailable) {
                SwitchItem(
                    "此网站启用跟踪保护",
                    !state.trackingExempt,
                    subtitle = "关闭后此网站不再拦截跟踪器（不影响 uBlock Origin）",
                ) { enabled -> tm.setTrackingExempt(tab, exempt = !enabled); onDismiss() }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuItem("历史记录") { ui.openPage(Screen.HISTORY) }
            MenuItem("下载") { ui.openPage(Screen.DOWNLOADS) }
            MenuItem("设置") { ui.openPage(Screen.SETTINGS) }
            MenuItem("关闭此标签页") { act { tm.close(tab.id) } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
fun MenuItem(label: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchItem(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
