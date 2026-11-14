package io.yiner.browser.core

/** 返回手势当时的界面状态。 */
data class BackContext(
    val inFullscreen: Boolean,
    val overlayOpen: Boolean,
    val showingHome: Boolean,
    val hasTab: Boolean,
    val canGoBack: Boolean,
    val tabOpenedByOtherApp: Boolean,
    val tabHasParent: Boolean,
)

enum class BackAction {
    EXIT_FULLSCREEN,
    CLOSE_OVERLAY,
    GO_BACK_IN_PAGE,
    CLOSE_TAB_AND_RETURN_TO_APP,
    CLOSE_TAB_AND_SHOW_PARENT,
    /** 回到标签切换页（原“主页”；界面改为横向卡片后不再有单独主页）。 */
    SHOW_HOME,

    /** 不拦截，交给系统（回到桌面，支持预测性返回动画）。 */
    SYSTEM,
}

object BackPolicy {
    fun decide(c: BackContext): BackAction = when {
        c.inFullscreen -> BackAction.EXIT_FULLSCREEN
        c.overlayOpen -> BackAction.CLOSE_OVERLAY
        c.showingHome || !c.hasTab -> BackAction.SYSTEM
        c.canGoBack -> BackAction.GO_BACK_IN_PAGE
        c.tabOpenedByOtherApp -> BackAction.CLOSE_TAB_AND_RETURN_TO_APP
        c.tabHasParent -> BackAction.CLOSE_TAB_AND_SHOW_PARENT
        else -> BackAction.SHOW_HOME
    }
}
