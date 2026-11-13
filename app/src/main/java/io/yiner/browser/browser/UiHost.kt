package io.yiner.browser.browser

import android.app.Activity
import android.net.Uri
import org.mozilla.geckoview.WebResponse

/**
 * 需要 Activity 才能完成的操作（对话框、系统权限、文件选择、外部 App）。
 * GeckoSession 的生命周期比 Activity 长，所以委托都通过这个接口找“当前”的界面；
 * 没有界面时一律安全地拒绝/取消。
 */
interface UiHost {
    val activity: Activity

    fun requestAndroidPermissions(permissions: Array<String>, onResult: (allGranted: Boolean) -> Unit)

    /** 打开系统文件选择器；用户取消时回调空列表。 */
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, onResult: (List<Uri>) -> Unit)

    /** 调用系统相机拍一张照片；失败或取消时回调 null。 */
    fun capturePhoto(onResult: (Uri?) -> Unit)

    fun confirmExternalLink(tab: Tab, uri: String)

    fun confirmDownload(tab: Tab, response: WebResponse)

    fun onFullScreen(tab: Tab, fullScreen: Boolean)
}
