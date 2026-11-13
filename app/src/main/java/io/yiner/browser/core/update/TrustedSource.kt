package io.yiner.browser.core.update

import java.net.URI
import java.net.URLDecoder

/**
 * 更新来源白名单。只接受本仓库 Release 附件的固定 URL 形式，
 * 不接受任何来自用户输入或元数据文件里的任意 URL。
 */
class TrustedSource(val repo: String) {
    init {
        require(Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(repo)) { "无效的仓库名：$repo" }
    }

    /** 公开 API，不带 token。返回最新的正式版（GitHub 本身就排除 draft 和 prerelease）。 */
    val latestReleaseApi: String get() = "https://api.github.com/repos/$repo/releases/latest"

    val releasesPage: String get() = "https://github.com/$repo/releases"

    /**
     * 附件下载地址必须严格等于
     * https://github.com/{repo}/releases/download/{tag}/{assetName}
     * （不允许端口、用户信息、查询参数或片段）。
     */
    fun isTrustedAssetUrl(url: String, tag: String, assetName: String): Boolean {
        val u = parse(url) ?: return false
        if (u.scheme != "https" || !u.host.equals("github.com", ignoreCase = true)) return false
        if (u.rawUserInfo != null || (u.port != -1 && u.port != 443)) return false
        if (u.rawQuery != null || u.rawFragment != null) return false
        val segments = u.rawPath.orEmpty().split('/').drop(1).map { decode(it) ?: return false }
        if (segments.size != 6) return false
        // 仓库 owner/name 在 GitHub 上大小写不敏感；其余部分必须完全一致
        val sameRepo = segments.take(2).map { it.lowercase() } == repo.split('/').map { it.lowercase() }
        return sameRepo && segments.drop(2) == listOf("releases", "download", tag, assetName)
    }

    /** Release 网页地址只用于“查看发布说明”，也限定在本仓库。 */
    fun isTrustedReleasePage(url: String): Boolean {
        val u = parse(url) ?: return false
        return u.scheme == "https" && u.host.equals("github.com", ignoreCase = true) && u.rawUserInfo == null &&
            u.rawPath.orEmpty().lowercase().startsWith("/${repo.lowercase()}/releases/")
    }

    companion object {
        /**
         * 下载过程中允许经过的重定向主机（GitHub 会把附件 302 到自己的 CDN）。
         * 每一跳都必须是 HTTPS 且在名单内。完整性另由哈希与签名校验保证。
         */
        val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        fun isAllowedRedirect(url: String): Boolean {
            val u = parse(url) ?: return false
            return u.scheme == "https" && u.host != null && u.host.lowercase() in ALLOWED_DOWNLOAD_HOSTS &&
                u.rawUserInfo == null && (u.port == -1 || u.port == 443)
        }

        private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf { it.isAbsolute && it.host != null }

        private fun decode(s: String): String? = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}
