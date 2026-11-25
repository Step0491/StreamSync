package com.streamsync.app.extractor

import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Coordinates multi-signal stream discovery from:
 * 1. WebViewClient.shouldInterceptRequest (capturing Referer, Origin, User-Agent, Cookie)
 * 2. Injected JS fetch / XMLHttpRequest / HTMLMediaElement observer (catching extensionless manifests)
 * 3. Background manifest & byte-range container probing via ManifestParser
 */
class StreamSniffer(
    private val onStreamsUpdated: (List<DetectedStream>) -> Unit,
    private val onPopupBlockedByJs: () -> Unit
) {
    private val streamsByKey = ConcurrentHashMap<String, DetectedStream>()
    private val childOverrides = ConcurrentHashMap<String, DetectedStream>()
    private val inspectedKeys = ConcurrentHashMap.newKeySet<String>()
    private val probeExecutor = Executors.newFixedThreadPool(3)

    @Volatile
    var activePageUrl: String = ""

    @Volatile
    var activePageTitle: String = ""

    val jsBridge = JsBridge()

    private val candidateUrlRegex = Regex(
        """(\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|flac)(\?|#|$)|mime=(video|audio)|mpegurl|dash\+xml|/manifest|/playlist\.m3u8)""",
        RegexOption.IGNORE_CASE
    )

    fun clearForNewTopLevelPage(newUrl: String) {
        activePageUrl = newUrl
        streamsByKey.clear()
        childOverrides.clear()
        inspectedKeys.clear()
        notifyUpdated()
    }

    fun getSortedStreams(): List<DetectedStream> {
        return streamsByKey.values.sortedWith(
            compareBy<DetectedStream> { it.isDrmProtected }
                .thenBy {
                    when (it.kind) {
                        StreamKind.COMBINED -> 0
                        StreamKind.VIDEO_ONLY -> 1
                        StreamKind.AUDIO_ONLY -> 2
                    }
                }
                .thenByDescending { it.isMasterPlaylist }
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.bitrateKbps ?: 0 }
                .thenBy { it.detectedAtMs }
        )
    }

    fun inspectWebRequest(request: WebResourceRequest, pageUrl: String?, pageTitle: String?) {
        val rawUrl = request.url?.toString() ?: return
        if (ManifestParser.isSegmentOrNoiseUrl(rawUrl)) return

        val reqHeaders = request.requestHeaders ?: emptyMap()
        val accept = reqHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value.orEmpty()
        val secDest = reqHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }?.value.orEmpty()

        val looksLikeMedia = candidateUrlRegex.containsMatchIn(rawUrl) ||
            secDest.equals("video", ignoreCase = true) ||
            secDest.equals("audio", ignoreCase = true) ||
            accept.startsWith("video/", ignoreCase = true) ||
            accept.startsWith("audio/", ignoreCase = true) ||
            accept.contains("mpegurl", ignoreCase = true) ||
            accept.contains("dash+xml", ignoreCase = true)

        if (!looksLikeMedia) return

        val capturedHeaders = buildPlaybackHeaders(rawUrl, pageUrl ?: activePageUrl, reqHeaders)
        submitCandidate(
            rawUrl = rawUrl,
            contentTypeHint = "",
            headers = capturedHeaders,
            pageTitle = pageTitle ?: activePageTitle
        )
    }

    fun submitCandidate(
        rawUrl: String,
        contentTypeHint: String,
        headers: Map<String, String>,
        pageTitle: String?
    ) {
        if (ManifestParser.isSegmentOrNoiseUrl(rawUrl)) return
        val cleanUrl = ManifestParser.stripRangeQueryParams(rawUrl)
        val normKey = ManifestParser.normalizeUrlForKey(cleanUrl)

        // If a Master playlist already classified this exact child variant/audio URL, apply the authoritative entry
        val override = childOverrides[normKey]
        if (override != null) {
            streamsByKey[normKey] = override.copy(
                requestHeaders = mergeHeaders(override.requestHeaders, headers)
            )
            notifyUpdated()
            return
        }

        // Prevent duplicate deep network probes for the same normalized URL
        if (!inspectedKeys.add(normKey)) {
            val existing = streamsByKey[normKey]
            if (existing != null && headers.isNotEmpty() && existing.requestHeaders.size < headers.size) {
                streamsByKey[normKey] = existing.copy(
                    requestHeaders = mergeHeaders(existing.requestHeaders, headers)
                )
            }
            return
        }

        // Provide immediate optimistic classification for instant responsiveness while deep probe runs
        val quickExt = ManifestParser.guessContainerExt(cleanUrl, contentTypeHint)
        val lowerUrl = cleanUrl.lowercase(Locale.ROOT)
        val lowerCt = contentTypeHint.lowercase(Locale.ROOT)
        val isLikelyHls = quickExt == "m3u8" || lowerCt.contains("mpegurl") || lowerUrl.contains(".m3u8")
        val isLikelyDash = quickExt == "mpd" || lowerCt.contains("dash+xml") || lowerUrl.contains(".mpd")

        if (!isLikelyHls && !isLikelyDash) {
            val quickHint = ManifestParser.classifyByUrlAndMimeHints(cleanUrl, lowerUrl, lowerCt)
            val quickLabel = when (quickHint.kind) {
                StreamKind.AUDIO_ONLY -> "Audio Track (${quickExt.uppercase(Locale.ROOT)})"
                StreamKind.VIDEO_ONLY -> "Video-Only Track (${quickExt.uppercase(Locale.ROOT)})"
                StreamKind.COMBINED -> "Video Stream (${quickExt.uppercase(Locale.ROOT)})"
            }
            streamsByKey.putIfAbsent(
                normKey,
                DetectedStream(
                    id = normKey,
                    url = cleanUrl,
                    normalizedUrl = normKey,
                    kind = quickHint.kind,
                    container = quickExt,
                    label = quickLabel,
                    pageTitle = pageTitle,
                    requestHeaders = headers
                )
            )
            notifyUpdated()
        }

        // Deep asynchronous manifest / container header probe
        probeExecutor.execute {
            probeRemoteStream(
                cleanUrl = cleanUrl,
                normKey = normKey,
                contentTypeHint = contentTypeHint,
                headers = headers,
                pageTitle = pageTitle
            )
        }
    }

    private fun probeRemoteStream(
        cleanUrl: String,
        normKey: String,
        contentTypeHint: String,
        headers: Map<String, String>,
        pageTitle: String?
    ) {
        var conn: HttpURLConnection? = null
        try {
            val quickExt = ManifestParser.guessContainerExt(cleanUrl, contentTypeHint)
            val expectManifest = quickExt == "m3u8" || quickExt == "mpd" ||
                contentTypeHint.contains("mpegurl", ignoreCase = true) ||
                contentTypeHint.contains("dash+xml", ignoreCase = true) ||
                cleanUrl.contains("manifest", ignoreCase = true)

            conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 7_000
                readTimeout = 8_000
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                if (!expectManifest) {
                    setRequestProperty("Range", "bytes=0-65535")
                }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                // Keep optimistic entry if HLS/DASH fallback is obvious
                if (quickExt == "m3u8" || quickExt == "mpd") {
                    streamsByKey.putIfAbsent(
                        normKey,
                        DetectedStream(
                            id = normKey,
                            url = cleanUrl,
                            normalizedUrl = normKey,
                            kind = StreamKind.COMBINED,
                            container = quickExt,
                            label = if (quickExt == "m3u8") "HLS Stream (.m3u8)" else "DASH Stream (.mpd)",
                            isHls = quickExt == "m3u8",
                            isDash = quickExt == "mpd",
                            pageTitle = pageTitle,
                            requestHeaders = headers
                        )
                    )
                    notifyUpdated()
                }
                return
            }

            val respCt = (conn.contentType ?: contentTypeHint).lowercase(Locale.ROOT)
            val totalSize = extractTotalLength(conn)

            // Reject non-media HTML/JSON/image responses captured from ambiguous URLs
            if (respCt.startsWith("text/html") || respCt.startsWith("image/") || respCt.startsWith("text/css")) {
                streamsByKey.remove(normKey)
                notifyUpdated()
                return
            }

            val maxReadBytes = if (expectManifest || respCt.contains("mpegurl") || respCt.contains("dash+xml") || respCt.contains("xml")) {
                512 * 1024
            } else {
                64 * 1024
            }
            val headBytes = readUpTo(conn, maxReadBytes)
            val textSample = String(
                headBytes,
                0,
                minOf(headBytes.size, 512 * 1024),
                StandardCharsets.UTF_8
            )

            // 1. HLS Manifest (#EXTM3U)
            if (textSample.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
                // Re-check if parent Master already classified this child playlist
                val existingOverride = childOverrides[normKey]
                if (existingOverride != null) {
                    streamsByKey[normKey] = existingOverride.copy(
                        requestHeaders = mergeHeaders(existingOverride.requestHeaders, headers)
                    )
                    notifyUpdated()
                    return
                }

                val parsed = ManifestParser.parseHlsManifest(cleanUrl, textSample, headers, pageTitle)
                if (parsed != null) {
                    for ((childKey, childStream) in parsed.childOverrides) {
                        childOverrides[childKey] = childStream
                        // Retroactively upgrade any already-captured child .m3u8!
                        if (streamsByKey.containsKey(childKey)) {
                            streamsByKey[childKey] = childStream
                        }
                    }
                    for (s in parsed.streams) {
                        val overrideForS = childOverrides[s.normalizedUrl]
                        streamsByKey[s.normalizedUrl] = overrideForS ?: s
                    }
                    notifyUpdated()
                    return
                }
            }

            // 2. DASH Manifest (<MPD)
            if (respCt.contains("dash+xml") || textSample.contains("<MPD", ignoreCase = true)) {
                val parsed = ManifestParser.parseDashManifest(cleanUrl, textSample, headers, pageTitle)
                if (parsed != null) {
                    for (s in parsed.streams) {
                        streamsByKey[s.normalizedUrl] = s
                    }
                    notifyUpdated()
                    return
                }
            }

            // 3. Binary Media Stream (MP4 / WebM / Audio)
            // Filter out tiny UI click sounds / 1-second ad beacons (< 120 KB) when Content-Length is known
            if (totalSize != null && totalSize in 1..120_000L) {
                streamsByKey.remove(normKey)
                notifyUpdated()
                return
            }

            val ext = ManifestParser.guessContainerExt(cleanUrl, respCt)
            val probe = ManifestParser.inspectContainerBytes(headBytes, respCt, cleanUrl)
            val codecLabel = listOfNotNull(
                probe.vcodec?.let { DetectedStream.friendlyCodec(it) },
                probe.acodec?.let { DetectedStream.friendlyCodec(it) }
            ).joinToString("+").takeIf { it.isNotEmpty() }

            val richLabel = when (probe.kind) {
                StreamKind.COMBINED -> "Video + Audio (${ext.uppercase(Locale.ROOT)}${codecLabel?.let { " • $it" } ?: ""})"
                StreamKind.VIDEO_ONLY -> "Video-Only (${ext.uppercase(Locale.ROOT)}${codecLabel?.let { " • $it" } ?: ""})"
                StreamKind.AUDIO_ONLY -> "Audio-Only (${ext.uppercase(Locale.ROOT)}${codecLabel?.let { " • $it" } ?: ""})"
            }

            streamsByKey[normKey] = DetectedStream(
                id = normKey,
                url = cleanUrl,
                normalizedUrl = normKey,
                kind = probe.kind,
                container = ext,
                label = richLabel,
                vcodec = probe.vcodec,
                acodec = probe.acodec,
                contentLength = totalSize,
                isDrmProtected = probe.isDrmProtected,
                pageTitle = pageTitle,
                requestHeaders = headers
            )
            notifyUpdated()
        } catch (_: Exception) {
            // Keep optimistic entry if probe timed out or CORS/TLS quirk occurred
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractTotalLength(conn: HttpURLConnection): Long? {
        val contentRange = conn.getHeaderField("Content-Range")
        if (!contentRange.isNullOrBlank() && contentRange.contains('/')) {
            val totalStr = contentRange.substringAfter('/').trim()
            val parsed = totalStr.toLongOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        val len = conn.contentLengthLong
        return if (len > 0) len else null
    }

    private fun readUpTo(conn: HttpURLConnection, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16_384))
        val buf = ByteArray(8_192)
        var remaining = maxBytes
        conn.inputStream.use { input ->
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        return out.toByteArray()
    }

    private fun buildPlaybackHeaders(
        streamUrl: String,
        pageUrl: String,
        reqHeaders: Map<String, String>
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in reqHeaders) {
            when (k.lowercase(Locale.ROOT)) {
                "referer", "origin", "user-agent", "authorization" -> {
                    if (v.isNotBlank()) out[k] = v
                }
            }
        }
        if (out.keys.none { it.equals("Referer", ignoreCase = true) } && pageUrl.startsWith("http")) {
            out["Referer"] = pageUrl
        }
        try {
            val cookieMgr = CookieManager.getInstance()
            val streamCookies = cookieMgr.getCookie(streamUrl) ?: cookieMgr.getCookie(pageUrl)
            if (!streamCookies.isNullOrBlank()) {
                out["Cookie"] = streamCookies
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun mergeHeaders(
        base: Map<String, String>,
        extra: Map<String, String>
    ): Map<String, String> {
        if (extra.isEmpty()) return base
        if (base.isEmpty()) return extra
        val merged = LinkedHashMap(base)
        for ((k, v) in extra) {
            if (v.isNotBlank()) merged[k] = v
        }
        return merged
    }

    private fun notifyUpdated() {
        onStreamsUpdated(getSortedStreams())
    }

    fun shutdown() {
        probeExecutor.shutdownNow()
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onMediaCandidate(
            url: String?,
            contentType: String?,
            pageUrl: String?,
            pageTitle: String?,
            @Suppress("UNUSED_PARAMETER") sourceTag: String?
        ) {
            val raw = url?.trim() ?: return
            if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
                return
            }
            val effectivePage = pageUrl?.takeIf { it.isNotBlank() } ?: activePageUrl
            val headers = buildPlaybackHeaders(raw, effectivePage, emptyMap())
            submitCandidate(
                rawUrl = raw,
                contentTypeHint = contentType.orEmpty(),
                headers = headers,
                pageTitle = pageTitle?.takeIf { it.isNotBlank() } ?: activePageTitle
            )
        }

        @JavascriptInterface
        fun onBlockedPopup(@Suppress("UNUSED_PARAMETER") blockedUrl: String?) {
            onPopupBlockedByJs()
        }
    }
}
