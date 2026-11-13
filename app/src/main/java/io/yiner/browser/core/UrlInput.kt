package io.yiner.browser.core

import java.net.URLEncoder

/** 支持的搜索引擎。`template` 中的 %s 会被替换为 URL 编码后的关键词。 */
enum class SearchEngine(
    val id: String,
    val label: String,
    val template: String,
    val suggestTemplate: String,
) {
    GOOGLE(
        "google", "Google",
        "https://www.google.com/search?q=%s",
        "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
    ),
    BING(
        "bing", "Bing",
        "https://www.bing.com/search?q=%s",
        "https://api.bing.com/osjson.aspx?query=%s",
    ),
    DUCKDUCKGO(
        "duckduckgo", "DuckDuckGo",
        "https://duckduckgo.com/?q=%s",
        "https://duckduckgo.com/ac/?type=list&q=%s",
    ),
    BAIDU(
        "baidu", "百度",
        "https://www.baidu.com/s?wd=%s",
        "https://suggestion.baidu.com/su?action=opensearch&ie=utf-8&wd=%s",
    );

    fun searchUrl(query: String): String = template.replace("%s", encode(query))
    fun suggestUrl(query: String): String = suggestTemplate.replace("%s", encode(query))

    companion object {
        val DEFAULT = GOOGLE
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: DEFAULT
        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

object UrlInput {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hostLike = Regex(
        "^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|\\[[0-9a-fA-F:]+]|([\\p{L}\\p{N}-]+\\.)+[\\p{L}\\p{N}-]{2,})(:\\d{1,5})?([/?#].*)?$",
    )

    /** 允许用户在地址栏直接输入的协议；其它（如 javascript:）一律当作搜索词。 */
    private val typeableSchemes = setOf("http", "https", "about", "view-source")

    /**
     * 把地址栏输入转换为要加载的 URL：
     * - 带允许协议的输入原样返回
     * - 像域名/IP 的输入补 https://
     * - 其它当作搜索词
     */
    fun toLoadableUrl(raw: String, engine: SearchEngine): String {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        if (schemeRegex.containsMatchIn(input) && !input.contains(' ')) {
            val scheme = input.substringBefore(':').lowercase()
            if (scheme in typeableSchemes) return input
            // "example.com:8080" 这种形式会被误认成协议，交给下面的域名判断
            if (!hostLike.matches(input)) return engine.searchUrl(input)
        }
        if (!input.contains(' ') && hostLike.matches(input)) {
            val scheme = if (input.startsWith("localhost") || isPrivateIp(input)) "http" else "https"
            return "$scheme://$input"
        }
        return engine.searchUrl(input)
    }

    /** 从任意文本（例如其它 App 分享过来的“标题 + 链接”）中提取第一个 http(s) 链接。 */
    fun extractUrl(text: String): String? =
        Regex("https?://[^\\s<>\"']+").find(text)?.value?.trimEnd('.', ',', ')', '，', '。', '）')

    /** 地址栏未聚焦时显示的简短主机名。 */
    fun displayHost(url: String): String {
        if (url.isBlank() || url == "about:blank") return ""
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .removePrefix("www.").substringAfterLast('@')
    }

    private fun isPrivateIp(input: String): Boolean {
        val host = input.substringBefore('/').substringBefore(':')
        return host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("127.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
    }
}
