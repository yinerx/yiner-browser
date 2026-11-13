package io.yiner.browser.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { HOME, BROWSER, HISTORY, DOWNLOADS, ARCHIVED, SETTINGS }

private val pages = setOf(Screen.HISTORY, Screen.DOWNLOADS, Screen.ARCHIVED, Screen.SETTINGS)

/** 打开搜索/地址输入层的方式。 */
data class SearchRequest(
    val initialText: String,
    /** true：在当前标签中加载；false：新建标签。 */
    val inCurrentTab: Boolean,
    val isPrivate: Boolean,
)

/** 纯界面状态（与标签数据分开，标签数据在 TabManager）。 */
class UiState {
    var screen by mutableStateOf(Screen.HOME)
    var search by mutableStateOf<SearchRequest?>(null)
    var menuOpen by mutableStateOf(false)
    var findOpen by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var homePrivate by mutableStateOf(false)
    private var returnTo = Screen.HOME

    val onPage: Boolean get() = screen in pages

    val overlayOpen: Boolean get() = search != null || menuOpen || findOpen || onPage

    /** 打开历史/下载/归档/设置等二级页面，返回时回到打开前的界面。 */
    fun openPage(page: Screen) {
        if (!onPage) returnTo = screen
        menuOpen = false
        screen = page
    }

    /** 关闭最上层的一个浮层，返回是否关闭了什么。 */
    fun closeTopOverlay(): Boolean {
        when {
            search != null -> search = null
            menuOpen -> menuOpen = false
            findOpen -> findOpen = false
            onPage -> screen = returnTo
            else -> return false
        }
        return true
    }
}
