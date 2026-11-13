package io.yiner.browser.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

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
    /** 当前站点已被用户设为“跟踪保护例外”。 */
    val trackingExempt: Boolean = false,
    /** 当前站点能否设置跟踪保护例外（GeckoView 是否给出了对应权限对象）。 */
    val trackingToggleAvailable: Boolean = false,
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
    /** GeckoView 序列化的会话历史（包括前进/后退栈），用于冷启动恢复。 */
    var sessionState: String?,
    var lastActiveAt: Long,
) {
    private val _state = MutableStateFlow(TabState(url = url, title = title, desktopMode = desktopMode))
    val state: StateFlow<TabState> = _state.asStateFlow()

    /** 为节省内存，长时间未用的非无痕标签会被释放会话，仅保留 [sessionState]。 */
    var session: GeckoSession? = null
    var trackingPermission: ContentPermission? = null

    fun update(transform: (TabState) -> TabState) = _state.update(transform)

    val url: String get() = _state.value.url
    val title: String get() = _state.value.title
    val displayTitle: String get() = title.ifBlank { url.ifBlank { "新标签页" } }
}
