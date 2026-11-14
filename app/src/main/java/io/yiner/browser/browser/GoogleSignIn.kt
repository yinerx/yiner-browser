package io.yiner.browser.browser

import java.net.URI

/**
 * Google 登录页识别。Google OAuth 政策禁止把授权请求发往开发者控制的“内嵌用户代理”，
 * 系统 WebView 属于此类，Google 可能拒绝在其中登录（常见报错 disallowed_useragent）。
 * 银耳浏览器不伪装身份、不删除 WebView 标识：打开这些页面前提示用户，可选择继续尝试或改用其它浏览器。
 */
object GoogleSignIn {
    private val paths = listOf("/signin", "/v3/signin", "/o/oauth2", "/servicelogin", "/accountchooser", "/interactivelogin", "/signup", "/lifecycle/flows/signup")

    fun isSignInUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (u.scheme != "https" || !u.host.equals("accounts.google.com", ignoreCase = true)) return false
        val path = (u.rawPath ?: "").lowercase()
        return paths.any { path.startsWith(it) }
    }
}
