package io.yiner.browser.core

import org.json.JSONArray

object Suggestions {
    /**
     * 解析 OpenSearch 建议格式：["关键词", ["建议1", "建议2", ...], ...]。
     * Google(client=firefox)、Bing osjson、DuckDuckGo(type=list)、百度 opensearch 都返回这种格式。
     */
    fun parseOpenSearch(body: String, limit: Int = 6): List<String> = runCatching {
        val arr = JSONArray(body)
        val list = arr.getJSONArray(1)
        (0 until list.length()).map { list.getString(it) }.filter { it.isNotBlank() }.distinct().take(limit)
    }.getOrDefault(emptyList())
}
