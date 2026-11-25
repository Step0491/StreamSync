package com.streamsync.app.adblock

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ResourceKind {
    MAIN_FRAME,
    SUBDOCUMENT,
    SCRIPT,
    IMAGE,
    STYLESHEET,
    XHR,
    MEDIA,
    POPUP,
    OTHER
}

data class FilterMatchInput(
    val requestUrl: String,
    val pageUrl: String?,
    val resourceKind: ResourceKind = ResourceKind.OTHER
)

/**
 * Pure-JVM uBlock / EasyList static filter matcher so it can be unit-tested on the JVM
 * without Android framework mocks and reused across platforms.
 */
class FilterRuleEngine {

    data class RuleOptions(
        val isException: Boolean,
        val thirdPartyOnly: Boolean?,
        val allowedKinds: Set<ResourceKind>?,
        val includeDomains: Set<String>,
        val excludeDomains: Set<String>
    )

    data class DomainRule(
        val domain: String,
        val pathPrefix: String?,
        val options: RuleOptions
    )

    data class PatternRule(
        val rawPattern: String,
        val regex: Regex?,
        val substring: String?,
        val options: RuleOptions
    )

    private val exceptionDomainRules = HashMap<String, MutableList<DomainRule>>()
    private val exceptionPatternRules = ArrayList<PatternRule>()
    private val blockDomainRules = HashMap<String, MutableList<DomainRule>>()
    private val blockPatternRules = ArrayList<PatternRule>()
    private val cosmeticSelectorList = LinkedHashSet<String>()

    val cosmeticSelectors: List<String>
        get() = cosmeticSelectorList.toList()

    val totalLoadedRules: Int
        get() = exceptionDomainRules.values.sumOf { it.size } +
            exceptionPatternRules.size +
            blockDomainRules.values.sumOf { it.size } +
            blockPatternRules.size +
            cosmeticSelectorList.size

    fun loadRules(lines: Sequence<String>) {
        lines.forEach { rawLine ->
            parseLine(rawLine)
        }
    }

