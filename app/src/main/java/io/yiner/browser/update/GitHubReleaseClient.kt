package io.yiner.browser.update

import io.yiner.browser.BuildConfig
import io.yiner.browser.core.update.CheckPolicy
import io.yiner.browser.core.update.TrustedSource
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApiResult {
    data class Ok(val body: String, val etag: String?) : ApiResult
    data object NotModified : ApiResult
    /** 仓库还没有任何正式版 Release（或仓库不可见）。 */
    data object NotFound : ApiResult
    data class RateLimited(val resetAt: Long) : ApiResult
    data class HttpError(val code: Int) : ApiResult
    data class NetworkError(val error: IOException) : ApiResult
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * 只使用 GitHub 公开 API，不携带、不读取任何 token。
 * 用 ETag 条件请求：未变化时返回 304，不消耗限流额度。
 */
class GitHubReleaseClient(private val source: TrustedSource) {

    fun fetchLatest(etag: String?): ApiResult {
        val conn = try {
            (URL(source.latestReleaseApi).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
                if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
            }
        } catch (e: IOException) {
            return ApiResult.NetworkError(e)
        }
        return try {
            when (val code = conn.responseCode) {
                200 -> ApiResult.Ok(conn.inputStream.use { readLimited(it, 2 * 1024 * 1024) }, conn.getHeaderField("ETag"))
                304 -> ApiResult.NotModified
                404 -> ApiResult.NotFound
                403, 429 -> {
                    val remaining = conn.getHeaderField("x-ratelimit-remaining")
                    val retryAfter = conn.getHeaderField("Retry-After")
                    if (code == 429 || remaining == "0" || retryAfter != null) {
                        ApiResult.RateLimited(
                            CheckPolicy.rateLimitResetAt(System.currentTimeMillis(), retryAfter, conn.getHeaderField("x-ratelimit-reset")),
                        )
                    } else {
                        ApiResult.HttpError(code)
                    }
                }
                else -> ApiResult.HttpError(code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        val USER_AGENT = "YinerBrowser/${BuildConfig.VERSION_NAME} (Android; +https://github.com/${BuildConfig.UPDATE_REPO})"

        fun readLimited(input: InputStream, max: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException("响应过大")
            }
            return out.toString("UTF-8")
        }
    }
}

/**
 * 下载 Release 附件：手动处理重定向，每一跳都必须是 HTTPS 且主机在白名单内。
 * 支持 Range 断点续传。
 */
object SafeDownloader {
    class Response(val connection: HttpURLConnection, val code: Int, val finalUrl: String)

    /**
     * 打开连接并跟随重定向。调用方负责关闭 [Response.connection]。
     * @param startOffset 大于 0 时发送 Range 请求。
     */
    fun open(trustedUrl: String, startOffset: Long = 0): Response {
        var url = trustedUrl
        repeat(6) {
            if (!TrustedSource.isAllowedRedirect(url)) throw IOException("下载地址不在可信名单内：${hostOf(url)}")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", GitHubReleaseClient.USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
                if (startOffset > 0) setRequestProperty("Range", "bytes=$startOffset-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("重定向缺少目标地址")
                url = URL(URL(url), location).toString()
                return@repeat
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                throw HttpStatusException(code)
            }
            return Response(conn, code, url)
        }
        throw IOException("重定向次数过多")
    }

    fun fetchSmall(trustedUrl: String, maxBytes: Int): String {
        val r = open(trustedUrl)
        try {
            return r.connection.inputStream.use { GitHubReleaseClient.readLimited(it, maxBytes) }
        } finally {
            r.connection.disconnect()
        }
    }

    private fun hostOf(url: String) = runCatching { URL(url).host }.getOrDefault("?")
}
