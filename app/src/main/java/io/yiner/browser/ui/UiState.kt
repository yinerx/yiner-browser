package io.yiner.browser.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面层级（参考 Arc Search Android beta 截图）：
 * - TABS：横向标签卡片（04），没有标签时也停在这里，并叠加搜索面板；不再有单独的“主页”网格；
 * - BROWSER：网页占满，底部轻薄工具栏（01）；
 * - 设置及其二级页（05/06）。
 */
enum class Screen { TABS, BROWSER, SETTINGS, SEARCH_ENGINES, SITE_SETTINGS_GLOBAL, ARCHIVE_AFTER, APPEARANCE, HISTORY, DOWNLOADS, ARCHIVED }

private val pages = setOf(
    Screen.SETTINGS, Screen.SEARCH_ENGINES, Screen.SITE_SETTINGS_GLOBAL, Screen.ARCHIVE_AFTER, Screen.APPEARANCE,
    Screen.HISTORY, Screen.DOWNLOADS, Screen.ARCHIVED,
)

/** 打开搜索面板的方式。 */
data class SearchRequest(
    val initialText: String,
    /** true：在当前标签中加载；false：新建标签。 */
    val inCurrentTab: Boolean,
    val isPrivate: Boolean,
)

/** 可被返回手势逐层关闭的界面层。 */
enum class Overlay { SEARCH, SITE_SETTINGS, MENU, PAGE, FIND }

class UiState {
    var screen by mutableStateOf(Screen.TABS)
    var search by mutableStateOf<SearchRequest?>(null)
    var menuOpen by mutableStateOf(false)
    var siteSettingsOpen by mutableStateOf(false)
    var findOpen by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    /** 二级页面返回栈：从哪里进入设置，就回到哪里。 */
    private val backStack = mutableStateListOf<Screen>()

    val onPage: Boolean get() = screen in pages

    val overlayOpen: Boolean get() = search != null || menuOpen || siteSettingsOpen || findOpen || onPage

    fun openPage(page: Screen) {
        menuOpen = false
        siteSettingsOpen = false
        if (screen != page) {
            backStack.add(screen)
            screen = page
        }
    }

    /**
     * 关闭最上层的一个浮层或页面，返回关闭的是哪一层（没有可关闭的返回 null）。
     * 顺序与界面叠放一致：搜索面板 > 菜单类面板 > 设置及其二级页 > 页内查找栏。
     * 查找栏属于网页界面，设置页盖在它上面时必须先关页面，否则返回会先关掉看不见的查找栏。
     */
    fun closeTopOverlay(): Overlay? {
        val top = when {
            search != null -> Overlay.SEARCH
            siteSettingsOpen -> Overlay.SITE_SETTINGS
            menuOpen -> Overlay.MENU
            onPage -> Overlay.PAGE
            findOpen -> Overlay.FIND
            else -> return null
        }
        when (top) {
            Overlay.SEARCH -> search = null
            Overlay.SITE_SETTINGS -> siteSettingsOpen = false
            Overlay.MENU -> menuOpen = false
            Overlay.PAGE -> screen = backStack.removeLastOrNull() ?: Screen.TABS
            Overlay.FIND -> findOpen = false
        }
        return top
    }

    fun leavePages(to: Screen) {
        backStack.clear()
        screen = to
    }
}
