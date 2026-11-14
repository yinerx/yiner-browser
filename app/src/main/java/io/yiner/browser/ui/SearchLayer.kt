package io.yiner.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yiner.browser.MainActivity
import io.yiner.browser.core.UrlInput
import io.yiner.browser.data.SuggestClient
import io.yiner.browser.ui.arc.ArcIcons
import io.yiner.browser.ui.arc.ArcSheet
import io.yiner.browser.ui.arc.ArcTokens
import io.yiner.browser.ui.arc.PrivateTint
import io.yiner.browser.ui.arc.SearchPill
import io.yiner.browser.ui.arc.SiteBadge
import io.yiner.browser.ui.arc.SuggestionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 叠在当前界面上的搜索面板（参考图 03）：
 * 背后是淡紫→淡粉的半透明色调，底部浅灰圆角面板，白色胶囊搜索框，配合系统键盘。
 * 03 胶囊右侧的“双眼”是 Browse for Me（AI）入口——银耳浏览器不做 AI，也不留假入口。
 */
@Composable
fun SearchLayer(activity: MainActivity, request: SearchRequest) {
    val ui = activity.ui
    val app = activity.app
    val settings by app.prefs.settings.collectAsState()
    val pinned by app.prefs.pinned.collectAsState()
    val tabs by app.tabManager.tabs.collectAsState()
    var value by remember(request) {
        mutableStateOf(TextFieldValue(request.initialText, TextRange(0, request.initialText.length)))
    }
    var typed by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(value.text) {
        val q = value.text.trim()
        if (q.isEmpty() || q == request.initialText) {
            typed = emptyList(); return@LaunchedEffect
        }
        delay(150)
        val local = if (request.isPrivate) emptyList() else withContext(Dispatchers.IO) {
            app.db.history(q, limit = 3).map { SearchItem.History(it.url, it.title) }
        }
        typed = local
        if (settings.searchSuggestions && !request.isPrivate) {
            typed = local + SuggestClient.fetch(settings.searchEngine, q).map { SearchItem.Suggest(it) }
        }
    }

    // 未输入时：固定的网站 + 已打开的标签（03 中输入框下方即为带网站图标的标签行）
    val idle: List<SearchItem> = remember(pinned, tabs, request.isPrivate) {
        pinned.map { SearchItem.Pinned(it.url, it.title) } +
            tabs.filter { it.isPrivate == request.isPrivate && it.url.isNotBlank() && pinned.none { p -> p.url == it.url } }
                .sortedByDescending { it.lastActiveAt }
                .map { SearchItem.OpenTab(it.id, it.displayTitle, UrlInput.displayHost(it.url), it.isPrivate) }
    }
    val items = if (value.text.isBlank() || value.text == request.initialText) idle else typed

    fun go(text: String) = activity.submitSearch(text, request.inCurrentTab, request.isPrivate)

    SearchPanelContent(
        value = value,
        onValueChange = { value = it },
        isPrivate = request.isPrivate,
        items = items,
        focusRequester = focus,
        onGo = { go(value.text) },
        onItem = { item ->
            when (item) {
                is SearchItem.OpenTab -> {
                    app.tabManager.select(item.tabId)
                    activity.showBrowser()
                }
                is SearchItem.Pinned -> go(item.url)
                is SearchItem.History -> go(item.url)
                is SearchItem.Suggest -> go(item.text)
            }
        },
        onDismiss = { ui.search = null },
    )
}
