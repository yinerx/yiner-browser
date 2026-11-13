package io.yiner.browser.core

import java.net.URLDecoder

object DownloadNaming {
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /**
     * 按 RFC 6266 解析 Content-Disposition 中的文件名，优先 filename*（RFC 5987 编码）。
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val v = star.groupValues[1].trim().trim('"')
            val parts = v.split("'", limit = 3)
            if (parts.size == 3) {
                val charset = parts[0].ifBlank { "UTF-8" }
                runCatching { return sanitize(URLDecoder.decode(parts[2].replace("+", "%2B"), charset)) }
            }
        }
        val plain = Regex("filename\\s*=\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|[^;]+)", RegexOption.IGNORE_CASE)
            .find(header) ?: return null
        val value = plain.groupValues[2].ifEmpty { plain.groupValues[1].trim() }
            .replace("\\\"", "\"")
        return sanitize(value)
    }

    /** 从 URL 路径的最后一段推测文件名。 */
    fun fromUrl(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val path = url.substringAfter("://").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        return sanitize(decoded)
    }

    /**
     * 最终文件名：header > URL > "download"，没有扩展名时按 MIME 补全。
     * [extensionForMime] 由调用方提供（Android 上用 MimeTypeMap）。
     */
    fun choose(
        contentDisposition: String?,
        url: String,
        mimeType: String?,
        extensionForMime: (String) -> String?,
    ): String {
        val base = fromContentDisposition(contentDisposition) ?: fromUrl(url) ?: "download"
        if (base.contains('.') && !base.endsWith('.')) return base
        val ext = mimeType?.substringBefore(';')?.trim()?.let(extensionForMime)
        return if (ext.isNullOrBlank()) base else "$base.$ext"
    }

    fun sanitize(name: String): String? {
        val cleaned = name.replace(illegal, "_").trim().trim('.').take(200)
        return cleaned.ifBlank { null }
    }

    /** 人类可读的文件大小。 */
    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    }
}
