package com.streamsync.app

import com.streamsync.app.extractor.DetectedStream
import com.streamsync.app.extractor.ManifestParser
import com.streamsync.app.extractor.StreamKind
import java.util.Locale

/**
 * Lightweight synchronous URL classifier retained as a fast stateless helper,
 * backed by the upgraded ManifestParser heuristics.
 */
object MediaDetector {

    fun classifyQuickUrl(url: String, contentType: String = ""): DetectedStream? {
        if (ManifestParser.isSegmentOrNoiseUrl(url)) return null
        val cleanUrl = ManifestParser.stripRangeQueryParams(url)
        val normKey = ManifestParser.normalizeUrlForKey(cleanUrl)
        val ext = ManifestParser.guessContainerExt(cleanUrl, contentType)
        val lowerUrl = cleanUrl.lowercase(Locale.ROOT)
        val lowerCt = contentType.lowercase(Locale.ROOT)

        if (ext == "m3u8" || lowerCt.contains("mpegurl")) {
            return DetectedStream(
                id = normKey,
                url = cleanUrl,
                normalizedUrl = normKey,
                kind = StreamKind.COMBINED,
                container = "m3u8",
                label = "HLS Stream (.m3u8)",
                isHls = true
            )
        }
        if (ext == "mpd" || lowerCt.contains("dash+xml")) {
            return DetectedStream(
                id = normKey,
                url = cleanUrl,
                normalizedUrl = normKey,
                kind = StreamKind.COMBINED,
                container = "mpd",
                label = "DASH Stream (.mpd)",
                isDash = true
            )
        }

        val hasMediaSignal = Regex("""\.(mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|wav|flac)(\?|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(cleanUrl) ||
            lowerCt.startsWith("video/") ||
            lowerCt.startsWith("audio/") ||
            lowerUrl.contains("mime=video") ||
            lowerUrl.contains("mime=audio")

        if (!hasMediaSignal) return null

        val probe = ManifestParser.classifyByUrlAndMimeHints(cleanUrl, lowerUrl, lowerCt)
        val label = when (probe.kind) {
            StreamKind.AUDIO_ONLY -> "Audio Track (${ext.uppercase(Locale.ROOT)})"
            StreamKind.VIDEO_ONLY -> "Video-Only Track (${ext.uppercase(Locale.ROOT)})"
            StreamKind.COMBINED -> "Video (${ext.uppercase(Locale.ROOT)})"
        }
        return DetectedStream(
            id = normKey,
            url = cleanUrl,
            normalizedUrl = normKey,
            kind = probe.kind,
            container = ext,
            label = label
        )
    }
}
