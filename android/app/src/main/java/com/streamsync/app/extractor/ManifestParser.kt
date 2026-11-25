package com.streamsync.app.extractor

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class HlsParseResult(
    val streams: List<DetectedStream>,
    val childOverrides: Map<String, DetectedStream>,
    val isDrmProtected: Boolean
)

data class DashParseResult(
    val streams: List<DetectedStream>,
    val isDrmProtected: Boolean
)

data class ContainerProbeResult(
    val kind: StreamKind,
    val width: Int? = null,
    val height: Int? = null,
    val durationSec: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val isDrmProtected: Boolean = false
)

/**
 * Pure-JVM manifest & container header analyzer for HLS (.m3u8), DASH (.mpd),
 * ISO-BMFF (.mp4/.m4a/.mov), and Matroska/WebM (.webm/.mkv).
 */
object ManifestParser {

    private val TRANSIENT_RANGE_PARAMS = setOf(
        "range", "bytestart", "byteend", "rn", "rbuf", "ump", "srfvp"
    )

    private val HLS_ATTR_REGEX = Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))""")
    private val SEGMENT_EXT_REGEX = Regex(
        """\.(ts|m4s|cmfv|cmfa|vtt|srt|webvtt|key|jpg|jpeg|png|gif|webp|svg|css|js|woff2?|ttf|ico)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )
    private val AUDIO_SEG_REGEX = Regex("""\.(aac|m4a|mp3|ac3|ec3|eac3|ogg|opus|flac|cmfa)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val VIDEO_ONLY_SEG_REGEX = Regex("""\.(cmfv|m4v|264|265|hevc)(\?|#|$)""", RegexOption.IGNORE_CASE)

    // Well-known YouTube / GoogleVideo / CDN adaptive format itags
    private val VIDEO_ONLY_ITAGS = setOf(
        "133", "134", "135", "136", "137", "138", "160", "242", "243", "244", "247", "248",
        "264", "266", "271", "278", "298", "299", "302", "303", "308", "313", "315",
        "394", "395", "396", "397", "398", "399", "400", "401"
    )
    private val AUDIO_ONLY_ITAGS = setOf(
        "139", "140", "141", "171", "172", "249", "250", "251", "256", "258", "325", "328"
    )

    fun isSegmentOrNoiseUrl(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return true
        }
        if (SEGMENT_EXT_REGEX.containsMatchIn(url)) return true
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains("/ptracking") || lower.contains("/log_event") ||
            lower.contains("/stats/qoe") || lower.contains("/ stats/") ||
            lower.contains("videostats") || lower.contains("/heartbeat")
        ) {
            return true
        }
        return false
    }

    /**
     * Strips transient HTTP byte-range parameters so chunked range requests collapse into
     * one clean full-stream URL while preserving authentication/signature query params.
     */
    fun stripRangeQueryParams(rawUrl: String): String {
        val qIdx = rawUrl.indexOf('?')
        if (qIdx < 0) return rawUrl.substringBefore('#')
        val base = rawUrl.substring(0, qIdx)
        val query = rawUrl.substring(qIdx + 1).substringBefore('#')
        if (query.isEmpty()) return base

        val kept = ArrayList<String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase(Locale.ROOT)
            if (key !in TRANSIENT_RANGE_PARAMS) {
                kept.add(pair)
            }
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    fun normalizeUrlForKey(rawUrl: String): String {
        return stripRangeQueryParams(rawUrl).trim()
    }

    fun resolveUrl(baseUrl: String, relativeOrAbs: String): String {
        val trimmed = relativeOrAbs.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            trimmed
        }
    }

    fun splitCodecs(codecsRaw: String?): Pair<String?, String?> {
        if (codecsRaw.isNullOrBlank()) return null to null
        var vcodec: String? = null
        var acodec: String? = null
        for (token in codecsRaw.split(',')) {
            val c = token.trim()
            val lower = c.lowercase(Locale.ROOT)
            when {
                lower.startsWith("avc1") || lower.startsWith("avc3") ||
                    lower.startsWith("hev1") || lower.startsWith("hvc1") ||
                    lower.startsWith("vp09") || lower.startsWith("vp9") ||
                    lower.startsWith("vp08") || lower.startsWith("vp8") ||
                    lower.startsWith("av01") || lower.startsWith("dvh1") ||
                    lower.startsWith("mp4v") -> {
                    if (vcodec == null) vcodec = c
                }
                lower.startsWith("mp4a") || lower.startsWith("ac-3") ||
                    lower.startsWith("ec-3") || lower.startsWith("opus") ||
                    lower.startsWith("vorbis") || lower.startsWith("flac") ||
                    lower.startsWith("alac") || lower == "mp3" -> {
                    if (acodec == null) acodec = c
                }
            }
        }
        return vcodec to acodec
    }

    /**
     * Parses an HLS (#EXTM3U) Master or Media playlist.
     * Accurately separates Master Combined, decoupled Video-Only variants, and Audio-Only renditions.
     */
    fun parseHlsManifest(
        manifestUrl: String,
        content: String,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String? = null
    ): HlsParseResult? {
        val trimmed = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("#EXTM3U")) return null

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-MEDIA:") }

        var sawDrm = false
        for (line in lines) {
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                val upper = line.uppercase(Locale.ROOT)
                if (upper.contains("SAMPLE-AES") || upper.contains("WIDEVINE") ||
                    upper.contains("PLAYREADY") || upper.contains("FAIRPLAY") ||
                    upper.contains("COM.APPLE.STREAMINGKEYDELIVERY")
                ) {
                    sawDrm = true
                }
            }
        }

        val results = ArrayList<DetectedStream>()
        val childOverrides = HashMap<String, DetectedStream>()
        val cleanMasterUrl = stripRangeQueryParams(manifestUrl)
        val masterKey = normalizeUrlForKey(cleanMasterUrl)

        if (isMaster) {
            // 1. Collect #EXT-X-MEDIA:TYPE=AUDIO groups
            data class AudioGroupEntry(
                val groupId: String,
                val name: String?,
                val lang: String?,
                val uri: String?,
                val channels: String?
            )

            val audioGroups = HashMap<String, MutableList<AudioGroupEntry>>()
            for (line in lines) {
                if (!line.startsWith("#EXT-X-MEDIA:")) continue
                val attrs = parseHlsAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) continue
                val groupId = attrs["GROUP-ID"] ?: "audio"
                val uriAttr = attrs["URI"]?.takeIf { it.isNotBlank() }?.let { resolveUrl(cleanMasterUrl, it) }
                val entry = AudioGroupEntry(
                    groupId = groupId,
                    name = attrs["NAME"],
                    lang = attrs["LANGUAGE"],
                    uri = uriAttr,
                    channels = attrs["CHANNELS"]
                )
                audioGroups.getOrPut(groupId) { ArrayList() }.add(entry)

                if (uriAttr != null) {
                    val cleanAudioUrl = stripRangeQueryParams(uriAttr)
                    val audioNorm = normalizeUrlForKey(cleanAudioUrl)
                    val langDisplay = listOfNotNull(
                        entry.name?.takeIf { it.isNotBlank() },
                        entry.lang?.takeIf { it.isNotBlank() && !it.equals(entry.name, ignoreCase = true) }
                    ).joinToString(" - ").ifEmpty { "Audio Track" }
                    val chSuffix = entry.channels?.let { " (${it}ch)" } ?: ""
                    val audioStream = DetectedStream(
                        id = audioNorm,
                        url = cleanAudioUrl,
                        normalizedUrl = audioNorm,
                        kind = StreamKind.AUDIO_ONLY,
                        container = "m3u8",
                        label = "$langDisplay$chSuffix • HLS Audio",
                        acodec = "mp4a",
                        language = entry.lang ?: entry.name,
                        isHls = true,
                        isMasterPlaylist = false,
                        isDrmProtected = sawDrm,
                        parentManifestUrl = cleanMasterUrl,
                        pageTitle = pageTitle,
                        requestHeaders = headers
                    )
                    results.add(audioStream)
                    childOverrides[audioNorm] = audioStream
                }
            }

            // 2. Collect #EXT-X-STREAM-INF variants
            var maxHeight = 0
            var maxWidth = 0
            var maxBandwidthKbps = 0
            var masterVCodec: String? = null
            var masterACodec: String? = null

            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val attrs = parseHlsAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                    // Find next non-comment URI line
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) j++
                    if (j < lines.size) {
                        val variantUrl = stripRangeQueryParams(resolveUrl(cleanMasterUrl, lines[j]))
                        val variantNorm = normalizeUrlForKey(variantUrl)
                        val bwBits = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull()
                        val bwKbps = bwBits?.let { (it / 1000L).toInt() }
                        val res = attrs["RESOLUTION"]
                        val width = res?.substringBefore('x')?.toIntOrNull()
                        val height = res?.substringAfter('x')?.toIntOrNull()
                        val fps = attrs["FRAME-RATE"]?.toDoubleOrNull()
                        val (vcodec, acodecFromInf) = splitCodecs(attrs["CODECS"])
                        val audioGroupId = attrs["AUDIO"]
                        val linkedAudioGroup = audioGroupId?.let { audioGroups[it] }
                        val hasDetachedAudioUri = linkedAudioGroup?.any { it.uri != null } == true

                        if ((height ?: 0) > maxHeight) {
                            maxHeight = height ?: 0
                            maxWidth = width ?: 0
                        }
                        if ((bwKbps ?: 0) > maxBandwidthKbps) maxBandwidthKbps = bwKbps ?: 0
                        if (masterVCodec == null && vcodec != null) masterVCodec = vcodec
                        if (masterACodec == null && acodecFromInf != null) masterACodec = acodecFromInf

                        // Crucial classification: if audio is in a detached #EXT-X-MEDIA URI (or codecs clearly specify video-only without muxed audio),
                        // this variant .m3u8 URL on its own has NO audio!
                        val isVideoOnlyVariant = hasDetachedAudioUri ||
                            (vcodec != null && acodecFromInf == null && audioGroups.values.flatten().any { it.uri != null }) ||
                            (vcodec != null && acodecFromInf == null && attrs["CODECS"] != null)

                        val effectiveKind = if (isVideoOnlyVariant) StreamKind.VIDEO_ONLY else StreamKind.COMBINED
                        val effectiveACodec = if (isVideoOnlyVariant) null else acodecFromInf
                        val resLabel = when {
                            height != null && fps != null && fps >= 48 -> "${height}p${fps.toInt()}"
                            height != null -> "${height}p"
                            bwKbps != null -> "$bwKbps kbps"
                            else -> "Variant"
                        }
                        val kindSuffix = if (isVideoOnlyVariant) "HLS Video-Only" else "HLS Variant"

                        val variantStream = DetectedStream(
                            id = variantNorm,
                            url = variantUrl,
                            normalizedUrl = variantNorm,
                            kind = effectiveKind,
                            container = "m3u8",
                            label = "$resLabel • $kindSuffix",
                            width = width,
                            height = height,
                            fps = fps,
                            vcodec = vcodec,
                            acodec = effectiveACodec,
                            bitrateKbps = bwKbps,
                            isHls = true,
                            isMasterPlaylist = false,
                            isDrmProtected = sawDrm,
                            parentManifestUrl = cleanMasterUrl,
                            pageTitle = pageTitle,
                            requestHeaders = headers
                        )
                        results.add(variantStream)
                        childOverrides[variantNorm] = variantStream
                        i = j
                    }
                }
                i++
            }

            if (masterACodec == null && audioGroups.isNotEmpty()) {
                masterACodec = "mp4a"
            }

            val masterResText = if (maxHeight > 0) "Adaptive (up to ${maxHeight}p)" else "Adaptive Master"
            val masterStream = DetectedStream(
                id = masterKey,
                url = cleanMasterUrl,
                normalizedUrl = masterKey,
                kind = StreamKind.COMBINED,
                container = "m3u8",
                label = "$masterResText • HLS Master",
                width = maxWidth.takeIf { it > 0 },
                height = maxHeight.takeIf { it > 0 },
                vcodec = masterVCodec,
                acodec = masterACodec,
                bitrateKbps = maxBandwidthKbps.takeIf { it > 0 },
                isHls = true,
                isMasterPlaylist = true,
                isDrmProtected = sawDrm,
                pageTitle = pageTitle,
                requestHeaders = headers
            )
            results.add(0, masterStream)
            return HlsParseResult(
                streams = results.distinctBy { it.normalizedUrl },
                childOverrides = childOverrides,
                isDrmProtected = sawDrm
            )
        }

        // Media Playlist (#EXTINF segments) — sum durations just like Video DownloadHelper!
        val segmentLines = lines.filter { !it.startsWith("#") }.take(8)
        val mapLine = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
        val mapUri = mapLine?.let { parseHlsAttributes(it.removePrefix("#EXT-X-MAP:"))["URI"] } ?: ""

        var totalDuration = 0.0
        var extinfCount = 0
        for (line in lines) {
            if (line.startsWith("#EXTINF:")) {
                val durStr = line.removePrefix("#EXTINF:").substringBefore(',').trim()
                val d = durStr.toDoubleOrNull()
                if (d != null && d > 0.0) {
                    totalDuration += d
                    extinfCount++
                }
            }
        }
        val hasEndList = lines.any { it.startsWith("#EXT-X-ENDLIST") }
        val isVodPlaylist = hasEndList || lines.any { it.contains("PLAYLIST-TYPE:VOD", ignoreCase = true) }
        val isLivePlaylist = extinfCount > 0 && !isVodPlaylist

        val lowerUrl = cleanMasterUrl.lowercase(Locale.ROOT)
        val allSegSamples = (segmentLines + listOf(mapUri)).filter { it.isNotBlank() }

        val looksAudioOnly = allSegSamples.any { AUDIO_SEG_REGEX.containsMatchIn(it) } ||
            Regex("""(/audio/|[_.-]audio[_.-]|mime=audio|type=audio)""", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl)
        val looksVideoOnly = !looksAudioOnly && (
            allSegSamples.any { VIDEO_ONLY_SEG_REGEX.containsMatchIn(it) } ||
                (Regex("""(/video/|[_.-]video[_.-]|mime=video)""", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl) &&
                    !lowerUrl.contains("audio"))
            )

        val kind = when {
            looksAudioOnly -> StreamKind.AUDIO_ONLY
            looksVideoOnly -> StreamKind.VIDEO_ONLY
            else -> StreamKind.COMBINED
        }
        val label = when (kind) {
            StreamKind.AUDIO_ONLY -> "HLS Audio Track (.m3u8)"
            StreamKind.VIDEO_ONLY -> "HLS Video-Only Track (.m3u8)"
            StreamKind.COMBINED -> "HLS Stream (.m3u8)"
        }

        val single = DetectedStream(
            id = masterKey,
            url = cleanMasterUrl,
            normalizedUrl = masterKey,
            kind = kind,
            container = "m3u8",
            label = label,
            durationSec = totalDuration.takeIf { it > 0.5 && !isLivePlaylist },
            isLive = isLivePlaylist,
            isHls = true,
            isMasterPlaylist = false,
            isDrmProtected = sawDrm,
            pageTitle = pageTitle,
            requestHeaders = headers
        )
        return HlsParseResult(
            streams = listOf(single),
            childOverrides = emptyMap(),
            isDrmProtected = sawDrm
        )
    }

    private fun parseHlsAttributes(attrString: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (match in HLS_ATTR_REGEX.findAll(attrString)) {
            val key = match.groupValues[1].uppercase(Locale.ROOT)
            val quoted = match.groups[2]?.value
            val unquoted = match.groups[3]?.value
            map[key] = (quoted ?: unquoted ?: "").trim()
        }
        return map
    }

    /**
     * Parses an MPEG-DASH (.mpd) XML manifest safely.
     */
    fun parseDashManifest(
        manifestUrl: String,
        xmlContent: String,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String? = null
    ): DashParseResult? {
        val trimmed = xmlContent.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.contains("<MPD", ignoreCase = true)) return null

        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (_: Exception) {
                }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(trimmed)))
        } catch (_: Exception) {
            return null
        }

        val cpNodes = doc.getElementsByTagName("ContentProtection")
        var sawDrm = false
        for (idx in 0 until cpNodes.length) {
            val el = cpNodes.item(idx) as? Element ?: continue
            val scheme = el.getAttribute("schemeIdUri").lowercase(Locale.ROOT)
            // Ignore pure mp4protection box info if no DRM system UUID is present; flag real DRM UUIDs
            if (scheme.contains("edef8ba9") || scheme.contains("9a04f079") ||
                scheme.contains("94ce86fb") || scheme.contains("widevine") ||
                scheme.contains("playready") || scheme.contains("fairplay")
            ) {
                sawDrm = true
            }
        }
        if (!sawDrm && trimmed.lowercase(Locale.ROOT).let {
                it.contains("widevine") || it.contains("playready")
            }
        ) {
            sawDrm = true
        }

        val mpdRoot = doc.documentElement
        val mpdType = mpdRoot?.getAttribute("type")?.lowercase(Locale.ROOT).orEmpty()
        val isDashLive = mpdType == "dynamic"
        val mpdDurationSec = parseIso8601Duration(mpdRoot?.getAttribute("mediaPresentationDuration"))

        val cleanMpdUrl = stripRangeQueryParams(manifestUrl)
        val mpdKey = normalizeUrlForKey(cleanMpdUrl)
        val streams = ArrayList<DetectedStream>()

        var hasVideo = false
        var hasAudio = false
        var maxHeight = 0
        var maxWidth = 0
        var maxBwKbps = 0
        var bestVCodec: String? = null
        var bestACodec: String? = null
        val audioLangs = LinkedHashSet<String>()

        val adaptationSets = doc.getElementsByTagName("AdaptationSet")
        for (aIdx in 0 until adaptationSets.length) {
            val adapt = adaptationSets.item(aIdx) as? Element ?: continue
            val adaptMime = adapt.getAttribute("mimeType").lowercase(Locale.ROOT)
            val adaptContent = adapt.getAttribute("contentType").lowercase(Locale.ROOT)
            val adaptLang = adapt.getAttribute("lang").takeIf { it.isNotBlank() }
            val adaptCodecs = adapt.getAttribute("codecs").takeIf { it.isNotBlank() }
            val hasSegmentTemplate = adapt.getElementsByTagName("SegmentTemplate").length > 0

            val reps = adapt.getElementsByTagName("Representation")
            for (rIdx in 0 until reps.length) {
                val rep = reps.item(rIdx) as? Element ?: continue
                val mime = (rep.getAttribute("mimeType").takeIf { it.isNotBlank() } ?: adaptMime).lowercase(Locale.ROOT)
                val codecs = rep.getAttribute("codecs").takeIf { it.isNotBlank() } ?: adaptCodecs
                val width = rep.getAttribute("width").toIntOrNull() ?: adapt.getAttribute("width").toIntOrNull()
                val height = rep.getAttribute("height").toIntOrNull() ?: adapt.getAttribute("height").toIntOrNull()
                val bwKbps = rep.getAttribute("bandwidth").toLongOrNull()?.let { (it / 1000L).toInt() }
                val fpsRaw = rep.getAttribute("frameRate").takeIf { it.isNotBlank() }
                    ?: adapt.getAttribute("frameRate").takeIf { it.isNotBlank() }
                val fps = parseDashFrameRate(fpsRaw)

                val isVideo = adaptContent == "video" || mime.startsWith("video/") || (height != null && height > 0)
                val isAudio = !isVideo && (adaptContent == "audio" || mime.startsWith("audio/"))

                val (vc, ac) = splitCodecs(codecs)
                if (isVideo) {
                    hasVideo = true
                    if ((height ?: 0) > maxHeight) {
                        maxHeight = height ?: 0
                        maxWidth = width ?: 0
                    }
                    if ((bwKbps ?: 0) > maxBwKbps) maxBwKbps = bwKbps ?: 0
                    if (bestVCodec == null) bestVCodec = vc ?: codecs
                } else if (isAudio) {
                    hasAudio = true
                    if (bestACodec == null) bestACodec = ac ?: codecs
                    if (adaptLang != null) audioLangs.add(adaptLang)
                }

                // If Representation exposes a standalone single-file <BaseURL> (no SegmentTemplate),
                // expose it for manual Video+Audio track pairing as well!
                val repHasTemplate = hasSegmentTemplate || rep.getElementsByTagName("SegmentTemplate").length > 0
                if (!repHasTemplate) {
                    val baseNodes = rep.getElementsByTagName("BaseURL")
                    if (baseNodes.length == 1) {
                        val baseText = baseNodes.item(0)?.textContent?.trim().orEmpty()
                        if (baseText.isNotEmpty() && !baseText.endsWith("/")) {
                            val resolved = stripRangeQueryParams(resolveUrl(cleanMpdUrl, baseText))
                            val norm = normalizeUrlForKey(resolved)
                            val ext = guessContainerExt(resolved, mime)
                            if (isVideo) {
                                streams.add(
                                    DetectedStream(
                                        id = norm,
                                        url = resolved,
                                        normalizedUrl = norm,
                                        kind = StreamKind.VIDEO_ONLY,
                                        container = ext,
                                        label = "${height?.let { "${it}p" } ?: "Video"} • DASH Video-Only",
                                        width = width,
                                        height = height,
                                        fps = fps,
                                        durationSec = mpdDurationSec,
                                        isLive = isDashLive,
                                        vcodec = vc ?: codecs,
                                        bitrateKbps = bwKbps,
                                        isDash = false,
                                        isDrmProtected = sawDrm,
                                        parentManifestUrl = cleanMpdUrl,
                                        pageTitle = pageTitle,
                                        requestHeaders = headers
                                    )
                                )
                            } else if (isAudio) {
                                streams.add(
                                    DetectedStream(
                                        id = norm,
                                        url = resolved,
                                        normalizedUrl = norm,
                                        kind = StreamKind.AUDIO_ONLY,
                                        container = ext,
                                        label = "${adaptLang?.uppercase(Locale.ROOT) ?: "Audio"}${bwKbps?.let { " ($it kbps)" } ?: ""} • DASH Audio",
                                        durationSec = mpdDurationSec,
                                        isLive = isDashLive,
                                        acodec = ac ?: codecs,
                                        bitrateKbps = bwKbps,
                                        language = adaptLang,
                                        isDash = false,
                                        isDrmProtected = sawDrm,
                                        parentManifestUrl = cleanMpdUrl,
                                        pageTitle = pageTitle,
                                        requestHeaders = headers
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        val mpdKind = when {
            hasVideo && hasAudio -> StreamKind.COMBINED
            hasVideo -> StreamKind.VIDEO_ONLY
            hasAudio -> StreamKind.AUDIO_ONLY
            else -> StreamKind.COMBINED
        }
        val resText = if (maxHeight > 0) "Adaptive (up to ${maxHeight}p)" else "Adaptive DASH"
        val masterDash = DetectedStream(
            id = mpdKey,
            url = cleanMpdUrl,
            normalizedUrl = mpdKey,
            kind = mpdKind,
            container = "mpd",
            label = "$resText • DASH (.mpd)",
            width = maxWidth.takeIf { it > 0 },
            height = maxHeight.takeIf { it > 0 },
            durationSec = mpdDurationSec,
            isLive = isDashLive,
            vcodec = bestVCodec,
            acodec = bestACodec,
            bitrateKbps = maxBwKbps.takeIf { it > 0 },
            language = audioLangs.takeIf { it.isNotEmpty() }?.joinToString("/"),
            isDash = true,
            isMasterPlaylist = true,
            isDrmProtected = sawDrm,
            pageTitle = pageTitle,
            requestHeaders = headers
        )
        streams.add(0, masterDash)

        return DashParseResult(
            streams = streams.distinctBy { it.normalizedUrl },
            isDrmProtected = sawDrm
        )
    }

    fun parseIso8601Duration(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$""", RegexOption.IGNORE_CASE)
            .find(raw.trim()) ?: return null
        val days = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val mins = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val secs = m.groupValues[4].toDoubleOrNull() ?: 0.0
        val total = days * 86400.0 + hours * 3600.0 + mins * 60.0 + secs
        return if (total > 0.0) total else null
    }

    private fun parseDashFrameRate(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        if (raw.contains('/')) {
            val num = raw.substringBefore('/').toDoubleOrNull() ?: return null
            val den = raw.substringAfter('/').toDoubleOrNull() ?: return null
            return if (den > 0) num / den else null
        }
        return raw.toDoubleOrNull()
    }

    /**
     * Inspects the initial bytes (e.g. first 64 KB) of an MP4/M4A/MOV or WebM/Matroska stream
     * plus URL/Content-Type hints to reliably separate Combined (Video+Audio) from Video-Only & Audio-Only.
     */
    fun inspectContainerBytes(
        headerBytes: ByteArray,
        contentType: String?,
        rawUrl: String
    ): ContainerProbeResult {
        val ct = contentType?.lowercase(Locale.ROOT).orEmpty()
        val lowerUrl = rawUrl.lowercase(Locale.ROOT)

        // 1. Check ISO-BMFF (MP4 / M4A / MOV) box markers if 'ftyp' or 'moov' is in headerBytes
        if (headerBytes.size >= 16 && (
                containsAsciiToken(headerBytes, "ftyp") || containsAsciiToken(headerBytes, "moov")
            )
        ) {
            val hasMoov = containsAsciiToken(headerBytes, "moov")
            val sawEncvOrEnca = containsAsciiToken(headerBytes, "encv") ||
                containsAsciiToken(headerBytes, "enca") ||
                containsAsciiToken(headerBytes, "pssh")

            if (hasMoov) {
                val handlers = findIsoHandlerTypes(headerBytes)
                val hasVide = "vide" in handlers
                val hasSoun = "soun" in handlers
                val vcodec = detectIsoVideoCodec(headerBytes)
                val acodec = detectIsoAudioCodec(headerBytes)
                val durSec = parseIsoMvhdDuration(headerBytes)
                val dims = parseIsoTkhdDimensions(headerBytes)

                if (hasVide && hasSoun) {
                    return ContainerProbeResult(StreamKind.COMBINED, dims?.first, dims?.second, durSec, vcodec, acodec, sawEncvOrEnca)
                }
                if (hasVide && !hasSoun) {
                    return ContainerProbeResult(StreamKind.VIDEO_ONLY, dims?.first, dims?.second, durSec, vcodec, null, sawEncvOrEnca)
                }
                if (hasSoun && !hasVide) {
                    return ContainerProbeResult(StreamKind.AUDIO_ONLY, null, null, durSec, null, acodec, sawEncvOrEnca)
                }
            }
        }

        // 2. Check WebM / Matroska EBML magic (0x1A 0x45 0xDF 0xA3)
        if (headerBytes.size >= 32 &&
            headerBytes[0] == 0x1A.toByte() &&
            headerBytes[1] == 0x45.toByte() &&
            headerBytes[2] == 0xDF.toByte() &&
            headerBytes[3] == 0xA3.toByte()
        ) {
            val hasWebmVideo = containsAsciiToken(headerBytes, "V_VP9") ||
                containsAsciiToken(headerBytes, "V_VP8") ||
                containsAsciiToken(headerBytes, "V_AV1") ||
                containsAsciiToken(headerBytes, "V_MPEG4")
            val hasWebmAudio = containsAsciiToken(headerBytes, "A_OPUS") ||
                containsAsciiToken(headerBytes, "A_VORBIS") ||
                containsAsciiToken(headerBytes, "A_AAC")
            val vcodec = when {
                containsAsciiToken(headerBytes, "V_AV1") -> "AV1"
                containsAsciiToken(headerBytes, "V_VP9") -> "VP9"
                containsAsciiToken(headerBytes, "V_VP8") -> "VP8"
                else -> null
            }
            val acodec = when {
                containsAsciiToken(headerBytes, "A_OPUS") -> "Opus"
                containsAsciiToken(headerBytes, "A_VORBIS") -> "Vorbis"
                else -> null
            }
            if (hasWebmVideo && hasWebmAudio) return ContainerProbeResult(StreamKind.COMBINED, vcodec = vcodec, acodec = acodec)
            if (hasWebmVideo) return ContainerProbeResult(StreamKind.VIDEO_ONLY, vcodec = vcodec, acodec = null)
            if (hasWebmAudio) return ContainerProbeResult(StreamKind.AUDIO_ONLY, vcodec = null, acodec = acodec)
        }

        // 3. Fallback to URL query/itag/mime hints & Content-Type
        return classifyByUrlAndMimeHints(rawUrl, lowerUrl, ct)
    }

    fun classifyByUrlAndMimeHints(
        rawUrl: String,
        lowerUrl: String = rawUrl.lowercase(Locale.ROOT),
        lowerContentType: String = ""
    ): ContainerProbeResult {
        val itag = Regex("""[?&]itag=(\d+)""").find(lowerUrl)?.groupValues?.getOrNull(1)
        if (itag != null) {
            if (itag in VIDEO_ONLY_ITAGS) return ContainerProbeResult(StreamKind.VIDEO_ONLY)
            if (itag in AUDIO_ONLY_ITAGS) return ContainerProbeResult(StreamKind.AUDIO_ONLY)
        }

        if (lowerContentType.startsWith("audio/") ||
            lowerUrl.contains("mime=audio") ||
            AUDIO_SEG_REGEX.containsMatchIn(lowerUrl) ||
            Regex("""\.(m4a|mp3|aac|ogg|opus|wav|flac)(\?|$)""").containsMatchIn(lowerUrl)
        ) {
            return ContainerProbeResult(StreamKind.AUDIO_ONLY)
        }

        val explicitVideoOnly = (
            (lowerUrl.contains("mime=video") && (itag != null || lowerUrl.contains("gir=yes") || lowerUrl.contains("aitags="))) ||
                Regex("""([/_.-]video[-_.]?only|vcodec=[^&]+&acodec=none|acodec=none)""").containsMatchIn(lowerUrl)
            )
        if (explicitVideoOnly) {
            return ContainerProbeResult(StreamKind.VIDEO_ONLY)
        }

        return ContainerProbeResult(StreamKind.COMBINED)
    }

    fun guessContainerExt(url: String, contentType: String?): String {
        val path = try {
            URI(stripRangeQueryParams(url)).path?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            url.substringBefore('?').lowercase(Locale.ROOT)
        }
        for (ext in listOf("m3u8", "mpd", "mp4", "webm", "m4v", "mov", "m4a", "mp3", "aac", "opus", "ogg", "flac", "wav")) {
            if (path.endsWith(".$ext")) return ext
        }
        val ct = contentType?.lowercase(Locale.ROOT).orEmpty()
        return when {
            ct.contains("mpegurl") -> "m3u8"
            ct.contains("dash+xml") -> "mpd"
            ct.contains("webm") -> "webm"
            ct.contains("audio/mp4") || ct.contains("audio/x-m4a") -> "m4a"
            ct.contains("audio/mpeg") -> "mp3"
            ct.contains("audio/ogg") || ct.contains("opus") -> "opus"
            ct.contains("audio/aac") -> "aac"
            ct.contains("video/mp4") || ct.contains("application/mp4") -> "mp4"
            else -> "mp4"
        }
    }

    /**
     * Scans ISO-BMFF 'hdlr' boxes: [size:4]['hdlr':4][version+flags:4][pre_defined:4][handler_type:4]
     * handler_type is 'vide' for video tracks and 'soun' for audio tracks.
     */
    private fun findIsoHandlerTypes(bytes: ByteArray): Set<String> {
        val found = HashSet<String>()
        val limit = bytes.size - 16
        var i = 4
        while (i <= limit) {
            if (bytes[i] == 'h'.code.toByte() &&
                bytes[i + 1] == 'd'.code.toByte() &&
                bytes[i + 2] == 'l'.code.toByte() &&
                bytes[i + 3] == 'r'.code.toByte()
            ) {
                val handlerOffset = i + 12
                if (handlerOffset + 4 <= bytes.size) {
                    val hType = String(bytes, handlerOffset, 4, StandardCharsets.US_ASCII)
                    found.add(hType)
                }
                i += 16
            } else {
                i++
            }
        }
        return found
    }

    private fun readUint32BE(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0L
        return ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
    }

    private fun readUint64BE(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return 0L
        return (readUint32BE(b, off) shl 32) or readUint32BE(b, off + 4)
    }

    /** Parses ISO-BMFF 'mvhd' box to extract exact duration in seconds. */
    private fun parseIsoMvhdDuration(bytes: ByteArray): Double? {
        val limit = bytes.size - 32
        var i = 4
        while (i <= limit) {
            if (bytes[i] == 'm'.code.toByte() &&
                bytes[i + 1] == 'v'.code.toByte() &&
                bytes[i + 2] == 'h'.code.toByte() &&
                bytes[i + 3] == 'd'.code.toByte()
            ) {
                val version = bytes[i + 4].toInt() and 0xFF
                if (version == 0 && i + 24 <= bytes.size) {
                    val timescale = readUint32BE(bytes, i + 16)
                    val duration = readUint32BE(bytes, i + 20)
                    if (timescale > 0 && duration > 0) return duration.toDouble() / timescale.toDouble()
                } else if (version == 1 && i + 36 <= bytes.size) {
                    val timescale = readUint32BE(bytes, i + 24)
                    val duration = readUint64BE(bytes, i + 28)
                    if (timescale > 0 && duration > 0) return duration.toDouble() / timescale.toDouble()
                }
                return null
            }
            i++
        }
        return null
    }

    /** Parses ISO-BMFF 'tkhd' boxes to extract max video track width & height (16.16 fixed-point). */
    private fun parseIsoTkhdDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var bestW = 0
        var bestH = 0
        val limit = bytes.size - 92
        var i = 4
        while (i <= limit) {
            if (bytes[i] == 't'.code.toByte() &&
                bytes[i + 1] == 'k'.code.toByte() &&
                bytes[i + 2] == 'h'.code.toByte() &&
                bytes[i + 3] == 'd'.code.toByte()
            ) {
                val version = bytes[i + 4].toInt() and 0xFF
                val dimOff = if (version == 1) i + 88 else i + 76
                if (dimOff + 8 <= bytes.size) {
                    val w = (readUint32BE(bytes, dimOff) shr 16).toInt()
                    val h = (readUint32BE(bytes, dimOff + 4) shr 16).toInt()
                    if (w in 120..7680 && h in 90..4320 && h > bestH) {
                        bestW = w
                        bestH = h
                    }
                }
                i += 80
            } else {
                i++
            }
        }
        return if (bestW > 0 && bestH > 0) bestW to bestH else null
    }

    private fun detectIsoVideoCodec(bytes: ByteArray): String? {
        for (fourcc in listOf("avc1", "avc3", "hev1", "hvc1", "av01", "vp09")) {
            if (containsAsciiToken(bytes, fourcc)) return fourcc
        }
        return null
    }

    private fun detectIsoAudioCodec(bytes: ByteArray): String? {
        for (fourcc in listOf("mp4a", "Opus", "ec-3", "ac-3", "fLaC", "alac")) {
            if (containsAsciiToken(bytes, fourcc)) return fourcc.lowercase(Locale.ROOT)
        }
        return null
    }

    private fun containsAsciiToken(haystack: ByteArray, token: String): Boolean {
        val needle = token.toByteArray(StandardCharsets.US_ASCII)
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val maxIdx = haystack.size - needle.size
        outer@ for (i in 0..maxIdx) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