    private fun parseLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("!")) return

        // Cosmetic element hiding rules (e.g. ##.ad-banner)
        val cosmeticIdx = line.indexOf("##")
        if (cosmeticIdx == 0) {
            val selector = line.substring(2).trim()
            if (selector.isNotEmpty()) {
                cosmeticSelectorList.add(selector)
            }
            return
        }
        if (line.startsWith("#") || line.contains("#@#") || line.contains("#?#")) {
            return
        }

        var working = line
        val isException = if (working.startsWith("@@")) {
            working = working.substring(2)
            true
        } else {
            false
        }

        val options = extractOptions(working, isException)
        val patternPart = options.first
        val ruleOpts = options.second ?: return
        if (patternPart.isEmpty()) return

        if (patternPart.startsWith("||")) {
            val body = patternPart.substring(2).trim()
            val sepIdx = body.indexOfFirst { it == '^' || it == '/' }
            val domainPart = if (sepIdx >= 0) body.substring(0, sepIdx) else body
            val restPart = if (sepIdx >= 0) body.substring(sepIdx) else ""
            val cleanDomain = domainPart.lowercase(Locale.ROOT).trimEnd('.')
            if (cleanDomain.isNotEmpty() && !cleanDomain.contains('*')) {
                val cleanPath = when {
                    restPart.isEmpty() || restPart == "^" || restPart == "/" -> null
                    restPart.startsWith("^") -> restPart.substring(1).takeIf { it.isNotEmpty() }
                    else -> restPart
                }
                if (cleanPath == null || !cleanPath.contains('*')) {
                    val rule = DomainRule(
                        domain = cleanDomain,
                        pathPrefix = cleanPath?.lowercase(Locale.ROOT),
                        options = ruleOpts
                    )
                    val targetMap = if (isException) exceptionDomainRules else blockDomainRules
                    targetMap.getOrPut(cleanDomain) { ArrayList() }.add(rule)
                    return
                }
            }
        }

        val patternRule = compilePatternRule(patternPart, ruleOpts) ?: return
        if (isException) {
            exceptionPatternRules.add(patternRule)
        } else {
            blockPatternRules.add(patternRule)
        }
    }

    private fun extractOptions(
        expr: String,
        isException: Boolean
    ): Pair<String, RuleOptions?> {
        val dollarIdx = expr.lastIndexOf('$')
        // Ignore '$' if it appears to be regex end-anchor with no valid option tokens
        if (dollarIdx <= 0 || dollarIdx == expr.length - 1) {
            return expr to RuleOptions(
                isException = isException,
                thirdPartyOnly = null,
                allowedKinds = null,
                includeDomains = emptySet(),
                excludeDomains = emptySet()
            )
        }

        val rawOpts = expr.substring(dollarIdx + 1)
        val pattern = expr.substring(0, dollarIdx)

        var thirdParty: Boolean? = null
        val kinds = HashSet<ResourceKind>()
        val includeDomains = HashSet<String>()
        val excludeDomains = HashSet<String>()

        for (token in rawOpts.split(',')) {
            val t = token.trim().lowercase(Locale.ROOT)
            if (t.isEmpty()) continue
            when {
                t == "third-party" || t == "3p" -> thirdParty = true
                t == "~third-party" || t == "1p" || t == "first-party" -> thirdParty = false
                t == "script" -> kinds.add(ResourceKind.SCRIPT)
                t == "image" -> kinds.add(ResourceKind.IMAGE)
                t == "stylesheet" || t == "css" -> kinds.add(ResourceKind.STYLESHEET)
                t == "xmlhttprequest" || t == "xhr" -> kinds.add(ResourceKind.XHR)
                t == "media" -> kinds.add(ResourceKind.MEDIA)
                t == "subdocument" || t == "frame" -> kinds.add(ResourceKind.SUBDOCUMENT)
                t == "popup" -> kinds.add(ResourceKind.POPUP)
                t == "other" -> kinds.add(ResourceKind.OTHER)
                t == "important" || t == "badfilter" -> {
                    if (t == "badfilter") return pattern to null
                }
                t.startsWith("domain=") -> {
                    val domList = t.removePrefix("domain=").split('|')
                    for (d in domList) {
                        val clean = d.trim()
                        if (clean.startsWith("~")) {
                            val ex = clean.substring(1).trim()
                            if (ex.isNotEmpty()) excludeDomains.add(ex)
                        } else if (clean.isNotEmpty()) {
                            includeDomains.add(clean)
                        }
                    }
                }
            }
        }

        return pattern to RuleOptions(
            isException = isException,
            thirdPartyOnly = thirdParty,
            allowedKinds = if (kinds.isEmpty()) null else kinds,
            includeDomains = includeDomains,
            excludeDomains = excludeDomains
        )
    }

    private fun compilePatternRule(pattern: String, options: RuleOptions): PatternRule? {
        if (pattern.length >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            return try {
                PatternRule(
                    rawPattern = pattern,
                    regex = Regex(pattern.substring(1, pattern.length - 1), RegexOption.IGNORE_CASE),
                    substring = null,
                    options = options
                )
            } catch (_: Exception) {
                null
            }
        }

        if (!pattern.contains('*') && !pattern.contains('^') && !pattern.startsWith("|")) {
            return PatternRule(
                rawPattern = pattern,
                regex = null,
                substring = pattern.lowercase(Locale.ROOT),
                options = options
            )
        }

        val sb = StringBuilder()
        var i = 0
        if (pattern.startsWith("||")) {
            sb.append("""^https?://([a-z0-9-]+\.)*""")
            i = 2
        } else if (pattern.startsWith("|")) {
            sb.append("^")
            i = 1
        }

        val endAnchored = pattern.endsWith("|")
        val endIdx = if (endAnchored && pattern.length > i) pattern.length - 1 else pattern.length

        while (i < endIdx) {
            when (val ch = pattern[i]) {
                '*' -> sb.append(".*")
                '^' -> sb.append("""(?:[^a-zA-Z0-9_.\-%]|$)""")
                '.', '+', '?', '(', ')', '[', ']', '{', '}', '\\', '$', '|' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
            i++
        }
        if (endAnchored) sb.append("$")

        return try {
            PatternRule(
                rawPattern = pattern,
                regex = Regex(sb.toString(), RegexOption.IGNORE_CASE),
                substring = null,
                options = options
            )
        } catch (_: Exception) {
            null
        }
    }

    fun shouldBlock(input: FilterMatchInput): Boolean {
        val reqUri = parseUriSafe(input.requestUrl) ?: return false
        val scheme = reqUri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false

        val reqHost = reqUri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        val pageHost = input.pageUrl?.let { parseUriSafe(it)?.host?.lowercase(Locale.ROOT)?.trimEnd('.') }
        val isThirdParty = pageHost != null && !isSameSite(reqHost, pageHost)
        val lowerUrl = input.requestUrl.lowercase(Locale.ROOT)
        val pathAndQuery = buildPathAndQuery(reqUri).lowercase(Locale.ROOT)

        // 1. Check whitelist exceptions first
        if (matchesDomainMap(exceptionDomainRules, reqHost, pathAndQuery, pageHost, isThirdParty, input.resourceKind)) {
            return false
        }
        if (matchesPatternList(exceptionPatternRules, input.requestUrl, lowerUrl, pageHost, isThirdParty, input.resourceKind)) {
            return false
        }

        // 2. Check blocking domain trie/map (O(labels))
        if (matchesDomainMap(blockDomainRules, reqHost, pathAndQuery, pageHost, isThirdParty, input.resourceKind)) {
            return true
        }

        // 3. Check blocking substring / wildcard / regex patterns
        return matchesPatternList(blockPatternRules, input.requestUrl, lowerUrl, pageHost, isThirdParty, input.resourceKind)
    }

    private fun matchesDomainMap(
        map: Map<String, List<DomainRule>>,
        reqHost: String,
        pathAndQuery: String,
        pageHost: String?,
        isThirdParty: Boolean,
        kind: ResourceKind
    ): Boolean {
        var cursor = reqHost
        while (cursor.isNotEmpty()) {
            val rules = map[cursor]
            if (rules != null) {
                for (rule in rules) {
                    if (rule.pathPrefix != null && !pathAndQuery.startsWith(rule.pathPrefix)) {
                        continue
                    }
                    if (optionsMatch(rule.options, pageHost, isThirdParty, kind)) {
                        return true
                    }
                }
            }
            val dot = cursor.indexOf('.')
            if (dot < 0) break
            cursor = cursor.substring(dot + 1)
        }
        return false
    }

    private fun matchesPatternList(
        rules: List<PatternRule>,
        rawUrl: String,
        lowerUrl: String,
        pageHost: String?,
        isThirdParty: Boolean,
        kind: ResourceKind
    ): Boolean {
        for (rule in rules) {
            if (!optionsMatch(rule.options, pageHost, isThirdParty, kind)) continue
            if (rule.substring != null) {
                if (lowerUrl.contains(rule.substring)) return true
            } else if (rule.regex != null) {
                if (rule.regex.containsMatchIn(rawUrl)) return true
            }
        }
        return false
    }

    private fun optionsMatch(
        opts: RuleOptions,
        pageHost: String?,
        isThirdParty: Boolean,
        kind: ResourceKind
    ): Boolean {
        if (opts.thirdPartyOnly != null && opts.thirdPartyOnly != isThirdParty) {
            // For POPUP checks where pageHost is present, respect third-party constraint
            return false
        }
        if (opts.allowedKinds != null && kind !in opts.allowedKinds) {
            return false
        }
        if (opts.excludeDomains.isNotEmpty() && pageHost != null) {
            if (opts.excludeDomains.any { domainMatchesHost(pageHost, it) }) return false
        }
        if (opts.includeDomains.isNotEmpty()) {
            if (pageHost == null) return false
            if (opts.includeDomains.none { domainMatchesHost(pageHost, it) }) return false
        }
        return true
    }

    companion object {
        private val TWO_PART_TLDS = setOf(
            "co.uk", "org.uk", "ac.uk", "com.au", "co.jp", "co.kr",
            "com.br", "com.mx", "co.in", "com.tr", "co.za"
        )

        fun registrableDomain(host: String): String {
            val clean = host.lowercase(Locale.ROOT).trimEnd('.')
            val parts = clean.split('.')
            if (parts.size <= 2) return clean
            val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
            return if (lastTwo in TWO_PART_TLDS && parts.size >= 3) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                lastTwo
            }
        }

        fun isSameSite(hostA: String, hostB: String): Boolean {
            if (hostA == hostB) return true
            return registrableDomain(hostA) == registrableDomain(hostB)
        }

        fun domainMatchesHost(host: String, domain: String): Boolean {
            return host == domain || host.endsWith(".$domain")
        }

        fun parseUriSafe(url: String): URI? {
            return try {
                URI(url.trim())
            } catch (_: Exception) {
                try {
                    val encoded = url.trim().replace(" ", "%20")
                    URI(encoded)
                } catch (_: Exception) {
                    null
                }
            }
        }

        private fun buildPathAndQuery(uri: URI): String {
            val p = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val q = uri.rawQuery
            return if (q.isNullOrEmpty()) p else "$p?$q"
        }
    }
}

