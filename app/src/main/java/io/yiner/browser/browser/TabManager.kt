package io.yiner.browser.browser

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.yiner.browser.core.ArchiveCandidate
import io.yiner.browser.core.ArchivePolicy
import io.yiner.browser.data.ArchivedTab
import io.yiner.browser.data.BrowserDb
import io.yiner.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.HistoryDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.util.UUID
import io.yiner.browser.core.LinkDecision
import io.yiner.browser.core.LinkPolicy

/**
 * 管理全部标签及其 GeckoSession。所有方法都在主线程调用（GeckoView 的委托也在主线程回调）。
 */
class TabManager(
    private val context: Context,
    private val runtimeProvider: () -> GeckoRuntime,
    private val db: BrowserDb,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val prompts: PromptHandler,
    private val permissions: PermissionHandler,
) {
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** 选中标签的会话被替换（例如内容进程崩溃后重建）时递增，界面据此重新挂载 GeckoView。 */
    private val _sessionEpoch = MutableStateFlow(0)
    val sessionEpoch: StateFlow<Int> = _sessionEpoch.asStateFlow()

    /** 数据库写入串行执行，保证“记录访问”先于“更新标题”。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 每次归档后递增，供界面刷新“已归档”列表。 */
    private val _archiveVersion = MutableStateFlow(0)
    val archiveVersion: StateFlow<Int> = _archiveVersion.asStateFlow()

    var uiHost: UiHost? = null

    /** 网页通过 target=_blank / window.open 打开了新标签。 */
    var onTabOpenedByPage: ((Tab) -> Unit)? = null

    private val stateFile = AtomicFile(File(context.filesDir, "tabs.json"))
    private var saveJob: Job? = null
    private var restored = false

    val selectedTab: Tab? get() = _tabs.value.firstOrNull { it.id == _selectedId.value }

    fun find(id: String?): Tab? = _tabs.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 恢复与保存

    /** 从磁盘恢复上次的普通标签（只建模型，不创建会话，选中时再懒加载）。 */
    fun restore() {
        if (restored) return
        restored = true
        val json = runCatching { String(stateFile.readFully(), Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(json)
            val arr = root.getJSONArray("tabs")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Tab(
                    id = o.getString("id"),
                    isPrivate = false,
                    parentId = o.optString("parent").ifEmpty { null },
                    openedByOtherApp = false,
                    url = o.optString("url"),
                    title = o.optString("title"),
                    desktopMode = o.optBoolean("desktop"),
                    sessionState = o.optString("state").ifEmpty { null },
                    lastActiveAt = o.optLong("lastActive", System.currentTimeMillis()),
                )
            }
            _tabs.value = list
            val sel = prefs.selectedTabId
            _selectedId.value = list.firstOrNull { it.id == sel }?.id
        }.onFailure { Log.w(TAG, "恢复标签失败", it) }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            saveNow()
        }
    }

    /** 立即保存（Activity 进入后台时调用）。无痕标签永不落盘。 */
    fun saveNow() {
        val arr = JSONArray()
        _tabs.value.filter { !it.isPrivate }.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("parent", t.parentId ?: "")
                    .put("url", t.url)
                    .put("title", t.title)
                    .put("desktop", t.state.value.desktopMode)
                    .put("state", t.sessionState ?: "")
                    .put("lastActive", t.lastActiveAt),
            )
        }
        val payload = JSONObject().put("tabs", arr).toString().toByteArray(Charsets.UTF_8)
        val selected = selectedTab?.takeIf { !it.isPrivate }?.id
        scope.launch(Dispatchers.IO) {
            prefs.selectedTabId = selected
            val out = runCatching { stateFile.startWrite() }.getOrNull() ?: return@launch
            try {
                out.write(payload)
                stateFile.finishWrite(out)
            } catch (e: Exception) {
                stateFile.failWrite(out)
                Log.w(TAG, "保存标签失败", e)
            }
        }
    }

    // ------------------------------------------------------------------ 标签操作

    fun newTab(
        url: String?,
        isPrivate: Boolean,
        parentId: String? = null,
        openedByOtherApp: Boolean = false,
        select: Boolean = true,
    ): Tab {
        val tab = Tab(
            id = UUID.randomUUID().toString(),
            isPrivate = isPrivate,
            parentId = parentId,
            openedByOtherApp = openedByOtherApp,
            url = url.orEmpty(),
            title = "",
            desktopMode = false,
            sessionState = null,
            lastActiveAt = System.currentTimeMillis(),
        )
        _tabs.value = _tabs.value + tab
        // ensureSession 会按 tab.url 加载
        if (!url.isNullOrBlank()) ensureSession(tab)
        if (select) select(tab.id)
        scheduleSave()
        return tab
    }

    fun select(id: String?) {
        val prev = selectedTab
        val tab = find(id)
        if (prev != null && prev !== tab) prev.session?.setActive(false)
        _selectedId.value = tab?.id
        if (tab != null) {
            tab.lastActiveAt = System.currentTimeMillis()
            ensureSession(tab).setActive(true)
            trimSessions()
        }
        scheduleSave()
    }

    /** 在当前标签加载；没有标签时新建。 */
    fun load(url: String, isPrivate: Boolean = false) {
        val tab = selectedTab
        if (tab == null || tab.isPrivate != isPrivate) {
            newTab(url, isPrivate)
        } else {
            tab.update { it.copy(url = url) }
            ensureSession(tab).loadUri(url)
            tab.lastActiveAt = System.currentTimeMillis()
        }
    }

    fun close(id: String) {
        val tab = find(id) ?: return
        val remaining = _tabs.value.filter { it.id != id }
        // 子标签的父级指向被关闭标签的父级，保持返回链
        remaining.forEach { if (it.parentId == id) it.parentId = tab.parentId }
        _tabs.value = remaining
        tab.session?.close()
        tab.session = null
        if (_selectedId.value == id) {
            val parent = remaining.firstOrNull { it.id == tab.parentId && it.isPrivate == tab.isPrivate }
            _selectedId.value = null
            if (parent != null) select(parent.id)
        }
        scheduleSave()
    }

    fun closeAll(isPrivate: Boolean) {
        _tabs.value.filter { it.isPrivate == isPrivate }.forEach { close(it.id) }
    }

    fun setDesktopMode(tab: Tab, enabled: Boolean) {
        tab.update { it.copy(desktopMode = enabled) }
        tab.session?.settings?.apply {
            userAgentMode = if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            viewportMode = if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        tab.session?.reload()
        scheduleSave()
    }

    /** 将当前站点加入/移出 Gecko 增强型跟踪保护例外。 */
    fun setTrackingExempt(tab: Tab, exempt: Boolean): Boolean {
        val perm = tab.trackingPermission ?: return false
        runtimeProvider().storageController.setPermission(
            perm,
            if (exempt) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
        )
        tab.update { it.copy(trackingExempt = exempt) }
        tab.session?.reload()
        return true
    }

    // ------------------------------------------------------------------ 归档

    fun runAutoArchive() {
        val after = prefs.settings.value.archiveAfter
        val now = System.currentTimeMillis()
        val ids = ArchivePolicy.tabsToArchive(
            _tabs.value.map { ArchiveCandidate(it.id, it.lastActiveAt, it.isPrivate) }, now, after,
        ).toSet()
        if (ids.isEmpty()) return
        val toArchive = _tabs.value.filter { it.id in ids && it.url.isNotBlank() }
        val records = toArchive.map { ArchivedTab(it.id, it.url, it.displayTitle, it.sessionState, now) }
        scope.launch(Dispatchers.IO) {
            db.archive(records)
            withContext(Dispatchers.Main) { _archiveVersion.value++ }
        }
        ids.forEach { close(it) }
    }

    fun restoreArchived(archived: ArchivedTab) {
        val tab = Tab(
            id = UUID.randomUUID().toString(),
            isPrivate = false, parentId = null, openedByOtherApp = false,
            url = archived.url, title = archived.title, desktopMode = false,
            sessionState = archived.sessionState, lastActiveAt = System.currentTimeMillis(),
        )
        _tabs.value = _tabs.value + tab
        select(tab.id)
        scope.launch(Dispatchers.IO) {
            db.removeArchived(archived.id)
            withContext(Dispatchers.Main) { _archiveVersion.value++ }
        }
    }

    // ------------------------------------------------------------------ 会话

    /** 保证标签有会话；没有时新建并从保存的状态或 URL 恢复。 */
    fun ensureSession(tab: Tab): GeckoSession {
        tab.session?.let { return it }
        val session = buildSession(tab)
        tab.session = session
        session.open(runtimeProvider())
        val saved = tab.sessionState?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
        when {
            saved != null -> session.restoreState(saved)
            tab.url.isNotBlank() -> session.loadUri(tab.url)
        }
        return session
    }

    /** 只保留最近使用的若干个普通标签会话，其余释放以节省内存（无痕标签不释放，否则会丢失登录状态）。 */
    private fun trimSessions() {
        val live = _tabs.value.filter { it.session != null && !it.isPrivate && it.id != _selectedId.value }
            .sortedByDescending { it.lastActiveAt }
        live.drop(MAX_BACKGROUND_SESSIONS).forEach { t ->
            t.session?.close()
            t.session = null
        }
    }

    private fun buildSession(tab: Tab): GeckoSession {
        val desktop = tab.state.value.desktopMode
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate)
            .useTrackingProtection(true)
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(false)
            .build()
        return GeckoSession(settings).also { attachDelegates(tab, it) }
    }

    private fun attachDelegates(tab: Tab, session: GeckoSession) {
        session.promptDelegate = prompts
        session.permissionDelegate = permissions

        session.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                val shown = if (url != null && url.startsWith(ERROR_PAGE_PREFIX)) tab.url else url.orEmpty()
                val tracking = perms.firstOrNull { it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
                tab.trackingPermission = tracking
                tab.update {
                    it.copy(
                        url = shown,
                        trackingToggleAvailable = tracking != null,
                        trackingExempt = tracking?.value == ContentPermission.VALUE_ALLOW,
                    )
                }
                scheduleSave()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.update { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.update { it.copy(canGoForward = canGoForward) }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> = handleLoadRequest(tab, request)

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                // 子框架不允许直接拉起外部 App 或打开本地文件
                return when (LinkPolicy.decide(request.uri, false)) {
                    LinkDecision.LoadInBrowser -> GeckoResult.allow()
                    else -> GeckoResult.deny()
                }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // target=_blank / window.open：开新标签，GeckoView 会自行 open 返回的会话
                val child = Tab(
                    id = UUID.randomUUID().toString(),
                    isPrivate = tab.isPrivate, parentId = tab.id, openedByOtherApp = false,
                    url = uri, title = "", desktopMode = tab.state.value.desktopMode,
                    sessionState = null, lastActiveAt = System.currentTimeMillis(),
                )
                val childSession = buildSession(child)
                child.session = childSession
                _tabs.value = _tabs.value + child
                tab.session?.setActive(false)
                _selectedId.value = child.id
                onTabOpenedByPage?.invoke(child)
                scheduleSave()
                return GeckoResult.fromValue(childSession)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String> {
                if (uri != null) tab.update { it.copy(url = uri) }
                return GeckoResult.fromValue(ErrorPages.dataUri(uri, error))
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.update { it.copy(loading = true, progress = 5) }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.update { it.copy(loading = false, progress = 100) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.update { it.copy(progress = progress) }
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
            ) {
                tab.update { it.copy(secure = securityInfo.isSecure) }
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                if (!tab.isPrivate) {
                    tab.sessionState = sessionState.toString()
                    scheduleSave()
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val t = title.orEmpty()
                tab.update { it.copy(title = t) }
                val url = tab.url
                if (!tab.isPrivate && t.isNotBlank() && url.startsWith("http")) {
                    scope.launch(dbDispatcher) { db.updateTitle(url, t) }
                }
                scheduleSave()
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                uiHost?.onFullScreen(tab, fullScreen)
            }

            override fun onCloseRequest(session: GeckoSession) {
                close(tab.id)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val host = uiHost
                if (host == null) {
                    runCatching { response.body?.close() }
                } else {
                    host.confirmDownload(tab, response)
                }
            }

            override fun onCrash(session: GeckoSession) = recoverFromCrash(tab, session)

            override fun onKill(session: GeckoSession) = recoverFromCrash(tab, session)
        }

        session.historyDelegate = object : HistoryDelegate {
            override fun onVisited(
                session: GeckoSession,
                url: String,
                lastVisitedURL: String?,
                flags: Int,
            ): GeckoResult<Boolean> {
                val skip = tab.isPrivate ||
                    flags and HistoryDelegate.VISIT_TOP_LEVEL == 0 ||
                    flags and (HistoryDelegate.VISIT_REDIRECT_SOURCE or HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT or
                        HistoryDelegate.VISIT_UNRECOVERABLE_ERROR) != 0 ||
                    !(url.startsWith("http://") || url.startsWith("https://"))
                if (skip) return GeckoResult.fromValue(false)
                // 标题此时可能还是上一页的，由 onTitleChange 随后写入
                scope.launch(dbDispatcher) { db.recordVisit(url, null) }
                return GeckoResult.fromValue(true)
            }

            override fun getVisited(session: GeckoSession, urls: Array<out String>): GeckoResult<BooleanArray> {
                if (tab.isPrivate) return GeckoResult.fromValue(BooleanArray(urls.size))
                val result = GeckoResult<BooleanArray>()
                val copy = arrayOf(*urls)
                scope.launch(Dispatchers.IO) { result.complete(runCatching { db.isVisited(copy) }.getOrElse { BooleanArray(copy.size) }) }
                return result
            }
        }
    }

    private fun handleLoadRequest(tab: Tab, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
        when (val d = LinkPolicy.decide(request.uri, request.isDirectNavigation)) {
            LinkDecision.LoadInBrowser -> GeckoResult.allow()
            LinkDecision.Block -> GeckoResult.deny()
            is LinkDecision.OpenExternally -> {
                uiHost?.confirmExternalLink(tab, d.uri)
                GeckoResult.deny()
            }
        }

    private fun recoverFromCrash(tab: Tab, session: GeckoSession) {
        Log.w(TAG, "内容进程崩溃/被回收：${tab.url}")
        if (tab.session === session) {
            runCatching { session.close() }
            tab.session = null
            if (_selectedId.value == tab.id) {
                ensureSession(tab).setActive(true)
                _sessionEpoch.value++
            }
        }
    }

    companion object {
        private const val TAG = "TabManager"
        private const val MAX_BACKGROUND_SESSIONS = 4
        const val ERROR_PAGE_PREFIX = "data:text/html;charset=utf-8;base64,"
    }
}
