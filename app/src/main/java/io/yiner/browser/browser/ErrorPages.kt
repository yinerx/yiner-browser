package io.yiner.browser.browser

import android.util.Base64
import org.mozilla.geckoview.WebRequestError

/**
 * 加载失败时显示的本地错误页。证书/安全类错误只提示、不提供“继续访问”，
 * 不绕过 TLS 校验。
 */
object ErrorPages {
    fun dataUri(failedUri: String?, error: WebRequestError): String {
        val (title, detail) = describe(error)
        val safeUri = escape(failedUri.orEmpty())
        val retry = if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY ||
            error.category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING
        ) {
            ""
        } else {
            "<button onclick=\"location.replace('${escapeJs(failedUri.orEmpty())}')\">重试</button>"
        }
        val html = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escape(title)}</title>
            <style>
              :root{color-scheme:light dark}
              body{font-family:sans-serif;margin:0;padding:15vh 24px;background:Canvas;color:CanvasText}
              h1{font-size:20px;margin:0 0 12px} p{line-height:1.6;opacity:.8}
              code{word-break:break-all;font-size:13px;opacity:.7}
              button{margin-top:20px;padding:10px 22px;border:0;border-radius:20px;background:#c8892e;color:#fff;font-size:15px}
            </style></head><body>
            <h1>${escape(title)}</h1><p>${escape(detail)}</p><code>$safeUri</code><br>$retry
            </body></html>
        """.trimIndent()
        val b64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "data:text/html;charset=utf-8;base64,$b64"
    }

    private fun describe(e: WebRequestError): Pair<String, String> = when (e.code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> "找不到服务器" to "请检查网址是否正确，或网络是否可用。"
        WebRequestError.ERROR_OFFLINE -> "网络不可用" to "设备当前处于离线状态。"
        WebRequestError.ERROR_CONNECTION_REFUSED -> "连接被拒绝" to "服务器拒绝了连接。"
        WebRequestError.ERROR_NET_TIMEOUT -> "连接超时" to "服务器响应时间过长。"
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "连接中断" to "与服务器的连接被重置。"
        WebRequestError.ERROR_REDIRECT_LOOP -> "重定向次数过多" to "该页面一直在跳转，无法打开。"
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL,
        WebRequestError.ERROR_BAD_HSTS_CERT,
        -> "连接不安全，已阻止" to "该网站的安全证书无效或连接无法验证。为保护你的数据，银耳浏览器不会继续加载此页面。"
        WebRequestError.ERROR_HTTPS_ONLY -> "该网站不支持安全连接" to "你已开启“仅 HTTPS 模式”，此网站只能通过不加密的 HTTP 访问。可在设置中关闭该模式。"
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI, WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
        -> "已拦截危险网站" to "该网站被标记为欺诈或含有恶意软件。"
        WebRequestError.ERROR_MALFORMED_URI -> "网址无效" to "无法识别这个网址。"
        WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "无法打开此链接" to "没有可以处理该协议的应用。"
        WebRequestError.ERROR_FILE_NOT_FOUND -> "找不到文件" to "请求的文件不存在。"
        WebRequestError.ERROR_CONTENT_CRASHED -> "页面已崩溃" to "请重试。"
        else -> "无法打开此页面" to "加载时出错（错误代码 ${e.code}）。"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escapeJs(s: String) = escape(s.replace("\\", "\\\\").replace("'", "\\'"))
}
