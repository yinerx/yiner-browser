package io.yiner.browser

import android.Manifest
import android.app.AlertDialog
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.yiner.browser.browser.Tab
import io.yiner.browser.browser.UiHost
import io.yiner.browser.core.DownloadNaming
import io.yiner.browser.core.LinkPolicy
import io.yiner.browser.core.UrlInput
import io.yiner.browser.ui.BrowserApp
import io.yiner.browser.ui.Screen
import io.yiner.browser.ui.UiState
import io.yiner.browser.ui.YinerTheme
import io.yiner.browser.update.UpdateInstaller
import io.yiner.browser.update.UpdateNotifications
import android.content.ComponentName
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import io.yiner.browser.browser.BlobDownloads
import io.yiner.browser.browser.DownloadRequest
import java.io.File

class MainActivity : ComponentActivity(), UiHost {

    val app get() = application as YinerApp
    val ui = UiState()
    /** 承载当前标签 WebView 的容器（界面中始终存在，切换标签时更换子视图）。 */
    lateinit var webContainer: FrameLayout
        private set

    /** 网页全屏（例如全屏视频）时显示 WebView 提供的视图。 */
    lateinit var fullscreenContainer: FrameLayout
        private set
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override val activity get() = this

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var filesCallback: ((List<Uri>) -> Unit)? = null
    private var photoCallback: ((Uri?) -> Unit)? = null
    private var pendingPhoto: Uri? = null
    private var externalDialogShowing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(result.isNotEmpty() && result.values.all { it })
        }
    private val openOneLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = filesCallback
            filesCallback = null
            cb?.invoke(listOfNotNull(uri))
        }
    private val openManyLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val cb = filesCallback
            filesCallback = null
            cb?.invoke(uris)
        }
    private val photoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val cb = photoCallback
            val uri = pendingPhoto
            photoCallback = null
            pendingPhoto = null
            cb?.invoke(if (ok) uri else null)
        }
    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshDefaultBrowser() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        webContainer = FrameLayout(this)
        fullscreenContainer = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        app.blocker // 尽早在后台加载过滤规则
        val tm = app.tabManager
        tm.attachActivity(this)
        tm.uiHost = this
        tm.onTabOpenedByPage = { showBrowser() }
        tm.restore()
        tm.runAutoArchive()
        if (tm.selectedTab != null) ui.screen = Screen.BROWSER

        setContent { YinerTheme { BrowserApp(this) } }

        if (savedInstanceState == null) handleIntent(intent, coldStart = true)
        app.updates.onAppStart()
        showMigrationNoticeOnce()
    }

    /**
     * 0.2.0 正式版使用 GeckoView 内核，本版改为基于 Chromium 的系统 WebView。两者的 Cookie 与网站存储格式不同，
     * 无法迁移：首次启动时告知用户需要重新登录。旧数据不会被自动删除（可在设置中手动清理）。
     */
    private fun showMigrationNoticeOnce() {
        val prefs = app.prefs
        if (prefs.webViewMigrationNoticeShown) return
        val hadOldEngineData = File(filesDir, "mozilla").exists() || app.tabManager.tabs.value.isNotEmpty()
        prefs.webViewMigrationNoticeShown = true
        if (!hadOldEngineData) return
        AlertDialog.Builder(this)
            .setTitle("浏览器内核已更换")
            .setMessage(
                "银耳浏览器已改用基于 Chromium 的系统 WebView。\n\n" +
                    "• 网站登录状态（Cookie、网站存储）无法从旧内核迁移，需要重新登录各网站。\n" +
                    "• 标签网址、历史记录、下载列表、设置和已归档标签已保留；标签的前进/后退历史无法保留。\n" +
                    "• 旧内核的数据没有被删除，可在“设置 → 存储 → 清理旧内核数据”中手动清理。",
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, coldStart = false)
    }

    override fun onResume() {
        super.onResume()
        app.tabManager.uiHost = this
        app.tabManager.onTabOpenedByPage = { showBrowser() }
        app.tabManager.runAutoArchive()
        refreshDefaultBrowser()
    }

    override fun onStop() {
        app.tabManager.saveNow()
        android.webkit.CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        if (app.tabManager.uiHost === this) {
            app.tabManager.uiHost = null
            app.tabManager.onTabOpenedByPage = null
            app.tabManager.attachActivity(null)
        }
        webContainer.removeAllViews()
        fullscreenContainer.removeAllViews()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 外部 Intent

    private fun handleIntent(intent: Intent?, coldStart: Boolean) {
        intent ?: return
        val tm = app.tabManager
        val engine = app.prefs.settings.value.searchEngine
        when (intent.action) {
            UpdateNotifications.ACTION_SHOW_UPDATE -> ui.openPage(Screen.SETTINGS)
            UpdateNotifications.ACTION_INSTALL_UPDATE -> {
                ui.openPage(Screen.SETTINGS)
                UpdateInstaller.install(this)
            }
            Intent.ACTION_VIEW -> {
                val data = intent.dataString ?: return
                if (!(data.startsWith("http://") || data.startsWith("https://"))) return
                val fromOtherApp = referrer?.host != packageName
                tm.newTab(data, isPrivate = false, openedByOtherApp = fromOtherApp)
                showBrowser()
            }
            Intent.ACTION_WEB_SEARCH -> {
                val q = intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() } ?: return
                tm.newTab(UrlInput.toLoadableUrl(q, engine), isPrivate = false)
                showBrowser()
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
                tm.newTab(UrlInput.extractUrl(text) ?: engine.searchUrl(text), isPrivate = false)
                showBrowser()
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return
                tm.newTab(UrlInput.toLoadableUrl(text, engine), isPrivate = false)
                showBrowser()
            }
            Intent.ACTION_MAIN -> if (coldStart) {
                // 从桌面冷启动：停在当前标签（没有标签时停在标签切换页）；
                // 开启“自动弹出键盘”时直接叠加搜索面板（05 设置项 Auto-Open Keyboard）
                ui.screen = if (tm.selectedTab != null) Screen.BROWSER else Screen.TABS
                if (app.prefs.settings.value.autoOpenKeyboard || tm.selectedTab == null) {
                    ui.search = io.yiner.browser.ui.SearchRequest("", inCurrentTab = false, isPrivate = false)
                }
            }
        }
    }

    fun showBrowser() {
        ui.search = null
        ui.menuOpen = false
        ui.siteSettingsOpen = false
        ui.leavePages(Screen.BROWSER)
    }

    /** 回到标签切换页：先给当前网页截一张缩略图，供卡片显示。 */
    fun showTabs() {
        captureThumbnail()
        ui.menuOpen = false
        ui.leavePages(Screen.TABS)
    }

    /** 截取当前网页的缩略图（缩小到宽 360px，只保存在内存中）。 */
    fun captureThumbnail() {
        val tab = app.tabManager.selectedTab ?: return
        val view = tab.view ?: return
        if (ui.screen != Screen.BROWSER || view.width == 0 || view.height == 0) return
        runCatching {
            val w = 360
            val scale = w / view.width.toFloat()
            val bmp = createBitmap(w, (view.height * scale).toInt().coerceAtLeast(1))
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)
            tab.thumbnail.value = bmp.asImageBitmap()
        }
    }

    fun setTextZoom(percent: Int) {
        val p = percent.coerceIn(50, 300)
        app.prefs.update { it.copy(textZoomPercent = p) }
        app.tabManager.applyTextZoom(p)
    }

    /** 无痕标签需要系统 WebView 支持多配置文件（MULTI_PROFILE）；不支持时不打开，避免与普通标签共享 Cookie。 */
    private fun privateUnavailable() =
        toast("此设备的系统 WebView 不支持隔离的无痕模式，请更新“Android System WebView”后再试")

    /** 用其它浏览器打开（例如 Google 拒绝在 WebView 中登录时）。登录状态不会带回银耳浏览器。 */
    fun openInOtherBrowser(url: String) {
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val chooser = Intent.createChooser(view, "用其它浏览器打开")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        runCatching { startActivity(chooser) }.onFailure { toast("没有其它可用的浏览器") }
    }

    // ------------------------------------------------------------------ 浏览操作（供界面调用）

    fun submitSearch(text: String, inCurrentTab: Boolean, isPrivate: Boolean) {
        val url = UrlInput.toLoadableUrl(text, app.prefs.settings.value.searchEngine)
        if (url.isEmpty()) return
        val tm = app.tabManager
        val ok = if (inCurrentTab && tm.selectedTab != null) tm.load(url, tm.selectedTab!!.isPrivate) else tm.newTab(url, isPrivate) != null
        if (!ok) return privateUnavailable()
        showBrowser()
    }

    fun openInNewTab(url: String, isPrivate: Boolean = false) {
        if (app.tabManager.newTab(url, isPrivate) == null) return privateUnavailable()
        showBrowser()
    }

    fun shareUrl(tab: Tab) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, tab.title).putExtra(Intent.EXTRA_TEXT, tab.url)
        startActivity(Intent.createChooser(send, "分享链接"))
    }

    fun copyUrl(tab: Tab) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", tab.url))
        if (Build.VERSION.SDK_INT < 33) toast("已复制链接")
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun returnToCallingApp() {
        moveTaskToBack(true)
    }

    fun setFullscreen(enabled: Boolean) {
        ui.fullscreen = enabled
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ 默认浏览器

    var isDefaultBrowser = androidx.compose.runtime.mutableStateOf(false)
        private set

    fun refreshDefaultBrowser() {
        isDefaultBrowser.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        } else {
            val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
            packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
        }
    }

    /** Android 10+ 弹出系统“设为默认浏览器”对话框；不可用时跳转到系统默认应用设置，由用户手动选择。 */
    fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)) }
                    .onSuccess { return }
            }
        }
        openDefaultAppsSettings()
    }

    fun openDefaultAppsSettings() {
        val i = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        runCatching { startActivity(i) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    // ------------------------------------------------------------------ UiHost

    override fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return onResult(true)
        if (permissionCallback != null) return onResult(false)
        permissionCallback = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, onResult: (List<Uri>) -> Unit) {
        if (filesCallback != null) return onResult(emptyList())
        filesCallback = onResult
        try {
            if (multiple) openManyLauncher.launch(mimeTypes) else openOneLauncher.launch(mimeTypes)
        } catch (e: ActivityNotFoundException) {
            filesCallback = null
            toast("没有可用的文件选择器")
            onResult(emptyList())
        }
    }

    override fun capturePhoto(onResult: (Uri?) -> Unit) {
        // 本应用声明了 CAMERA 权限，按系统规则拍照前必须已获授权
        requestAndroidPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
            if (!granted || photoCallback != null) return@requestAndroidPermissions onResult(null)
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            pendingPhoto = uri
            photoCallback = onResult
            try {
                photoLauncher.launch(uri)
            } catch (e: ActivityNotFoundException) {
                photoCallback = null; pendingPhoto = null
                toast("没有可用的相机应用")
                onResult(null)
            }
        }
    }

    override fun confirmExternalLink(tab: Tab, uri: String) {
        if (externalDialogShowing) return
        externalDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("打开其它应用？")
            .setMessage(LinkPolicy.describe(uri))
            .setPositiveButton("打开") { _, _ -> launchExternal(tab, uri) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { externalDialogShowing = false }
            .show()
    }

    private fun launchExternal(tab: Tab, uri: String) {
        val intent = if (uri.startsWith("intent:", ignoreCase = true)) {
            runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                // 只允许打开可浏览的 Activity，不能借链接启动任意组件或获得 URI 授权
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (intent == null) return toast("链接格式无效")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = LinkPolicy.intentFallbackUrl(uri)
            when {
                fallback != null -> app.tabManager.ensureView(tab).loadUrl(fallback)
                intent.`package` != null -> runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${intent.`package`}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast("没有可以打开此链接的应用") }
                else -> toast("没有可以打开此链接的应用")
            }
        } catch (e: SecurityException) {
            toast("无法打开此链接")
        }
    }

    override fun confirmDownload(tab: Tab, request: DownloadRequest) {
        val url = request.url
        var mime = request.mimeType
        if (url.startsWith("data:")) mime = BlobDownloads.dataUrlMime(url) ?: mime
        val name = DownloadNaming.choose(request.contentDisposition, url, mime) { m ->
            MimeTypeMap.getSingleton().getExtensionFromMimeType(m)
        }
        val size = if (url.startsWith("data:")) -1L else request.contentLength
        AlertDialog.Builder(this)
            .setTitle("下载文件")
            .setMessage("$name\n${DownloadNaming.formatSize(size)}" + if (tab.isPrivate) "\n\n（无痕模式：文件会保存，但不会出现在下载列表中）" else "")
            .setPositiveButton("下载") { _, _ ->
                when {
                    url.startsWith("data:") -> {
                        val bytes = BlobDownloads.decodeDataUrl(url) ?: return@setPositiveButton toast("无法读取文件内容")
                        startDownload(request.copy(inlineBytes = bytes), name, mime, bytes.size.toLong())
                    }
                    url.startsWith("blob:") -> {
                        val view = tab.view ?: return@setPositiveButton toast("页面已关闭，无法下载")
                        BlobDownloads.fetch(view, url) { bytes, blobMime, error ->
                            if (bytes == null) {
                                toast("下载失败：${error ?: "未知错误"}")
                            } else {
                                startDownload(request.copy(inlineBytes = bytes), name, blobMime ?: mime, bytes.size.toLong())
                            }
                        }
                    }
                    url.startsWith("https://") || url.startsWith("http://") -> startDownload(request, name, mime, request.contentLength)
                    else -> toast("不支持此类下载地址")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(request: DownloadRequest, name: String, mime: String?, total: Long) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
        requestAndroidPermissions(needed) { granted ->
            if (!granted) {
                toast("没有存储权限，无法保存文件")
                return@requestAndroidPermissions
            }
            app.downloads.enqueue(request, name, mime, total)
            toast("开始下载：$name")
            // 通知权限只影响进度提示，不影响下载本身
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestAndroidPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
            }
        }
    }

    override fun showCustomView(tab: Tab, view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customViewCallback != null) {
            callback.onCustomViewHidden(); return
        }
        customViewCallback = callback
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setFullscreen(true)
    }

    override fun hideCustomView() {
        fullscreenContainer.removeAllViews()
        val cb = customViewCallback
        customViewCallback = null
        setFullscreen(false)
        runCatching { cb?.onCustomViewHidden() }
    }

    /** 由返回手势退出网页全屏。 */
    fun exitCustomView() = hideCustomView()

    override fun confirmGoogleSignIn(tab: Tab, url: String) {
        AlertDialog.Builder(this)
            .setTitle("Google 登录可能受限")
            .setMessage(
                "银耳浏览器使用基于 Chromium 的系统 WebView。Google 的 OAuth 政策不允许在应用内嵌的浏览组件中发起登录，" +
                    "因此 Google 可能拒绝在这里登录（例如显示 disallowed_useragent）。\n\n" +
                    "你可以继续尝试；如被拒绝，可改用其它浏览器打开此页面——但在其它浏览器中登录后，登录状态不会带回银耳浏览器。",
            )
            .setPositiveButton("继续尝试") { _, _ ->
                app.prefs.googleSignInNoticeShown = true
                app.tabManager.ensureView(tab).loadUrl(url)
            }
            .setNeutralButton("用其它浏览器打开") { _, _ -> openInOtherBrowser(url) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun openDownload(uri: String, mime: String?) {
        try {
            startActivity(io.yiner.browser.download.Downloads.openIntent(uri.toUri(), mime))
        } catch (e: ActivityNotFoundException) {
            toast("没有可以打开此文件的应用")
        } catch (e: SecurityException) {
            toast("文件已被移动或删除")
        }
    }
}
