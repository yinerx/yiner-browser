package io.yiner.browser.browser

/**
 * 本地错误页。证书错误、安全浏览拦截只提示，不提供“继续访问”，不绕过 TLS 校验。
 * （普通网络错误使用系统 WebView 自带的错误页。）
 */
object ErrorPages {
    fun certificateError(url: String): String = page(
        "连接不安全，已阻止",
        "该网站的安全证书无效或连接无法验证。为保护你的数据，银耳浏览器不会继续加载此页面。",
        url,
    )

    fun page(title: String, detail: String, url: String): String = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${escape(title)}</title>
        <style>
          :root{color-scheme:light dark}
          body{font-family:sans-serif;margin:0;padding:15vh 24px;background:Canvas;color:CanvasText}
          h1{font-size:20px;margin:0 0 12px} p{line-height:1.6;opacity:.8}
          code{word-break:break-all;font-size:13px;opacity:.7}
        </style></head><body>
        <h1>${escape(title)}</h1><p>${escape(detail)}</p><code>${escape(url)}</code>
        </body></html>
    """.trimIndent()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
