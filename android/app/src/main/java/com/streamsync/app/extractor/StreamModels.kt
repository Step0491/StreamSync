package com.streamsync.app.extractor

import org.json.JSONObject
import java.util.Locale

enum class StreamKind {
    COMBINED,
    VIDEO_ONLY,
    AUDIO_ONLY
}

data class DetectedStream(
    val id: String,
    val url: String,
    val normalizedUrl: String,
    val kind: StreamKind,
    val container: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val durationSec: Double? = null,
    val isLive: Boolean = false,
    val vcodec: String? = null,
    val acodec: String? = null,
    val bitrateKbps: Int? = null,
    val contentLength: Long? = null,
    val isEstimatedSize: Boolean = false,
    val language: String? = null,
    val isHls: Boolean = false,
    val isDash: Boolean = false,
    val isMasterPlaylist: Boolean = false,
    val isDrmProtected: Boolean = false,
    val parentManifestUrl: String? = null,
    val pageTitle: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val detectedAtMs: Long = System.currentTimeMillis()
) {
    fun resolutionBadge(): String? {
        if (width != null && width > 0 && height != null && height > 0) {
            val fpsSuffix = if (fps != null && fps >= 48.0) "@${fps.toInt()}fps" else ""
            return "${width}x${height} (${height}p$fpsSuffix)"
        }
        if (height != null && height > 0) {
            val fpsSuffix = if (fps != null && fps >= 48.0) "${fps.toInt()}" else ""
            return "${height}p$fpsSuffix"
        }
        return if (isMasterPlaylist) "Adaptive" else null
    }

    fun durationBadge(): String? {
        if (isLive) return "LIVE"
        val d = durationSec ?: return null
        if (d <= 0.5 || d.isNaN() || d.isInfinite()) return null
        val total = d.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun codecBadge(): String? {
        val v = vcodec?.let { friendlyCodec(it) }
        val a = acodec?.let { friendlyCodec(it) }
        return when {
            v != null && a != null -> "V:$v + A:$a"
            v != null -> "V:$v"
            a != null -> "A:$a"
            else -> null
        }
    }

    fun effectiveContentLength(): Pair<Long, Boolean>? {
        if (contentLength != null && contentLength > 0L) {
            return contentLength to isEstimatedSize
        }
        if (!isLive && bitrateKbps != null && bitrateKbps > 0 && durationSec != null && durationSec > 1.0) {
            val est = ((bitrateKbps.toDouble() * 1000.0 / 8.0) * durationSec).toLong()
            if (est > 0L) return est to true
        }
        return null
    }

    fun bitrateOrSizeBadge(): String? {
        val parts = ArrayList<String>(2)
        if (bitrateKbps != null && bitrateKbps > 0) {
            parts.add(
                if (bitrateKbps >= 1000) String.format(Locale.US, "%.1f Mbps", bitrateKbps / 1000.0)
                else "$bitrateKbps kbps"
            )
        }
        val sizePair = effectiveContentLength()
        if (sizePair != null) {
            val (bytes, estimated) = sizePair
            val prefix = if (estimated) "~" else ""
            val mb = bytes / (1024.0 * 1024.0)
            parts.add(
                if (mb >= 1024.0) String.format(Locale.US, "%s%.2f GB", prefix, mb / 1024.0)
                else if (mb >= 1.0) String.format(Locale.US, "%s%.1f MB", prefix, mb)
                else "$prefix${bytes / 1024} KB"
            )
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    fun metadataSubtitle(): String {
        val pills = ArrayList<String>()
        pills.add(container.uppercase(Locale.ROOT))
        resolutionBadge()?.let { pills.add(it) }
        durationBadge()?.let { pills.add("⏱ $it") }
        language?.takeIf { it.isNotBlank() }?.let { pills.add("Lang: $it") }
        codecBadge()?.let { pills.add(it) }
        bitrateOrSizeBadge()?.let { pills.add(it) }
        if (kind == StreamKind.VIDEO_ONLY) {
            pills.add("Video-Only (No Audio)")
        } else if (kind == StreamKind.AUDIO_ONLY) {
            pills.add("Audio-Only")
        }
        if (isDrmProtected) {
            pills.add("DRM Protected")
        }
        return pills.joinToString(" • ")
    }

    fun headersToJson(): JSONObject {
        val obj = JSONObject()
        for ((k, v) in requestHeaders) {
            if (k.isNotBlank() && v.isNotBlank()) {
                obj.put(k, v)
            }
        }
        return obj
    }

    companion object {
        fun friendlyCodec(raw: String): String {
            val lower = raw.lowercase(Locale.ROOT).trim()
            return when {
                lower.startsWith("avc1") || lower.startsWith("h264") || lower == "h.264" -> "H.264"
                lower.startsWith("hev1") || lower.startsWith("hvc1") || lower.startsWith("h265") -> "HEVC"
                lower.startsWith("av01") || lower == "av1" -> "AV1"
                lower.startsWith("vp09") || lower.startsWith("vp9") || lower == "v_vp9" -> "VP9"
                lower.startsWith("vp08") || lower.startsWith("vp8") || lower == "v_vp8" -> "VP8"
                lower.startsWith("mp4a") || lower == "aac" || lower == "a_aac" -> "AAC"
                lower.startsWith("opus") || lower == "a_opus" -> "Opus"
                lower.startsWith("vorbis") || lower == "a_vorbis" -> "Vorbis"
                lower.startsWith("ec-3") || lower == "eac3" -> "E-AC3"
                lower.startsWith("ac-3") || lower == "ac3" -> "AC3"
                lower.startsWith("mp3") || lower == "mp4a.69" || lower == "mp4a.6b" -> "MP3"
                lower.startsWith("flac") -> "FLAC"
                else -> raw.substringBefore('.').uppercase(Locale.ROOT)
            }
        }
    }
}