/**
 * Android WebView integration around FilterRuleEngine with per-site whitelist persistence,
 * resource-type inference, synthetic safe WebResourceResponse generation, and popup shield.
 */
class AdBlockEngine(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    @Volatile
    private var engine: FilterRuleEngine = FilterRuleEngine()

    private val disabledHosts = ConcurrentHashMap.newKeySet<String>()
    private val pageBlockedCount = AtomicInteger(0)
    private val sessionBlockedCount = AtomicInteger(0)

    val blockedOnCurrentPage: Int
        get() = pageBlockedCount.get()

    val blockedInSession: Int
        get() = sessionBlockedCount.get()

    val cosmeticSelectors: List<String>
        get() = engine.cosmeticSelectors

    init {
        val savedDisabled = prefs.getStringSet(PREF_DISABLED_HOSTS, emptySet()) ?: emptySet()
        disabledHosts.addAll(savedDisabled.map { it.lowercase(Locale.ROOT) })
        reloadBundledAndCachedRules()
    }

    fun reloadBundledAndCachedRules() {
        val nextEngine = FilterRuleEngine()
        try {
            context.assets.open("filters/ublock_core_filters.txt").bufferedReader().useLines { lines ->
                nextEngine.loadRules(lines)
            }
        } catch (_: Exception) {
            // Asset missing only in stripped test environments
        }

        val cachedFile = File(File(context.filesDir, "filters"), "remote_easylist_cache.txt")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                cachedFile.bufferedReader().useLines { lines ->
                    nextEngine.loadRules(lines)
                }
            } catch (_: Exception) {
            }
        }
        engine = nextEngine
    }

    /**
     * Optional background update of Peter Lowe / EasyList lightweight host rules.
     */
    fun refreshRemoteFiltersAsync(executor: java.util.concurrent.Executor) {
        val cacheDir = File(context.filesDir, "filters").apply { mkdirs() }
        val cacheFile = File(cacheDir, "remote_easylist_cache.txt")
        val now = System.currentTimeMillis()
        if (cacheFile.exists() && now - cacheFile.lastModified() < 24L * 3600L * 1000L) {
            return
        }
        executor.execute {
            try {
                val conn = (URL("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "StreamSync-Shield/1.0")
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    if (text.length > 512) {
                        cacheFile.writeText(text)
                        reloadBundledAndCachedRules()
                    }
                }
            } catch (_: Exception) {
                // Bundled rules remain active offline
            }
        }
    }

    fun resetPageCounter() {
        pageBlockedCount.set(0)
    }

    fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return FilterRuleEngine.parseUriSafe(url)?.host?.lowercase(Locale.ROOT)?.trimEnd('.')
    }

    fun isShieldEnabledForUrl(pageUrl: String?): Boolean {
        val host = extractHost(pageUrl) ?: return true
        val reg = FilterRuleEngine.registrableDomain(host)
        return host !in disabledHosts && reg !in disabledHosts
    }

    fun toggleShieldForUrl(pageUrl: String?): Boolean {
        val host = extractHost(pageUrl) ?: return true
        val reg = FilterRuleEngine.registrableDomain(host)
        val currentlyEnabled = isShieldEnabledForUrl(pageUrl)
        if (currentlyEnabled) {
            disabledHosts.add(reg)
        } else {
            disabledHosts.remove(host)
            disabledHosts.remove(reg)
        }
        prefs.edit().putStringSet(PREF_DISABLED_HOSTS, HashSet(disabledHosts)).apply()
        return !currentlyEnabled
    }

    fun interceptRequest(
        request: WebResourceRequest,
        currentPageUrl: String?
    ): WebResourceResponse? {
        if (!isShieldEnabledForUrl(currentPageUrl)) return null
        val reqUrl = request.url?.toString() ?: return null
        val kind = inferResourceKind(request)
        if (kind == ResourceKind.MAIN_FRAME) return null

        val matched = engine.shouldBlock(
            FilterMatchInput(
                requestUrl = reqUrl,
                pageUrl = currentPageUrl,
                resourceKind = kind
            )
        )
        if (!matched) return null

        incrementBlocked()
        return buildSafeBlockedResponse(kind)
    }

    fun shouldBlockNavigationOrPopup(
        targetUrl: String?,
        currentPageUrl: String?,
        isPopupWindow: Boolean
    ): Boolean {
        if (!isShieldEnabledForUrl(currentPageUrl)) return false
        if (targetUrl.isNullOrBlank() || targetUrl == "about:blank") {
            if (isPopupWindow) {
                incrementBlocked()
                return true
            }
            return false
        }

        val lower = targetUrl.lowercase(Locale.ROOT)
        // Block non-HTTP app-store / rogue intent hijacks triggered inside streaming pages
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            incrementBlocked()
            return true
        }

        val kind = if (isPopupWindow) ResourceKind.POPUP else ResourceKind.SUBDOCUMENT
        if (engine.shouldBlock(FilterMatchInput(targetUrl, currentPageUrl, kind)) ||
            engine.shouldBlock(FilterMatchInput(targetUrl, currentPageUrl, ResourceKind.OTHER))
        ) {
            incrementBlocked()
            return true
        }

        // Block cross-site popup windows by default on streaming sites (classic popunder behavior)
        if (isPopupWindow && currentPageUrl != null) {
            val targetHost = extractHost(targetUrl)
            val pageHost = extractHost(currentPageUrl)
            if (targetHost != null && pageHost != null && !FilterRuleEngine.isSameSite(targetHost, pageHost)) {
                incrementBlocked()
                return true
            }
        }

        return false
    }

    fun recordJsBlockedPopup() {
        incrementBlocked()
    }

    private fun incrementBlocked() {
        pageBlockedCount.incrementAndGet()
        sessionBlockedCount.incrementAndGet()
    }

    private fun inferResourceKind(request: WebResourceRequest): ResourceKind {
        if (request.isForMainFrame) return ResourceKind.MAIN_FRAME
        val headers = request.requestHeaders ?: emptyMap()
        val secDest = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value?.lowercase(Locale.ROOT)
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value?.lowercase(Locale.ROOT).orEmpty()
        val path = request.url?.path?.lowercase(Locale.ROOT).orEmpty()

        return when {
            secDest == "script" || path.endsWith(".js") -> ResourceKind.SCRIPT
            secDest == "image" || accept.startsWith("image/") ||
                path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".gif") || path.endsWith(".webp") -> ResourceKind.IMAGE
            secDest == "style" || accept.contains("text/css") || path.endsWith(".css") -> ResourceKind.STYLESHEET
            secDest == "iframe" || secDest == "frame" || secDest == "document" -> ResourceKind.SUBDOCUMENT
            secDest == "video" || secDest == "audio" ||
                accept.startsWith("video/") || accept.startsWith("audio/") ||
                path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".ts") -> ResourceKind.MEDIA
            secDest == "empty" || headers.keys.any { it.equals("X-Requested-With", ignoreCase = true) } -> ResourceKind.XHR
            else -> ResourceKind.OTHER
        }
    }

    private fun buildSafeBlockedResponse(kind: ResourceKind): WebResourceResponse {
        val emptyHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store"
        )
        return when (kind) {
            ResourceKind.SCRIPT -> WebResourceResponse(
                "application/javascript",
                "UTF-8",
                200,
                "OK",
                emptyHeaders,
                ByteArrayInputStream("/* blocked by StreamSync Shield */".toByteArray(Charsets.UTF_8))
            )
            ResourceKind.STYLESHEET -> WebResourceResponse(
                "text/css",
                "UTF-8",
                200,
                "OK",
                emptyHeaders,
                ByteArrayInputStream("".toByteArray(Charsets.UTF_8))
            )
            ResourceKind.IMAGE -> WebResourceResponse(
                "image/gif",
                null,
                200,
                "OK",
                emptyHeaders,
                ByteArrayInputStream(TRANSPARENT_GIF_1X1)
            )
            ResourceKind.SUBDOCUMENT -> WebResourceResponse(
                "text/html",
                "UTF-8",
                200,
                "OK",
                emptyHeaders,
                ByteArrayInputStream("<!DOCTYPE html><html><body></body></html>".toByteArray(Charsets.UTF_8))
            )
            else -> WebResourceResponse(
                "text/plain",
                "UTF-8",
                204,
                "No Content",
                emptyHeaders,
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    companion object {
        private const val PREF_DISABLED_HOSTS = "shield_disabled_hosts"

        private val TRANSPARENT_GIF_1X1 = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
            0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02,
            0x02, 0x44, 0x01, 0x00, 0x3B
        )
    }
}
