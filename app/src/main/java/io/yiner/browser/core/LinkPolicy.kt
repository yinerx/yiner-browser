package io.yiner.browser.core

/** 页面导航请求的处理方式。 */
sealed interface LinkDecision {
    /** 交给 WebView 在当前标签内加载。 */
    data object LoadInBrowser : LinkDecision

    /** 需要交给其它 App（电话、邮件、intent:// 等），必须先征得用户同意。 */
    data class OpenExternally(val uri: String) : LinkDecision

    /** 直接拒绝（例如网页试图跳转到本地 file://）。 */
    data object Block : LinkDecision
}

object LinkPolicy {
    private val inBrowserSchemes = setOf(
        "http", "https", "about", "data", "blob", "javascript", "moz-extension", "resource", "view-source",
    )

    fun decide(uri: String, isDirectNavigation: Boolean): LinkDecision {
        val scheme = uri.substringBefore(':', "").lowercase()
        return when {
            scheme.isEmpty() -> LinkDecision.LoadInBrowser
            scheme in inBrowserSchemes -> LinkDecision.LoadInBrowser
            // 只有用户在地址栏亲手输入的 file:// 才允许，网页不能把用户引到本地文件
            scheme == "file" -> if (isDirectNavigation) LinkDecision.LoadInBrowser else LinkDecision.Block
            else -> LinkDecision.OpenExternally(uri)
        }
    }

    /**
     * 从 intent:// 链接中取出 S.browser_fallback_url（目标 App 未安装时使用）。
     * 只接受 http(s)，避免借道跳转到其它协议。
     */
    fun intentFallbackUrl(intentUri: String): String? {
        val extras = intentUri.substringAfter("#Intent;", "").split(';')
        val raw = extras.firstOrNull { it.startsWith("S.browser_fallback_url=") }
            ?.substringAfter('=') ?: return null
        val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
        return decoded.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** 外链确认对话框里给用户看的简短说明。 */
    fun describe(uri: String): String {
        val scheme = uri.substringBefore(':').lowercase()
        return when (scheme) {
            "tel" -> "拨打电话 ${uri.substringAfter(':')}"
            "mailto" -> "发送邮件给 ${uri.substringAfter(':').substringBefore('?')}"
            "sms", "smsto" -> "发送短信"
            "market" -> "在应用商店中打开"
            "intent" -> {
                val pkg = Regex("package=([^;]+)").find(uri)?.groupValues?.get(1)
                if (pkg != null) "在应用 $pkg 中打开" else "在其它应用中打开"
            }
            else -> "使用其它应用打开 $scheme: 链接"
        }
    }
}
