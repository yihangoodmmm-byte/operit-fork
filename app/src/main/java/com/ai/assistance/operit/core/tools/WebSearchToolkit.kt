package com.ai.assistance.operit.core.tools

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网搜索。
 *
 * 为什么自己做而不是等模型内置：模型自带的搜索只在部分供应商、部分模型上生效，
 * 换用的中转站往往没有；而这个工具走的是「一个搜索服务 + 一个 key」的模式，
 * 换供应商时搜索能力不受影响。
 *
 * 设计取舍：
 * - 只实现一个通用后端（DuckDuckGo 的 HTML 端点），不需要 key，开箱可用；
 * - 结果解析用正则抓链接与标题，不引 HTML 解析库（少一个依赖，且搜索结果页结构稳定）；
 * - 结果条数上限 [MAX_RESULTS]，避免把整页塞进上下文。
 */
object WebSearchToolkit {

    const val MAX_RESULTS: Int = 10
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class SearchResult(val title: String, val url: String, val snippet: String)

    /**
     * 执行搜索。返回 (结果列表, 错误说明)，二者互斥。
     */
    fun search(query: String, maxResults: Int = MAX_RESULTS): Pair<List<SearchResult>, String?> {
        if (query.isBlank()) return emptyList<SearchResult>() to "query 不能为空"
        return runCatching {
            val html = fetch(duckDuckGoUrl(query))
            val results = parseDuckDuckGo(html)
            if (results.isEmpty()) {
                return emptyList<SearchResult>() to "没有解析到结果（页面结构可能变了或网络受限）"
            }
            results.take(maxResults.coerceIn(1, MAX_RESULTS)) to null
        }.getOrElse { error ->
            emptyList<SearchResult>() to ("搜索失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun duckDuckGoUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://html.duckduckgo.com/html/?q=" + encoded
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP " + code)
            }
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解析 DuckDuckGo HTML 结果页。
     *
     * 页面结构：每个结果是一个 <a class="result__a" href="...">标题</a>，
     * 摘要跟在后面的 result__snippet。用正则而不是解析器，
     * 因为这里只取三类字段，且页面结构多年未变。
     */
    fun parseDuckDuckGo(html: String): List<SearchResult> {
        val linkRegex = Regex(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetRegex = Regex(
            "<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]).trim() }.toList()

        val out = mutableListOf<SearchResult>()
        var index = 0
        for (match in linkRegex.findAll(html)) {
            val rawUrl = decodeDuckDuckGoUrl(match.groupValues[1])
            val title = stripTags(match.groupValues[2]).trim()
            val snippet = snippets.getOrElse(index) { "" }
            out += SearchResult(title = title, url = rawUrl, snippet = snippet)
            index++
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    /** DuckDuckGo 的跳转链接形如 //duckduckgo.com/l/?uddg=<编码后的真实地址>。 */
    private fun decodeDuckDuckGoUrl(raw: String): String {
        var url = raw
        val marker = url.indexOf("uddg=")
        if (marker >= 0) {
            val encoded = url.substring(marker + 5).substringBefore('&')
            url = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        }
        if (url.startsWith("//")) url = "https:" + url
        return url
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")

    /** 渲染成模型可读的列表。 */
    fun render(results: List<SearchResult>): String {
        if (results.isEmpty()) return "没有结果"
        return buildString {
            results.forEachIndexed { i, r ->
                append(i + 1).append(". ").append(r.title).append('\n')
                append("   ").append(r.url).append('\n')
                if (r.snippet.isNotBlank()) append("   ").append(r.snippet).append('\n')
            }
        }.trimEnd()
    }
}
