package com.streamsync.app.extractor

import android.media.MediaMetadataRetriever
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
        """(\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|flac)(\?|#|$)|/videoplayback\b|mime=(video|audio)|mpegurl|dash\+xml|/manifest|/playlist\.m3u8|/hls/|/dash/)""",
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
        // Skip YouTube proprietary UMP/SABR chunks (not directly playable; extracted via ytInitialPlayerResponse)
        if (Regex("""[?&]ump=1\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)) return

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

            val sentRange = !expectManifest
            conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 7_000
                readTimeout = 8_000
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                if (sentRange) {
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
            val totalSize = extractReliableTotalLength(conn, sentRange)

            val hasExplicitMediaExt = Regex(
                """\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|flac)(\?|#|$)|mime=(video|audio)|/videoplayback\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(cleanUrl)

            // Reject HTML/image responses ONLY for ambiguous extensionless URLs.
            // If URL has explicit media extension (e.g. .m3u8/.mp4), keep optimistic entry because
            // background HttpURLConnection probes can hit Cloudflare/anti-bot HTML challenges while WebView plays fine!
            if (respCt.startsWith("text/html") || respCt.startsWith("image/") || respCt.startsWith("text/css")) {
                if (!hasExplicitMediaExt) {
                    streamsByKey.remove(normKey)
                    notifyUpdated()
                }
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
                    // If this is an HLS Master Playlist, probe the first child variant once to obtain
                    // exact total duration (#EXTINF sum) & LIVE status, then propagate to all variants!
                    var masterDuration: Double? = null
                    var masterIsLive = false
                    val firstChildVariant = parsed.streams.firstOrNull { !it.isMasterPlaylist && it.isHls }
                    if (firstChildVariant != null && parsed.streams.any { it.isMasterPlaylist }) {
                        val durPair = probeHlsChildDuration(firstChildVariant.url, headers)
                        if (durPair != null) {
                            masterDuration = durPair.first
                            masterIsLive = durPair.second
                        }
                    }

                    for ((childKey, childStream) in parsed.childOverrides) {
                        val enriched = childStream.copy(
                            durationSec = childStream.durationSec ?: masterDuration,
                            isLive = childStream.isLive || masterIsLive
                        )
                        childOverrides[childKey] = enriched
                        if (streamsByKey.containsKey(childKey)) {
                            streamsByKey[childKey] = enriched
                        }
                    }
                    for (s in parsed.streams) {
                        val base = childOverrides[s.normalizedUrl] ?: s
                        streamsByKey[s.normalizedUrl] = base.copy(
                            durationSec = base.durationSec ?: masterDuration,
                            isLive = base.isLive || masterIsLive
                        )
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

            var width = probe.width
            var height = probe.height
            var durationSec = probe.durationSec
            var bitrateKbps: Int? = null
            var effectiveKind = probe.kind

            // If moov/mvhd wasn't in the first 64KB, use Android's native MediaMetadataRetriever
            // (performs HTTP Range seeks to read exact duration, dimensions, bitrate, and track types!)
            if (durationSec == null || (effectiveKind != StreamKind.AUDIO_ONLY && height == null)) {
                val mmrInfo = probeWithMediaMetadataRetriever(cleanUrl, headers)
                if (mmrInfo != null) {
                    if (durationSec == null) durationSec = mmrInfo.durationSec
                    if (width == null) width = mmrInfo.width
                    if (height == null) height = mmrInfo.height
                    if (bitrateKbps == null) bitrateKbps = mmrInfo.bitrateKbps
                    if (mmrInfo.hasVideo != null && mmrInfo.hasAudio != null) {
                        effectiveKind = when {
                            mmrInfo.hasVideo && mmrInfo.hasAudio -> StreamKind.COMBINED
                            mmrInfo.hasVideo -> StreamKind.VIDEO_ONLY
                            mmrInfo.hasAudio -> StreamKind.AUDIO_ONLY
                            else -> effectiveKind
                        }
                    }
                }
            }

            if (bitrateKbps == null && totalSize != null && totalSize > 0 && durationSec != null && durationSec > 1.0) {
                bitrateKbps = ((totalSize * 8.0) / (durationSec * 1000.0)).toInt().takeIf { it > 0 }
            }

            val codecLabel = listOfNotNull(
                probe.vcodec?.let { DetectedStream.friendlyCodec(it) },
                probe.acodec?.let { DetectedStream.friendlyCodec(it) }
            ).joinToString("+").takeIf { it.isNotEmpty() }
            val resPrefix = height?.let { "${it}p • " } ?: ""

            val richLabel = when (effectiveKind) {
                StreamKind.COMBINED -> "${resPrefix}Video + Audio (${ext.uppercase(Locale.ROOT)}${codecLabel?.let { " • $it" } ?: ""})"
                StreamKind.VIDEO_ONLY -> "${resPrefix}Video-Only (${ext.uppercase(Locale.ROOT)}${codecLabel?.let { " • $it" } ?: ""})"
                StreamKind.AUDIO_ONLY -> "Audio-Only (${ext.uppercase(Locale.ROOT)}${codecLabel?.let { " • $it" } ?: ""})"
            }

            streamsByKey[normKey] = DetectedStream(
                id = normKey,
                url = cleanUrl,
                normalizedUrl = normKey,
                kind = effectiveKind,
                container = ext,
                label = richLabel,
                width = width,
                height = height,
                durationSec = durationSec,
                vcodec = probe.vcodec,
                acodec = probe.acodec,
                bitrateKbps = bitrateKbps,
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

    private data class NativeMediaMeta(
        val durationSec: Double?,
        val width: Int?,
        val height: Int?,
        val bitrateKbps: Int?,
        val hasVideo: Boolean?,
        val hasAudio: Boolean?
    )

    private fun probeWithMediaMetadataRetriever(url: String, headers: Map<String, String>): NativeMediaMeta? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(url, HashMap(headers))
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val hasV = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)?.equals("yes", ignoreCase = true)
            val hasA = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.equals("yes", ignoreCase = true)
            NativeMediaMeta(
                durationSec = durMs?.takeIf { it > 0 }?.let { it / 1000.0 },
                width = w?.takeIf { it > 0 },
                height = h?.takeIf { it > 0 },
                bitrateKbps = br?.takeIf { it > 0 }?.let { (it / 1000L).toInt() },
                hasVideo = hasV,
                hasAudio = hasA
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun probeHlsChildDuration(childUrl: String, headers: Map<String, String>): Pair<Double?, Boolean>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(childUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 5_000
                readTimeout = 6_000
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = readUpTo(conn, 256 * 1024)
            val text = String(bytes, StandardCharsets.UTF_8)
            val parsed = ManifestParser.parseHlsManifest(childUrl, text, headers, null)
            val first = parsed?.streams?.firstOrNull() ?: return null
            first.durationSec to first.isLive
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractReliableTotalLength(conn: HttpURLConnection, sentRange: Boolean): Long? {
        val contentRange = conn.getHeaderField("Content-Range")
        if (!contentRange.isNullOrBlank() && contentRange.contains('/')) {
            val totalStr = contentRange.substringAfter('/').trim()
            val parsed = totalStr.toLongOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        // When we sent Range: bytes=0-65535 and server omits Content-Range, Content-Length is just the 64KB chunk!
        if (sentRange) return null
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

        @JavascriptInterface
        fun onStructuredStream(
            url: String?,
            kindStr: String?,
            container: String?,
            label: String?,
            width: Int,
            height: Int,
            fps: Double,
            durationSec: Double,
            vcodec: String?,
            acodec: String?,
            bitrateKbps: Int,
            contentLength: Long,
            language: String?,
            isHls: Boolean,
            isDash: Boolean,
            pageUrl: String?,
            pageTitle: String?
        ) {
            val raw = url?.trim() ?: return
            if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
                return
            }
            val cleanUrl = ManifestParser.stripRangeQueryParams(raw)
            val normKey = ManifestParser.normalizeUrlForKey(cleanUrl)
            val kind = when (kindStr?.uppercase(Locale.ROOT)) {
                "VIDEO_ONLY" -> StreamKind.VIDEO_ONLY
                "AUDIO_ONLY" -> StreamKind.AUDIO_ONLY
                else -> StreamKind.COMBINED
            }
            val effectivePage = pageUrl?.takeIf { it.isNotBlank() } ?: activePageUrl
            val headers = buildPlaybackHeaders(cleanUrl, effectivePage, emptyMap())
            streamsByKey[normKey] = DetectedStream(
                id = normKey,
                url = cleanUrl,
                normalizedUrl = normKey,
                kind = kind,
                container = container?.takeIf { it.isNotBlank() } ?: ManifestParser.guessContainerExt(cleanUrl, null),
                label = label?.takeIf { it.isNotBlank() } ?: "Detected Stream",
                width = width.takeIf { it > 0 },
                height = height.takeIf { it > 0 },
                fps = fps.takeIf { it > 0.0 },
                durationSec = durationSec.takeIf { it > 0.5 && !it.isNaN() && !it.isInfinite() },
                vcodec = vcodec?.takeIf { it.isNotBlank() },
                acodec = acodec?.takeIf { it.isNotBlank() },
                bitrateKbps = bitrateKbps.takeIf { it > 0 },
                contentLength = contentLength.takeIf { it > 0L },
                language = language?.takeIf { it.isNotBlank() },
                isHls = isHls,
                isDash = isDash,
                isMasterPlaylist = isHls || isDash,
                pageTitle = pageTitle?.takeIf { it.isNotBlank() } ?: activePageTitle,
                requestHeaders = headers
            )
            inspectedKeys.add(normKey)
            notifyUpdated()
        }
    }
}
