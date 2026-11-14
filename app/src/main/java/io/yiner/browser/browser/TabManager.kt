package io.yiner.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.AtomicFile
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.SafeBrowsingResponseCompat
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.yiner.browser.blocking.ContentBlocker
import io.yiner.browser.blocking.Cosmetics
import io.yiner.browser.core.ArchiveCandidate
import io.yiner.browser.core.ArchivePolicy
import io.yiner.browser.core.LinkDecision
import io.yiner.browser.core.LinkPolicy
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
import java.io.File
import java.util.UUID

/**
 * 管理全部标签及其 WebView（Android 系统 WebView，基于 Chromium）。所有方法都在主线程调用。
 *
 * - 每个标签一个 WebView；后台只保留最近使用的若干个普通标签的 WebView，其余保存历史后销毁；
 * - 无痕标签使用独立 WebView Profile（见 [PrivateProfiles]），系统不支持时拒绝打开无痕标签；
 * - 网络请求在 shouldInterceptRequest 中按规则拦截（见 [ContentBlocker]）。
 */
class TabManager(
    private val context: Context,
    private val db: BrowserDb,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val blocker: ContentBlocker,
    val privateProfiles: PrivateProfiles,
    private val dialogs: WebDialogs,
) {
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** 选中标签的 WebView 被替换（例如渲染进程崩溃后重建）时递增，界面据此重新挂载。 */
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

    /** 所有 WebView 共用的上下文包装：有 Activity 时指向 Activity（下拉框等弹窗需要），否则指向 Application。 */
    private val contexts = mutableListOf<MutableContextWrapper>()
    private var activityContext: Context? = null

    val selectedTab: Tab? get() = _tabs.value.firstOrNull { it.id == _selectedId.value }

    fun find(id: String?): Tab? = _tabs.value.firstOrNull { it.id == id }

    val privateSupported: Boolean get() = privateProfiles.supported

    fun attachActivity(activity: Context?) {
        activityContext = activity
        contexts.forEach { it.baseContext = themed(activity ?: context) }
    }

    /** 当前界面是否为深色（由“设置 → 外观”与系统外观共同决定）。 */
    var darkTheme: Boolean = false
        private set

    /**
     * WebView 的上下文主题：系统 WebView 按主题的 isLightTheme 设置网页 prefers-color-scheme。
     * 不开启算法暗化（setAlgorithmicDarkeningAllowed 保持默认关闭），不对网页反色。
     */
    private fun themed(base: Context): Context =
        android.view.ContextThemeWrapper(base, if (darkTheme) io.yiner.browser.R.style.Theme_Yiner_Web_Dark else io.yiner.browser.R.style.Theme_Yiner_Web_Light)

    /**
     * 外观改变时调用。已创建的 WebView 不保证会重新读取主题，因此保存各标签的前进/后退历史后重建 WebView，
     * 当前标签立即按新外观重新加载，其余标签在切换到时再恢复。[rebuild] 为 false 用于启动时设置初值。
     */
    fun setDarkTheme(dark: Boolean, rebuild: Boolean = true) {
        if (dark == darkTheme) return
        darkTheme = dark
        if (!rebuild) return
        _tabs.value.filter { it.view != null }.forEach { t ->
            t.view?.let { v -> SessionCodec.save(v)?.let { t.sessionState = it } }
            destroyView(t)
        }
        selectedTab?.let {
            ensureView(it)
            _sessionEpoch.value++
        }
    }

    // ------------------------------------------------------------------ 恢复与保存

    /** 从磁盘恢复上次的普通标签（只建模型，不创建 WebView，选中时再懒加载）。 */
    fun restore() {
        if (restored) return
        restored = true
        val json = runCatching { String(stateFile.readFully(), Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            val arr = JSONObject(json).getJSONArray("tabs")
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
                    // 旧版 GeckoView 保存的历史格式不兼容：SessionCodec 会识别并改为按 URL 打开
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
            delay(800)
            saveNow()
        }
    }

    /** 立即保存（Activity 进入后台时调用）。无痕标签永不落盘。 */
    fun saveNow() {
        _tabs.value.filter { !it.isPrivate }.forEach { t -> t.view?.let { v -> SessionCodec.save(v)?.let { t.sessionState = it } } }
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

    /** 新建标签。请求无痕但系统 WebView 不支持隔离时返回 null（调用方需提示用户）。 */
    fun newTab(
        url: String?,
        isPrivate: Boolean,
        parentId: String? = null,
        openedByOtherApp: Boolean = false,
        select: Boolean = true,
    ): Tab? {
        if (isPrivate && !privateSupported) return null
        val tab = Tab(
            id = UUID.randomUUID().toString(),
            isPrivate = isPrivate, parentId = parentId, openedByOtherApp = openedByOtherApp,
            url = url.orEmpty(), title = "", desktopMode = false, sessionState = null,
            lastActiveAt = System.currentTimeMillis(),
        )
        _tabs.value = _tabs.value + tab
        if (!url.isNullOrBlank()) ensureView(tab)
        if (select) select(tab.id)
        scheduleSave()
        return tab
    }

    fun select(id: String?) {
        val prev = selectedTab
        val tab = find(id)
        if (prev != null && prev !== tab) prev.view?.onPause()
        _selectedId.value = tab?.id
        if (tab != null) {
            tab.lastActiveAt = System.currentTimeMillis()
            ensureView(tab).onResume()
            trimViews()
        }
        scheduleSave()
    }

    /** 在当前标签加载；没有标签（或无痕属性不同）时新建。 */
    fun load(url: String, isPrivate: Boolean = false): Boolean {
        val tab = selectedTab
        return if (tab == null || tab.isPrivate != isPrivate) {
            newTab(url, isPrivate) != null
        } else {
            tab.update { it.copy(url = url) }
            ensureView(tab).loadUrl(url)
            tab.lastActiveAt = System.currentTimeMillis()
            true
        }
    }

    fun close(id: String) {
        val tab = find(id) ?: return
        val remaining = _tabs.value.filter { it.id != id }
        remaining.forEach { if (it.parentId == id) it.parentId = tab.parentId }
        _tabs.value = remaining
        destroyView(tab)
        if (_selectedId.value == id) {
            val parent = remaining.firstOrNull { it.id == tab.parentId && it.isPrivate == tab.isPrivate }
            _selectedId.value = null
            if (parent != null) select(parent.id)
        }
        // 最后一个无痕标签关闭：清空无痕 Profile 的 Cookie 与网站存储，下次无痕会话换新 Profile
        if (tab.isPrivate && remaining.none { it.isPrivate }) privateProfiles.endSession()
        scheduleSave()
    }

    fun closeAll(isPrivate: Boolean) {
        _tabs.value.filter { it.isPrivate == isPrivate }.forEach { close(it.id) }
    }

    fun setDesktopMode(tab: Tab, enabled: Boolean) {
        tab.update { it.copy(desktopMode = enabled) }
        tab.view?.let { applyUserAgent(it, enabled); it.reload() }
        scheduleSave()
    }

    /** 对当前网站开关拦截（按近似可注册域名记录），并刷新页面。 */
    fun setSiteBlocking(tab: Tab, enabled: Boolean) {
        val host = tab.pageHost ?: return
        prefs.setSiteBlocking(io.yiner.browser.blocking.FilterEngine.baseDomain(host), enabled)
        tab.update { it.copy(siteBlockingEnabled = enabled) }
        tab.view?.reload()
    }

    fun applyTextZoom(percent: Int) {
        _tabs.value.forEach { it.view?.settings?.textZoom = percent }
    }

    fun applyThirdPartyCookies(allow: Boolean) {
        _tabs.value.forEach { t -> t.view?.let { privateProfiles.cookieManagerFor(it).setAcceptThirdPartyCookies(it, allow) } }
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
        toArchive.forEach { t -> t.view?.let { v -> SessionCodec.save(v)?.let { t.sessionState = it } } }
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

    // ------------------------------------------------------------------ WebView

    /** 保证标签有 WebView；没有时新建并从保存的历史或 URL 恢复。 */
    fun ensureView(tab: Tab): WebView {
        tab.view?.let { return it }
        val view = buildView(tab)
        tab.view = view
        if (!SessionCodec.restore(view, tab.sessionState) && tab.url.isNotBlank()) view.loadUrl(tab.url)
        return view
    }

    private fun destroyView(tab: Tab) {
        val v = tab.view ?: return
        tab.view = null
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        contexts.remove(v.context as? MutableContextWrapper)
        v.stopLoading()
        v.destroy()
    }

    /** 只保留最近使用的若干个普通标签的 WebView（无痕标签不释放，否则会丢失其页面状态）。 */
    private fun trimViews() {
        _tabs.value.filter { it.view != null && !it.isPrivate && it.id != _selectedId.value }
            .sortedByDescending { it.lastActiveAt }
            .drop(MAX_BACKGROUND_VIEWS)
            .forEach { t ->
                t.view?.let { v -> SessionCodec.save(v)?.let { t.sessionState = it } }
                destroyView(t)
            }
    }

    private var serviceWorkerHooked = false

    /**
     * Service Worker 发出的请求不经过 WebViewClient，需单独拦截。此时拿不到所属网页，
     * 因此按“第三方”处理、且不适用单站点放行名单。
     */
    private fun hookServiceWorkers() {
        if (serviceWorkerHooked) return
        serviceWorkerHooked = true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            return
        }
        androidx.webkit.ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = interceptServiceWorker(request)
        })
    }

    private fun interceptServiceWorker(request: WebResourceRequest): WebResourceResponse? {
        val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        return if (blocker.shouldBlock(request.url.toString(), null, accept)) blocker.emptyResponse() else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildView(tab: Tab): WebView {
        hookServiceWorkers()
        val ctx = MutableContextWrapper(themed(activityContext ?: context)).also { contexts.add(it) }
        val view = WebView(ctx)
        // 无痕标签：必须在任何加载之前切换到独立 Profile（其 Service Worker 也单独拦截）
        if (tab.isPrivate) privateProfiles.apply(view) { interceptServiceWorker(it) }
        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.textZoom = prefs.settings.value.textZoomPercent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        }
        if (tab.isPrivate) s.cacheMode = WebSettings.LOAD_DEFAULT
        applyUserAgent(view, tab.state.value.desktopMode)

        val cookies = privateProfiles.cookieManagerFor(view)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, prefs.settings.value.thirdPartyCookies)

        view.webViewClient = Client(tab)
        view.webChromeClient = Chrome(tab)
        view.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val host = uiHost ?: return@setDownloadListener
            host.confirmDownload(
                tab,
                DownloadRequest(
                    url = url, userAgent = userAgent, contentDisposition = contentDisposition, mimeType = mimetype,
                    contentLength = contentLength, referer = tab.url.takeIf { it.startsWith("http") },
                    cookies = runCatching { cookies.getCookie(url) }.getOrNull(), isPrivate = tab.isPrivate,
                ),
            )
        }
        Cosmetics.install(view, blocker)
        BlobDownloads.install(view)
        return view
    }

    /** 桌面版网站：只把设备描述改为桌面平台并去掉 “Mobile”，保留 WebView 标识（“; wv”），不冒充其它浏览器。 */
    private fun applyUserAgent(view: WebView, desktop: Boolean) {
        val base = WebSettings.getDefaultUserAgent(view.context)
        view.settings.userAgentString = if (desktop) {
            base.replace(Regex("""\(Linux; Android [^)]*?(; wv)?\)""")) { m -> "(X11; Linux x86_64" + (m.groupValues[1]) + ")" }
                .replace(" Mobile", "")
        } else {
            null
        }
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = desktop
    }

    private fun refreshNav(tab: Tab, view: WebView) {
        tab.update { it.copy(canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
    }

    private inner class Client(private val tab: Tab) : WebViewClientCompat() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return when (val d = LinkPolicy.decide(url, false)) {
                LinkDecision.Block -> true
                is LinkDecision.OpenExternally -> {
                    uiHost?.confirmExternalLink(tab, d.uri); true
                }
                LinkDecision.LoadInBrowser -> {
                    val s = prefs.settings.value
                    when {
                        // 仅 HTTPS 模式：把主文档的 http 导航升级为 https
                        s.httpsOnly && url.startsWith("http://") -> {
                            view.loadUrl("https://" + url.removePrefix("http://")); true
                        }
                        GoogleSignIn.isSignInUrl(url) && !prefs.googleSignInNoticeShown && uiHost != null &&
                            !request.isRedirect -> {
                            uiHost?.confirmGoogleSignIn(tab, url); true
                        }
                        else -> false
                    }
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val host = url.toUri().host
            tab.pageHost = host
            tab.update {
                it.copy(
                    url = url, loading = true, progress = 5, blockedCount = 0, secure = url.startsWith("https://"),
                    siteBlockingEnabled = blocker.isSiteEnabled(host),
                )
            }
            refreshNav(tab, view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.update { it.copy(loading = false, progress = 100) }
            refreshNav(tab, view)
            Cosmetics.fallbackInject(view, blocker, tab.pageHost)
            scheduleSave()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.update { it.copy(url = url) }
            refreshNav(tab, view)
            if (!tab.isPrivate && !isReload && (url.startsWith("http://") || url.startsWith("https://"))) {
                scope.launch(dbDispatcher) { db.recordVisit(url, null) }
            }
            scheduleSave()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) return null
            val url = request.url.toString()
            val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
            if (!blocker.shouldBlock(url, tab.pageHost, accept)) return null
            scope.launch { tab.update { it.copy(blockedCount = it.blockedCount + 1) } }
            return blocker.emptyResponse()
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // 不放行任何证书错误
            handler.cancel()
            tab.update { it.copy(secure = false, loading = false) }
            if (error.url == view.url || error.url == tab.url) {
                view.loadDataWithBaseURL(null, ErrorPages.certificateError(error.url), "text/html", "utf-8", error.url)
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            dialogs.httpAuth(host, realm, handler)
        }

        override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponseCompat) {
            // 安全浏览命中：返回安全页面并上报（系统 WebView 的标准做法）
            when {
                WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_BACK_TO_SAFETY) -> callback.backToSafety(true)
                WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_SHOW_INTERSTITIAL) -> callback.showInterstitial(true)
                else -> view.stopLoading()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "渲染进程退出（crash=${detail.didCrash()}）：${tab.url}")
            if (tab.view === view) {
                destroyView(tab)
                if (_selectedId.value == tab.id) {
                    ensureView(tab)
                    _sessionEpoch.value++
                }
            }
            return true
        }
    }

    private inner class Chrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.update { it.copy(progress = newProgress, loading = newProgress < 100) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val t = title.orEmpty()
            tab.update { it.copy(title = t) }
            val url = tab.url
            if (!tab.isPrivate && t.isNotBlank() && url.startsWith("http")) {
                scope.launch(dbDispatcher) { db.updateTitle(url, t) }
            }
            scheduleSave()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // 没有用户手势的弹窗一律拦截
            if (!isUserGesture) return false
            val child = Tab(
                id = UUID.randomUUID().toString(),
                isPrivate = tab.isPrivate, parentId = tab.id, openedByOtherApp = false,
                url = "", title = "", desktopMode = tab.state.value.desktopMode,
                sessionState = null, lastActiveAt = System.currentTimeMillis(),
            )
            val childView = buildView(child)
            child.view = childView
            _tabs.value = _tabs.value + child
            (resultMsg.obj as WebView.WebViewTransport).webView = childView
            resultMsg.sendToTarget()
            tab.view?.onPause()
            _selectedId.value = child.id
            onTabOpenedByPage?.invoke(child)
            scheduleSave()
            return true
        }

        override fun onCloseWindow(window: WebView) = close(tab.id)

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams) =
            dialogs.chooseFiles(filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest) = dialogs.permission(request)

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            dialogs.geolocation(origin, callback)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            uiHost?.showCustomView(tab, view, callback) ?: callback.onCustomViewHidden()
        }

        override fun onHideCustomView() {
            uiHost?.hideCustomView()
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult) = dialogs.alert(url, message, result)
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult) = dialogs.confirm(url, message, result)
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult) =
            dialogs.prompt(url, message, defaultValue, result)
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult) = dialogs.beforeUnload(result)
    }

    companion object {
        private const val TAG = "TabManager"
        private const val MAX_BACKGROUND_VIEWS = 4

        /** 默认 Profile 的 Cookie 管理器（普通标签使用）。 */
        fun defaultCookies(): CookieManager = CookieManager.getInstance()

        fun webViewPackageDescription(context: Context): String =
            WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" } ?: "未知"
    }
}
