package io.yiner.browser.browser

import android.app.Activity
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient

/** 一次下载请求（来自 WebView 的 DownloadListener）。 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val referer: String?,
    /** 发起下载的标签所在 Profile 的 Cookie（无痕标签来自无痕 Profile），用于下载需要登录的文件。 */
    val cookies: String?,
    val isPrivate: Boolean,
    /** blob:/data: 下载时，由页面读出的文件内容。 */
    val inlineBytes: ByteArray? = null,
)

/**
 * 需要 Activity 才能完成的操作（对话框、系统权限、文件选择、外部 App、全屏视图）。
 * WebView 的生命周期比 Activity 长，回调通过这个接口找“当前”的界面；没有界面时一律安全地拒绝/取消。
 */
interface UiHost {
    val activity: Activity

    /** 原生对话框使用的主题（随浅色 / 深色外观）。 */
    val dialogTheme: Int

    fun requestAndroidPermissions(permissions: Array<String>, onResult: (allGranted: Boolean) -> Unit)

    /** 打开系统文件选择器；用户取消时回调空列表。 */
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, onResult: (List<Uri>) -> Unit)

    /** 调用系统相机拍一张照片；失败或取消时回调 null。 */
    fun capturePhoto(onResult: (Uri?) -> Unit)

    fun confirmExternalLink(tab: Tab, uri: String)

    fun confirmDownload(tab: Tab, request: DownloadRequest)

    /** 网页进入全屏（例如全屏视频）：显示 WebView 提供的视图。 */
    fun showCustomView(tab: Tab, view: View, callback: WebChromeClient.CustomViewCallback)

    fun hideCustomView()

    /** 即将打开 Google 登录页：提示 WebView 中的登录限制，由用户选择继续或改用其它浏览器。 */
    fun confirmGoogleSignIn(tab: Tab, url: String)
}
