package io.yiner.browser.data

import io.yiner.browser.core.SearchEngine
import io.yiner.browser.core.Suggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 向所选搜索引擎请求联想词（可在设置中关闭；无痕标签不请求）。走系统正常 TLS 校验。 */
object SuggestClient {
    suspend fun fetch(engine: SearchEngine, query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val conn = URL(engine.suggestUrl(query)).openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != 200) return@runCatching emptyList()
                val charset = conn.contentType?.substringAfter("charset=", "")?.trim()?.ifBlank { null } ?: "UTF-8"
                val body = conn.inputStream.use { String(it.readBytes(), charset(charset)) }
                Suggestions.parseOpenSearch(body)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}
