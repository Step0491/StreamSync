package com.streamsync.app

enum class StreamKind {
    COMBINED,
    VIDEO,
    AUDIO
}

data class DetectedStream(
    val url: String,
    val kind: StreamKind,
    val label: String
)

object MediaDetector {
    private val segmentRegex = Regex("""\.(ts|m4s|cmfv|cmfa|vtt|srt|key)(\?|$)""", RegexOption.IGNORE_CASE)
    private val hlsRegex = Regex("""\.m3u8(\?|$)""", RegexOption.IGNORE_CASE)
    private val dashRegex = Regex("""\.mpd(\?|$)""", RegexOption.IGNORE_CASE)
    private val videoRegex = Regex("""\.(mp4|webm|m4v|mov)(\?|$)""", RegexOption.IGNORE_CASE)
    private val audioRegex = Regex("""\.(m4a|mp3|aac|ogg|opus|wav|flac)(\?|$)""", RegexOption.IGNORE_CASE)
    private val videoHintRegex = Regex("""(video|vcodec|mime=video)""", RegexOption.IGNORE_CASE)
    private val audioHintRegex = Regex("""(audio|acodec|mime=audio)""", RegexOption.IGNORE_CASE)
    private val drmRegex = Regex("""(widevine|playready|fairplay|contentprotection)""", RegexOption.IGNORE_CASE)

    fun classify(url: String): DetectedStream? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (segmentRegex.containsMatchIn(url) || drmRegex.containsMatchIn(url)) return null

        if (hlsRegex.containsMatchIn(url)) {
            return DetectedStream(url, StreamKind.COMBINED, "HLS Stream (.m3u8)")
        }
        if (dashRegex.containsMatchIn(url)) {
            return DetectedStream(url, StreamKind.COMBINED, "DASH Stream (.mpd)")
        }

        val isAudio = audioRegex.containsMatchIn(url) || audioHintRegex.containsMatchIn(url)
        val isVideo = videoRegex.containsMatchIn(url) || videoHintRegex.containsMatchIn(url)

        if (isAudio && !isVideo) {
            val ext = audioRegex.find(url)?.groupValues?.getOrNull(1)?.uppercase() ?: "AUDIO"
            return DetectedStream(url, StreamKind.AUDIO, "Audio Track ($ext)")
        }

        if (isVideo) {
            val ext = videoRegex.find(url)?.groupValues?.getOrNull(1)?.uppercase() ?: "VIDEO"
            val isVideoOnly = videoHintRegex.containsMatchIn(url) && !url.contains("audio", ignoreCase = true)
            return DetectedStream(
                url = url,
                kind = if (isVideoOnly) StreamKind.VIDEO else StreamKind.COMBINED,
                label = if (isVideoOnly) "Video Track ($ext)" else "Video ($ext)"
            )
        }

        return null
    }
}
