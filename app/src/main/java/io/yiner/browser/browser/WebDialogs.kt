package io.yiner.browser.browser

import android.Manifest
import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.LinearLayout
import io.yiner.browser.core.UrlInput

/**
 * 网页触发的对话框：JS alert/confirm/prompt、离开页面确认、HTTP 认证、网站权限、文件上传。
 * 没有可用界面时一律取消/拒绝，网页不会卡住。
 * （`<select>`、日期时间选择器由系统 WebView 自己处理。）
 */
class WebDialogs(private val hostProvider: () -> UiHost?) {

    private fun builder(title: String?): AlertDialog.Builder? {
        val host = hostProvider() ?: return null
        return AlertDialog.Builder(host.activity).apply { if (!title.isNullOrBlank()) setTitle(title) }
    }

    private fun site(url: String?) = UrlInput.displayHost(url.orEmpty()).ifBlank { "此网页" }

    fun alert(url: String?, message: String?, result: JsResult): Boolean {
        val b = builder(site(url)) ?: run { result.cancel(); return true }
        b.setMessage(message).setPositiveButton("确定") { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }.show()
        return true
    }

    fun confirm(url: String?, message: String?, result: JsResult): Boolean {
        val b = builder(site(url)) ?: run { result.cancel(); return true }
        b.setMessage(message)
            .setPositiveButton("确定") { _, _ -> result.confirm() }
            .setNegativeButton("取消") { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }.show()
        return true
    }

    fun prompt(url: String?, message: String?, default: String?, result: JsPromptResult): Boolean {
        val host = hostProvider() ?: run { result.cancel(); return true }
        val input = EditText(host.activity).apply { setText(default.orEmpty()); setSingleLine() }
        AlertDialog.Builder(host.activity).setTitle(site(url)).setMessage(message).setView(padded(input))
            .setPositiveButton("确定") { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton("取消") { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }.show()
        return true
    }

    fun beforeUnload(result: JsResult): Boolean {
        val b = builder("离开此页面？") ?: run { result.confirm(); return true }
        b.setMessage("你所做的更改可能不会保存。")
            .setPositiveButton("离开") { _, _ -> result.confirm() }
            .setNegativeButton("留下") { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }.show()
        return true
    }

    fun httpAuth(host: String?, realm: String?, handler: HttpAuthHandler) {
        val h = hostProvider() ?: run { handler.cancel(); return }
        val user = EditText(h.activity).apply { hint = "用户名"; setSingleLine() }
        val pass = EditText(h.activity).apply {
            hint = "密码"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; addView(user); addView(pass) }
        AlertDialog.Builder(h.activity).setTitle("需要登录").setMessage("${host.orEmpty()} ${realm.orEmpty()}").setView(padded(box))
            .setPositiveButton("登录") { _, _ -> handler.proceed(user.text.toString(), pass.text.toString()) }
            .setNegativeButton("取消") { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }.show()
    }

    /** 摄像头 / 麦克风 / DRM：每次询问，同意后再申请对应的系统权限。 */
    fun permission(request: PermissionRequest) {
        val host = hostProvider() ?: run { request.deny(); return }
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (wanted.isEmpty()) {
            request.deny(); return
        }
        val what = wanted.joinToString("、") {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "摄像头"
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "麦克风"
                else -> "受 DRM 保护的内容播放"
            }
        }
        val android = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(Manifest.permission.CAMERA)
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add(Manifest.permission.RECORD_AUDIO)
        }
        AlertDialog.Builder(host.activity).setTitle("权限请求")
            .setMessage("${site(request.origin.toString())} 想使用：$what")
            .setPositiveButton("允许") { _, _ ->
                if (android.isEmpty()) {
                    request.grant(wanted.toTypedArray())
                } else {
                    host.requestAndroidPermissions(android.toTypedArray()) { ok ->
                        if (ok) request.grant(wanted.toTypedArray()) else request.deny()
                    }
                }
            }
            .setNegativeButton("拒绝") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }.show()
    }

    /** 定位：每次询问，不记住选择（retain = false）。 */
    fun geolocation(origin: String, callback: GeolocationPermissions.Callback) {
        val host = hostProvider() ?: run { callback.invoke(origin, false, false); return }
        AlertDialog.Builder(host.activity).setTitle("权限请求").setMessage("${site(origin)} 想获取你的位置")
            .setPositiveButton("允许") { _, _ ->
                host.requestAndroidPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { ok ->
                    callback.invoke(origin, ok, false)
                }
            }
            .setNegativeButton("拒绝") { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }.show()
    }

    /** 文件上传：按 accept 过滤；图片可选拍照；capture 属性存在时直接打开相机。 */
    fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val host = hostProvider() ?: run { callback.onReceiveValue(null); return true }
        val mimes = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.contains('/') }
            .toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val wantsImage = mimes.any { it.startsWith("image/") }
        var done = false
        fun finish(uris: List<Uri>) {
            if (done) return
            done = true
            callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }
        fun files() = host.pickFiles(mimes, multiple) { finish(it) }
        fun camera() = host.capturePhoto { uri -> finish(listOfNotNull(uri)) }
        when {
            wantsImage && params.isCaptureEnabled -> camera()
            wantsImage -> AlertDialog.Builder(host.activity).setTitle("上传图片")
                .setItems(arrayOf("拍照", "从文件中选择")) { _, which -> if (which == 0) camera() else files() }
                .setOnCancelListener { finish(emptyList()) }.show()
            else -> files()
        }
        return true
    }

    private fun padded(view: android.view.View): android.view.View {
        val host = hostProvider()!!
        val pad = (20 * host.activity.resources.displayMetrics.density).toInt()
        return LinearLayout(host.activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
