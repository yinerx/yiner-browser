package io.yiner.browser.browser

import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import android.webkit.WebView

/**
 * 标签前进/后退历史的保存格式："wv1:" + Base64(Parcel(WebView.saveState))。
 *
 * - 只在同一台设备、同一个 WebView 版本族内恢复；任何失败都回退为按 URL 重新加载。
 * - 旧版（GeckoView）保存的 JSON 状态不以 "wv1:" 开头，会被识别为不兼容并忽略，只按 URL 打开。
 */
object SessionCodec {
    private const val PREFIX = "wv1:"

    /** 纯字符串判断，便于单元测试。 */
    fun isWebViewState(s: String?): Boolean = s != null && s.startsWith(PREFIX) && s.length > PREFIX.length

    fun save(view: WebView): String? = runCatching {
        val bundle = Bundle()
        view.saveState(bundle) ?: return null
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            PREFIX + Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    /** 恢复成功返回 true；返回 false 时调用方应按 URL 加载。 */
    fun restore(view: WebView, state: String?): Boolean {
        if (!isWebViewState(state)) return false
        return runCatching {
            val bytes = Base64.decode(state!!.removePrefix(PREFIX), Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val bundle = parcel.readBundle(SessionCodec::class.java.classLoader) ?: return false
                view.restoreState(bundle) != null
            } finally {
                parcel.recycle()
            }
        }.getOrDefault(false)
    }
}
