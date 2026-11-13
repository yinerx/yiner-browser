package io.yiner.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yiner.browser.MainActivity
import io.yiner.browser.browser.Tab
import io.yiner.browser.core.UrlInput
import io.yiner.browser.data.SuggestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 主页：标签网格 + 底部搜索框。普通/无痕两组标签分开显示。 */
@Composable
fun HomeScreen(activity: MainActivity) {
    val ui = activity.ui
    val tm = activity.app.tabManager
    val allTabs by tm.tabs.collectAsState()
    val selectedId by tm.selectedId.collectAsState()
    val private = ui.homePrivate
    val tabs = allTabs.filter { it.isPrivate == private }.sortedByDescending { it.lastActiveAt }
    var menu by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("银耳", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (tabs.isNotEmpty()) {
                    TextButton(onClick = { tm.closeAll(private) }) { Text("全部关闭") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("已归档的标签") }, onClick = { menu = false; ui.openPage(Screen.ARCHIVED) })
                        DropdownMenuItem(text = { Text("历史记录") }, onClick = { menu = false; ui.openPage(Screen.HISTORY) })
                        DropdownMenuItem(text = { Text("下载") }, onClick = { menu = false; ui.openPage(Screen.DOWNLOADS) })
                        DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; ui.openPage(Screen.SETTINGS) })
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = !private, onClick = { ui.homePrivate = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("标签页 ${allTabs.count { !it.isPrivate }}") }
                SegmentedButton(
                    selected = private, onClick = { ui.homePrivate = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("无痕 ${allTabs.count { it.isPrivate }}") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tabs.isEmpty()) {
                    EmptyHome(private)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(tabs, key = { it.id }) { tab ->
                            TabCard(
                                tab = tab,
                                selected = tab.id == selectedId,
                                onOpen = {
                                    tm.select(tab.id)
                                    ui.screen = Screen.BROWSER
                                },
                                onClose = { tm.close(tab.id) },
                            )
                        }
                    }
                }
            }

            SearchPill(private) {
                ui.search = SearchRequest("", inCurrentTab = false, isPrivate = private)
            }
        }
    }
}

@Composable
private fun EmptyHome(private: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (private) "无痕浏览" else "银耳浏览器", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            if (private) "无痕标签不会记录历史；全部关闭后，Cookie 与网站数据随之清除。"
            else "搜索或输入网址开始浏览。长时间未使用的标签会自动归档。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TabCard(tab: Tab, selected: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val state by tab.state.collectAsState()
    val accent = if (tab.isPrivate) PrivateAccent else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().aspectRatio(0.95f)
            .then(if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(18.dp)) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val host = UrlInput.displayHost(state.url)
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (host.firstOrNull() ?: '·').uppercase(),
                        color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "关闭标签", modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                tab.displayTitle, maxLines = 4, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                UrlInput.displayHost(state.url), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchPill(private: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp).height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (private) PrivateAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            if (private) "无痕搜索或输入网址" else "搜索或输入网址",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface Suggestion {
    val text: String
    data class Remote(override val text: String) : Suggestion
    data class History(override val text: String, val title: String) : Suggestion
}

/** 全屏搜索/地址输入层：自动聚焦、历史匹配 + 搜索引擎联想。 */
@Composable
fun SearchLayer(activity: MainActivity, request: SearchRequest) {
    val ui = activity.ui
    val settings by activity.app.prefs.settings.collectAsState()
    var value by remember(request) {
        mutableStateOf(TextFieldValue(request.initialText, TextRange(0, request.initialText.length)))
    }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(value.text) {
        val q = value.text.trim()
        if (q.isEmpty() || q == request.initialText) {
            suggestions = emptyList(); return@LaunchedEffect
        }
        delay(150)
        val local = if (request.isPrivate) emptyList() else withContext(Dispatchers.IO) {
            activity.app.db.history(q, limit = 4).map { Suggestion.History(it.url, it.title) }
        }
        suggestions = local
        if (settings.searchSuggestions && !request.isPrivate) {
            val remote = SuggestClient.fetch(settings.searchEngine, q).map { Suggestion.Remote(it) }
            suggestions = local + remote
        }
    }

    fun go(text: String) = activity.submitSearch(text, request.inCurrentTab, request.isPrivate)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(suggestions) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { go(s.text) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            when (s) {
                                is Suggestion.History -> {
                                    Text(s.title.ifBlank { s.text }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        s.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                is Suggestion.Remote -> Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ui.search = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "取消") }
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text(if (request.isPrivate) "无痕搜索或输入网址" else "使用 ${settings.searchEngine.label} 搜索或输入网址") },
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = if (request.isPrivate) PrivateAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    trailingIcon = {
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { value = TextFieldValue("") }) { Icon(Icons.Filled.Clear, "清空") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { go(value.text) }),
                )
            }
        }
    }
}
