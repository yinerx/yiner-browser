package io.yiner.browser.browser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 无痕标签的隔离：每个无痕会话使用一个独立的 WebView Profile（androidx.webkit MULTI_PROFILE）。
 * Profile 拥有独立的 Cookie、网站存储、HTTP 缓存与权限，与普通标签互不可见。
 *
 * 限制（官方文档）：同一进程中加载过的 Profile 不能删除。因此：
 * - 关闭最后一个无痕标签时，立即清空该 Profile 的 Cookie 与网站存储，下一次无痕会话换用新的 Profile 名；
 * - 磁盘上的残留目录在下次启动、尚未加载时由 [cleanupStale] 整体删除。
 *
 * 系统 WebView 不支持 MULTI_PROFILE 时，[supported] 为 false，应用拒绝打开无痕标签，
 * 而不是让无痕标签与普通标签共享 Cookie。
 */
// 所有用到 Profile 接口的方法都在开头直接检查 MULTI_PROFILE；嵌套 lambda 中的调用受同一检查保护
@android.annotation.SuppressLint("RequiresFeature")
class PrivateProfiles {
    val supported: Boolean get() = isSupported()

    private fun isSupported() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    private var current: String? = null

    /** 启动时删除上次遗留的无痕 Profile（必须在加载任何无痕 Profile 之前调用）。 */
    fun cleanupStale() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching {
            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { isPrivateName(it) }.forEach { name ->
                runCatching { store.deleteProfile(name) }.onFailure { Log.w(TAG, "删除遗留无痕配置失败：$name", it) }
            }
        }
    }

    /** 让 WebView 使用当前无痕会话的 Profile。必须在该 WebView 加载任何内容之前调用。 */
    fun apply(view: WebView, serviceWorkerInterceptor: (android.webkit.WebResourceRequest) -> android.webkit.WebResourceResponse?) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { "系统 WebView 不支持隔离的无痕模式" }
        val isNew = current == null
        val name = current ?: newName().also { current = it }
        WebViewCompat.setProfile(view, name)
        if (isNew) {
            runCatching {
                ProfileStore.getInstance().getProfile(name)?.serviceWorkerController?.setServiceWorkerClient(
                    object : android.webkit.ServiceWorkerClient() {
                        override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest) = serviceWorkerInterceptor(request)
                    },
                )
            }
        }
    }

    /** 最后一个无痕标签关闭（WebView 已销毁）后调用：清空数据并结束本次会话。 */
    fun endSession() {
        val name = current ?: return
        current = null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching {
            val profile = ProfileStore.getInstance().getProfile(name) ?: return
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
            profile.geolocationPermissions.clearAll()
        }.onFailure { Log.w(TAG, "清空无痕配置失败", it) }
    }

    fun cookieManagerFor(view: WebView): android.webkit.CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) runCatching { WebViewCompat.getProfile(view).cookieManager }.getOrNull() ?: android.webkit.CookieManager.getInstance()
        else android.webkit.CookieManager.getInstance()

    companion object {
        private const val TAG = "PrivateProfiles"
        const val PREFIX = "yiner-private-"
        fun isPrivateName(name: String) = name.startsWith(PREFIX)
        fun newName(now: Long = System.currentTimeMillis()) = "$PREFIX$now"
    }
}
