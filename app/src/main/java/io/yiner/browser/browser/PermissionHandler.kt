package io.yiner.browser.browser

import android.app.AlertDialog
import io.yiner.browser.core.UrlInput
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource

/**
 * 网站权限：每次都由用户明确选择，不默认放行。
 * 系统（Android）权限只在网站实际请求、且用户在本应用对话框中同意后才向系统申请。
 */
class PermissionHandler(private val hostProvider: () -> UiHost?) : PermissionDelegate {

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: PermissionDelegate.Callback,
    ) {
        val host = hostProvider()
        if (host == null || permissions.isNullOrEmpty()) {
            callback.reject(); return
        }
        host.requestAndroidPermissions(arrayOf(*permissions)) { granted ->
            if (granted) callback.grant() else callback.reject()
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: ContentPermission,
    ): GeckoResult<Int> {
        val site = UrlInput.displayHost(perm.uri).ifBlank { "此网站" }
        val question = when (perm.permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> "$site 想获取你的位置"
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "$site 想播放受 DRM 保护的内容"
            PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                "${UrlInput.displayHost(perm.thirdPartyOrigin.orEmpty()).ifBlank { "第三方网站" }} 想在 $site 上使用 Cookie 和网站数据（常见于第三方登录）"
            PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS, PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS ->
                "$site 想访问你局域网中的设备"
            // 自动播放：静音视频允许，有声视频阻止（与主流浏览器默认一致）
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            // 本版不实现网页推送通知、XR
            else -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
        return ask(question).map { allowed -> if (allowed == true) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY }
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out MediaSource>?,
        audio: Array<out MediaSource>?,
        callback: PermissionDelegate.MediaCallback,
    ) {
        val cam = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA } ?: video?.firstOrNull()
        val mic = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE } ?: audio?.firstOrNull()
        if (cam == null && mic == null) {
            callback.reject(); return
        }
        val what = listOfNotNull(cam?.let { "摄像头" }, mic?.let { "麦克风" }).joinToString("和")
        ask("${UrlInput.displayHost(uri).ifBlank { "此网站" }} 想使用你的$what").accept { allowed ->
            if (allowed == true) callback.grant(cam, mic) else callback.reject()
        }
    }

    private fun ask(message: String): GeckoResult<Boolean> {
        val host = hostProvider() ?: return GeckoResult.fromValue(false)
        val result = GeckoResult<Boolean>()
        AlertDialog.Builder(host.activity)
            .setTitle("权限请求")
            .setMessage(message)
            .setPositiveButton("允许") { _, _ -> result.complete(true) }
            .setNegativeButton("拒绝") { _, _ -> result.complete(false) }
            .setOnCancelListener { result.complete(false) }
            .show()
        return result
    }
}
