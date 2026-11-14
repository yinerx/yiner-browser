package io.yiner.browser.blocking

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * 元素隐藏（EasyList `##` 规则）注入。
 * 支持 DOCUMENT_START_SCRIPT 与 WEB_MESSAGE_LISTENER 的系统 WebView：页面开始解析时由注入脚本向应用请求本站 CSS，
 * 应用回传后插入 <style>，广告位在显示前即被隐藏；不支持时退化为页面加载完成后再注入（广告可能闪现）。
 * 消息通道只返回 CSS 文本，网页借此无法读取或改变应用的任何状态。
 */
object Cosmetics {
    private const val OBJECT = "yinerCosmetic"
    private const val SCRIPT = """(function(){try{var c=window.$OBJECT;if(!c)return;c.onmessage=function(e){var s=document.createElement('style');s.textContent=e.data;(document.head||document.documentElement).appendChild(s)};c.postMessage('css')}catch(e){}})();"""

    fun earlySupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }.getOrDefault(false)

    // 回调内的 postMessage 与外层调用受同一个 WEB_MESSAGE_LISTENER 检查保护
    @android.annotation.SuppressLint("RequiresFeature")
    fun install(view: WebView, blocker: ContentBlocker) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            return
        }
        WebViewCompat.addWebMessageListener(view, OBJECT, setOf("*")) { _, _, sourceOrigin, _, reply ->
            val css = blocker.cssFor(sourceOrigin.host)
            if (css.isNotEmpty()) reply.postMessage(css)
        }
        WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
    }

    fun fallbackInject(view: WebView, blocker: ContentBlocker, host: String?) {
        if (earlySupported()) return
        val css = blocker.cssFor(host)
        if (css.isEmpty()) return
        view.evaluateJavascript(
            "(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s)})();",
            null,
        )
    }
}
