package io.yiner.browser.blocking

import java.net.URI

/**
 * 广告与跟踪拦截规则引擎（纯 Kotlin，不依赖 Android，可单元测试）。
 *
 * 只实现 Adblock Plus 语法中能在 WebView 上可靠执行的子集：
 * - 网络拦截：`||host^`、`||host^$third-party`、`||host/path`（无通配符）、例外 `@@||host^`；
 * - 元素隐藏：通用 `##selector`、站点 `a.com,b.com##selector`、站点例外 `a.com#@#selector`。
 *
 * 不支持（解析时跳过，宁可漏拦也不误拦）：通配符/正则规则、除 `third-party` 外的选项（资源类型、`domain=` 等）、
 * 带 `~` 否定的站点规则、扩展/过程式选择器（`#?#`、`#$#`、`:has-text` 等）、脚本注入（`+js`）、`$removeparam` 等。
 */
class FilterEngine private constructor(
    private val blockedHosts: HashSet<String>,
    private val thirdPartyHosts: HashSet<String>,
    private val pathRules: HashMap<String, MutableList<String>>,
    private val exceptions: HashMap<String, MutableList<Exception>>,
    private val typedRules: HashMap<String, MutableList<TypedRule>>,
    private val genericHide: List<String>,
    private val domainHide: HashMap<String, MutableList<String>>,
    private val domainHideExceptions: HashMap<String, MutableSet<String>>,
    val stats: Stats,
) {
    data class Stats(val totalRules: Int, val networkRules: Int, val cosmeticRules: Int, val skippedRules: Int)

    /**
     * 网络例外（`@@||host/path$domain=a.com|b.com`）：只在路径前缀匹配、且（若指定了 domain=）当前网页属于这些站点时放行。
     * 路径含通配符时取通配符之前的部分作前缀（例外范围略宽于原意，只会少拦，不会多拦）。
     */
    data class Exception(val pathPrefix: String, val pageDomains: Set<String>?)

    /** 由请求的 Accept 头推断的资源类型（WebView 拦截接口不直接提供资源类型）。 */
    enum class ResourceType { IMAGE, STYLESHEET, SUBDOCUMENT, UNKNOWN }

    /** 带资源类型选项的规则，例如 `||host^$script,third-party,xmlhttprequest`。 */
    data class TypedRule(val types: Set<String>, val thirdParty: Boolean) {
        fun matches(type: ResourceType): Boolean = when (type) {
            ResourceType.IMAGE -> "image" in types
            ResourceType.STYLESHEET -> "stylesheet" in types
            ResourceType.SUBDOCUMENT -> "subdocument" in types
            // 推断不出类型（脚本、XHR/fetch、ping、媒体等）：规则包含这些类型之一时才算匹配
            ResourceType.UNKNOWN -> types.any { it in unknownTypes }
        }
    }

    /** 判断子资源请求是否应拦截。主文档（用户打开的网页本身）由调用方保证不传进来。 */
    fun shouldBlock(url: String, pageHost: String?, type: ResourceType = ResourceType.UNKNOWN): Boolean {
        val u = parse(url) ?: return false
        if (u.scheme != "http" && u.scheme != "https") return false
        val host = u.host?.lowercase()?.trimEnd('.') ?: return false
        val suffixes = suffixes(host)
        val path = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
        val pageSuffixes by lazy { pageHost?.lowercase()?.let { suffixes(it) }.orEmpty() }
        for (s in suffixes) {
            val list = exceptions[s] ?: continue
            if (list.any { e -> path.startsWith(e.pathPrefix) && (e.pageDomains == null || pageSuffixes.any { it in e.pageDomains }) }) {
                return false
            }
        }
        for (s in suffixes) {
            if (s in blockedHosts) return true
            if (s in thirdPartyHosts && isThirdParty(host, pageHost)) return true
            pathRules[s]?.let { prefixes -> if (prefixes.any { path.startsWith(it) }) return true }
            typedRules[s]?.let { rules ->
                if (rules.any { r -> r.matches(type) && (!r.thirdParty || isThirdParty(host, pageHost)) }) return true
            }
        }
        return false
    }

    /** 通用规则的 CSS 只生成一次（约 1.3 万条选择器）。 */
    private val genericCss: String by lazy { toCss(genericHide) }

    private fun toCss(selectors: Collection<String>): String =
        // 分组书写：一个无效选择器只会让所在的这一组失效，不影响其余规则
        selectors.chunked(CSS_CHUNK).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }

    /** 某个网页需要注入的元素隐藏 CSS；为空表示无需注入。 */
    fun cssFor(pageHost: String?): String {
        val host = pageHost?.lowercase()?.trimEnd('.')
        val hostSuffixes = host?.let { suffixes(it) }.orEmpty()
        if (hostSuffixes.none { it in domainHideExceptions }) {
            // 常见情况：本站没有例外，直接复用通用 CSS
            val extra = hostSuffixes.flatMap { domainHide[it].orEmpty() }.filter { it !in genericHide }
            return listOf(genericCss, toCss(extra)).filter { it.isNotEmpty() }.joinToString("\n")
        }
        val selectors = LinkedHashSet<String>(genericHide)
        val exceptions = HashSet<String>()
        if (host != null) {
            for (s in suffixes(host)) {
                domainHide[s]?.let { selectors.addAll(it) }
                domainHideExceptions[s]?.let { exceptions.addAll(it) }
            }
        }
        selectors.removeAll(exceptions)
        return toCss(selectors)
    }

    companion object {
        private const val CSS_CHUNK = 40
        private val hostRe = Regex("^[a-z0-9.-]+$")

        fun parse(vararg lists: String): FilterEngine {
            val blocked = HashSet<String>()
            val third = HashSet<String>()
            val paths = HashMap<String, MutableList<String>>()
            val exceptions = HashMap<String, MutableList<Exception>>()
            val typed = HashMap<String, MutableList<TypedRule>>()
            val generic = ArrayList<String>()
            val domainHide = HashMap<String, MutableList<String>>()
            val domainExc = HashMap<String, MutableSet<String>>()
            var total = 0
            var network = 0
            var cosmetic = 0
            var skipped = 0

            for (text in lists) for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
                total++
                when {
                    line.contains("#@#") -> {
                        val (domains, sel) = line.split("#@#", limit = 2).let { it[0] to it[1] }
                        val ds = parseDomains(domains)
                        if (ds == null || ds.isEmpty() || !isPlainSelector(sel)) { skipped++; continue }
                        ds.forEach { domainExc.getOrPut(it) { HashSet() }.add(sel) }
                        cosmetic++
                    }
                    line.contains("##") -> {
                        val (domains, sel) = line.split("##", limit = 2).let { it[0] to it[1] }
                        if (!isPlainSelector(sel)) { skipped++; continue }
                        if (domains.isEmpty()) {
                            generic.add(sel)
                        } else {
                            val ds = parseDomains(domains)
                            if (ds == null || ds.isEmpty()) { skipped++; continue }
                            ds.forEach { domainHide.getOrPut(it) { ArrayList() }.add(sel) }
                        }
                        cosmetic++
                    }
                    line.contains("#?#") || line.contains("#$#") || line.contains("#%#") -> skipped++
                    line.startsWith("@@||") -> {
                        val e = parseException(line.removePrefix("@@||"))
                        if (e == null) { skipped++; continue }
                        exceptions.getOrPut(e.first) { ArrayList() }.add(e.second); network++
                    }
                    line.startsWith("||") -> {
                        val typedRule = parseTypedRule(line)
                        if (typedRule != null) {
                            typed.getOrPut(typedRule.first) { ArrayList() }.add(typedRule.second); network++; continue
                        }
                        val rule = networkHost(line, allowOptions = false)
                        if (rule == null) { skipped++; continue }
                        val (host, rest, thirdParty) = rule
                        when {
                            rest.isEmpty() && thirdParty -> third.add(host)
                            rest.isEmpty() -> blocked.add(host)
                            !thirdParty -> paths.getOrPut(host) { ArrayList() }.add(rest)
                            else -> { skipped++; continue }
                        }
                        network++
                    }
                    else -> skipped++
                }
            }
            return FilterEngine(
                blocked, third, paths, exceptions, typed, generic, domainHide, domainExc,
                Stats(total, network, cosmetic, skipped),
            )
        }

        /**
         * 解析 `||host^`、`||host^$third-party`、`||host/path`。
         * 返回 (host, 路径前缀, 是否仅第三方)；不支持的写法返回 null。
         * [allowOptions] 为 true 时（例外规则）忽略选项，只取域名。
         */
        private fun networkHost(rule: String, allowOptions: Boolean): Triple<String, String, Boolean>? {
            var body = rule.removePrefix("||")
            var thirdParty = false
            val dollar = body.indexOf('$')
            if (dollar >= 0) {
                val options = body.substring(dollar + 1).split(',')
                body = body.substring(0, dollar)
                if (!allowOptions) {
                    if (options != listOf("third-party")) return null
                    thirdParty = true
                }
            }
            if (body.contains('*') || body.contains('|')) return null
            val sep = body.indexOfFirst { it == '^' || it == '/' || it == ':' }
            val host = (if (sep < 0) body else body.substring(0, sep)).lowercase()
            if (!hostRe.matches(host) || !host.contains('.')) return null
            if (sep < 0) return if (allowOptions) Triple(host, "", false) else null
            val tail = body.substring(sep)
            return when {
                tail == "^" -> Triple(host, "", thirdParty)
                allowOptions -> Triple(host, "", false)
                tail.startsWith("/") && !tail.contains('^') -> Triple(host, tail, thirdParty)
                tail.startsWith("/") && tail.endsWith("^") && tail.count { it == '^' } == 1 -> Triple(host, tail.dropLast(1), thirdParty)
                else -> null
            }
        }

        private val resourceTypes = setOf(
            "script", "image", "stylesheet", "xmlhttprequest", "subdocument", "ping", "media", "font", "object", "other", "websocket",
        )
        private val unknownTypes = setOf("script", "xmlhttprequest", "ping", "media", "font", "object", "other", "websocket")

        /**
         * `||host^$类型[,third-party]`：只含资源类型（可加 third-party）的整域规则。
         * 含其它选项（domain=、~类型、popup、important 等）或带路径的返回 null（交给其它分支或跳过）。
         */
        private fun parseTypedRule(rule: String): Pair<String, TypedRule>? {
            val dollar = rule.indexOf('$')
            if (dollar < 0) return null
            val options = rule.substring(dollar + 1).split(',').map { it.trim() }
            val types = options.filter { it != "third-party" }.toSet()
            if (types.isEmpty() || !types.all { it in resourceTypes }) return null
            val body = rule.substring(2, dollar)
            if (!body.endsWith("^")) return null
            val host = body.dropLast(1).lowercase()
            if (!hostRe.matches(host) || !host.contains('.')) return null
            return host to TypedRule(types, "third-party" in options)
        }

        /** 由 Accept 请求头推断资源类型。 */
        fun typeFromAccept(accept: String?): ResourceType {
            val a = accept?.lowercase() ?: return ResourceType.UNKNOWN
            return when {
                a.startsWith("image/") || a.contains("image/webp") || a.contains("image/avif") -> ResourceType.IMAGE
                a.startsWith("text/css") -> ResourceType.STYLESHEET
                a.startsWith("text/html") -> ResourceType.SUBDOCUMENT
                else -> ResourceType.UNKNOWN
            }
        }

        /** 解析例外规则主体（已去掉 "@@||"）。带否定站点（~）等无法准确表达的写法返回 null。 */
        private fun parseException(body0: String): Pair<String, Exception>? {
            var body = body0
            var domains: Set<String>? = null
            val dollar = body.indexOf('$')
            if (dollar >= 0) {
                val options = body.substring(dollar + 1).split(',')
                body = body.substring(0, dollar)
                options.firstOrNull { it.startsWith("domain=") }?.let { opt ->
                    val ds = opt.removePrefix("domain=").split('|').map { it.trim().lowercase() }
                    if (ds.any { it.startsWith("~") || !hostRe.matches(it) }) return null
                    domains = ds.toSet()
                }
            }
            val sep = body.indexOfFirst { it == '^' || it == '/' || it == ':' || it == '*' || it == '|' }
            val host = (if (sep < 0) body else body.substring(0, sep)).lowercase()
            if (!hostRe.matches(host) || !host.contains('.')) return null
            var prefix = if (sep < 0) "" else body.substring(sep)
            prefix = prefix.substringBefore('*').substringBefore('^').substringBefore('|')
            if (prefix.isNotEmpty() && !prefix.startsWith("/")) prefix = ""
            return host to Exception(prefix, domains)
        }

        /** "a.com,b.com" → 列表；含否定（~）或非法字符时返回 null。 */
        private fun parseDomains(s: String): List<String>? {
            val parts = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (parts.any { it.startsWith("~") || !hostRe.matches(it) }) return null
            return parts
        }

        private fun isPlainSelector(sel: String): Boolean =
            sel.isNotBlank() && !sel.startsWith("+js(") && !sel.contains(":-abp-") && !sel.contains(":has-text") &&
                !sel.contains(":style(") && !sel.contains(":xpath") && !sel.contains(":remove(") &&
                !sel.contains(":matches-") && !sel.contains(":upward") && !sel.contains(":min-text") &&
                !sel.contains('{') && !sel.contains('}')

        /** "a.b.example.co.uk" → [a.b.example.co.uk, b.example.co.uk, example.co.uk, co.uk]（不含顶级域本身）。 */
        fun suffixes(host: String): List<String> {
            val labels = host.split('.')
            return (0 until (labels.size - 1).coerceAtLeast(1)).map { labels.drop(it).joinToString(".") }
        }

        private val multiPartSuffixes = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.jp", "ne.jp", "or.jp",
            "com.cn", "net.cn", "org.cn", "gov.cn", "com.hk", "com.tw", "co.kr", "com.br", "co.in", "co.nz", "com.sg",
        )

        /** 近似的“可注册域名”（未使用完整公共后缀列表）。 */
        fun baseDomain(host: String): String {
            val labels = host.lowercase().trimEnd('.').split('.')
            if (labels.size <= 2) return labels.joinToString(".")
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in multiPartSuffixes) labels.takeLast(3).joinToString(".") else lastTwo
        }

        fun isThirdParty(requestHost: String, pageHost: String?): Boolean =
            pageHost.isNullOrBlank() || baseDomain(requestHost) != baseDomain(pageHost)

        private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()
    }
}
