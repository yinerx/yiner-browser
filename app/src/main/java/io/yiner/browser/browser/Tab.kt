package io.yiner.browser.browser

import android.webkit.WebView
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 界面需要的标签状态快照。 */
data class TabState(
    val url: String,
    val title: String,
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val secure: Boolean = false,
    val desktopMode: Boolean = false,
    /** 当前站点是否启用广告与跟踪拦截（用户可在“网站设置”中对单个网站关闭）。 */
    val siteBlockingEnabled: Boolean = true,
    /** 本页被拦截的网络请求数（EasyList / EasyPrivacy 网络规则；元素隐藏不计入）。 */
    val blockedCount: Int = 0,
)

class Tab(
    val id: String,
    val isPrivate: Boolean,
    var parentId: String?,
    /** 由其它 App 通过链接打开：在根页面返回时关闭标签并回到那个 App。 */
    var openedByOtherApp: Boolean,
    url: String,
    title: String,
    desktopMode: Boolean,
    /** 序列化的前进/后退历史（见 [SessionCodec]），用于冷启动恢复。 */
    var sessionState: String?,
    var lastActiveAt: Long,
) {
    private val _state = MutableStateFlow(TabState(url = url, title = title, desktopMode = desktopMode))
    val state: StateFlow<TabState> = _state.asStateFlow()

    /** 为节省内存，长时间未用的普通标签会销毁 WebView，仅保留 [sessionState]。 */
    var view: WebView? = null

    /** 当前主文档的主机名，供请求拦截判断“第三方”。在 WebView 的后台线程读取。 */
    @Volatile var pageHost: String? = null

    /** 网页缩略图（仅内存，用于底栏与标签切换卡片）。 */
    val thumbnail = MutableStateFlow<ImageBitmap?>(null)

    fun update(transform: (TabState) -> TabState) = _state.update(transform)

    val url: String get() = _state.value.url
    val title: String get() = _state.value.title
    val displayTitle: String get() = title.ifBlank { url.ifBlank { "新标签页" } }
}
