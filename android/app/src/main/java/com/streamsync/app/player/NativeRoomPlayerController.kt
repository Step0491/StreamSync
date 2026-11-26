package com.streamsync.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Wraps AndroidX Media3 ExoPlayer to provide:
 * 1. Sample-accurate hardware-synced playback of 1 Video URL + 1 Audio URL via MergingMediaSource
 * 2. Custom HTTP header injection (Referer, Origin, User-Agent, Cookie) for hotlink/CORS-protected CDNs
 * 3. Bidirectional real-time watch-party room sync bridge with roomWebView (@JavascriptInterface)
 */
@OptIn(UnstableApi::class)
class NativeRoomPlayerController(
    private val context: Context,
    private val playerContainer: View,
    private val playerView: PlayerView,
    private val tvPlayerTitle: TextView,
    private val tvPlayerBadge: TextView,
    private val roomWebView: WebView,
    private val defaultUserAgent: String,
    private val onFullscreenToggle: (Boolean) -> Unit,
    private val onExtractSourceUrlRequested: (sourceUrl: String, wantPaired: Boolean, streamHintJson: String) -> Unit = { _, _, _ -> }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .build()

    private var suppressOutboundEvents = false
    private var pausedByRoomBarrier = false
    private var hasExternalPairedAudio = false
    private var appliedPairedAudioOverride = false
    private var isFullscreen = false
    private var hasActiveMedia = false
    private var hasReachedReadyOnce = false
    private var isRoomTabVisible = true

    val jsBridge = JsBridge()

    private val stateTicker = object : Runnable {
        override fun run() {
            if (hasActiveMedia) {
                pushSnapshotToWeb()
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    init {
        playerView.player = exoPlayer
        playerView.setShowSubtitleButton(true)
        playerView.setFullscreenButtonClickListener { full ->
            isFullscreen = full
            onFullscreenToggle(full)
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushSnapshotToWeb()
                if (suppressOutboundEvents || pausedByRoomBarrier) return
                // Distinguish genuine play/pause transitions while READY
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    emitEventToWeb(if (isPlaying) "play" else "pause")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                pushSnapshotToWeb()
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        // Only trigger room-wide buffering barrier AFTER initial load reached READY,
                        // so joining guests loading their first frame never pause the Admin!
                        if (hasActiveMedia && hasReachedReadyOnce && exoPlayer.playWhenReady && !pausedByRoomBarrier) {
                            emitEventToWeb("buffering_start")
                        }
                    }
                    Player.STATE_READY -> {
                        val firstReady = !hasReachedReadyOnce
                        hasReachedReadyOnce = true
                        emitEventToWeb("buffering_end")
                        if (firstReady) {
                            emitEventToWeb("ready")
                        }
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        emitEventToWeb("buffering_end")
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                emitEventToWeb("buffering_end")
                emitEventToWeb("playback_error")
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                pushSnapshotToWeb()
                if (suppressOutboundEvents) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    emitEventToWeb("seek")
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                pushSnapshotToWeb()
                if (suppressOutboundEvents) return
                emitEventToWeb("ratechange")
            }

            override fun onTracksChanged(tracks: Tracks) {
                // If user explicitly paired a Video stream (that happened to contain muxed fallback audio)
                // with an external Audio URL, prefer the merged external audio track group automatically.
                if (hasExternalPairedAudio && !appliedPairedAudioOverride) {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                    if (audioGroups.size > 1) {
                        val externalGroup = audioGroups.last()
                        appliedPairedAudioOverride = true
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(externalGroup.mediaTrackGroup, 0))
                            .build()
                    }
                }
            }
        })
    }

    fun setRoomTabVisibility(visible: Boolean) {
        isRoomTabVisible = visible
        updateContainerVisibility()
    }

    fun exitFullscreenIfNeeded(): Boolean {
        if (!isFullscreen) return false
        isFullscreen = false
        onFullscreenToggle(false)
        return true
    }

    private fun updateContainerVisibility() {
        playerContainer.visibility = if (isRoomTabVisible && hasActiveMedia) View.VISIBLE else View.GONE
    }

    fun loadNativePlayback(
        videoUrl: String,
        audioUrl: String?,
        isHls: Boolean,
        isDash: Boolean,
        title: String,
        label: String,
        headers: Map<String, String>,
        autoPlay: Boolean
    ) {
        if (videoUrl.isBlank()) return
        runWithSuppressedEvents {
            pausedByRoomBarrier = false
            hasActiveMedia = true
            hasReachedReadyOnce = false
            hasExternalPairedAudio = !audioUrl.isNullOrBlank()
            appliedPairedAudioOverride = false

            // Clear previous track overrides
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()

            val httpFactory = buildHttpFactory(headers)
            val videoSource = createMediaSource(videoUrl, isHls, isDash, httpFactory)

            val finalSource: MediaSource = if (!audioUrl.isNullOrBlank()) {
                val lowerAudio = audioUrl.lowercase(Locale.ROOT)
                val audioIsHls = lowerAudio.contains(".m3u8") || lowerAudio.contains("mpegurl")
                val audioIsDash = lowerAudio.contains(".mpd") || lowerAudio.contains("dash")
                val audioSource = createMediaSource(audioUrl, audioIsHls, audioIsDash, httpFactory)
                MergingMediaSource(true, true, videoSource, audioSource)
            } else {
                videoSource
            }

            tvPlayerTitle.text = title.ifBlank { "Untitled Stream" }
            tvPlayerBadge.text = if (!audioUrl.isNullOrBlank()) {
                "ExoPlayer • Paired Video + Audio"
            } else {
                "ExoPlayer • ${label.ifBlank { "Direct Stream" }}"
            }

            updateContainerVisibility()
            exoPlayer.setMediaSource(finalSource)
            exoPlayer.playbackParameters = PlaybackParameters(1.0f)
            exoPlayer.playWhenReady = autoPlay
            exoPlayer.prepare()
        }

        mainHandler.removeCallbacks(stateTicker)
        mainHandler.post(stateTicker)
    }

    fun applyRemoteSyncState(
        isPlaying: Boolean,
        positionSec: Double,
        canonicalRate: Double,
        effectiveRate: Double,
        seekThresholdSec: Double
    ) {
        if (!hasActiveMedia) return
        runWithSuppressedEvents {
            pausedByRoomBarrier = false
            val targetRate = effectiveRate.coerceIn(0.25, 4.0).toFloat()
            if (abs(exoPlayer.playbackParameters.speed - targetRate) > 0.008f) {
                exoPlayer.playbackParameters = PlaybackParameters(targetRate)
            }

            val currentSec = exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0
            if (positionSec >= 0.0 && abs(currentSec - positionSec) > seekThresholdSec) {
                exoPlayer.seekTo((positionSec * 1000.0).toLong())
            }

            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            if (exoPlayer.playWhenReady != isPlaying) {
                exoPlayer.playWhenReady = isPlaying
            }
            // Restore canonical rate marker for snapshot reporting if micro-nudging
            if (abs(canonicalRate - effectiveRate) < 0.001 &&
                abs(exoPlayer.playbackParameters.speed - canonicalRate.toFloat()) > 0.01f
            ) {
                exoPlayer.playbackParameters = PlaybackParameters(canonicalRate.toFloat())
            }
        }
        pushSnapshotToWeb()
    }

    fun setPausedForRoomBuffer(paused: Boolean, resumePositionSec: Double, resumeRate: Double) {
        if (!hasActiveMedia) return
        runWithSuppressedEvents {
            pausedByRoomBarrier = paused
            if (paused) {
                exoPlayer.playWhenReady = false
            } else {
                val currentSec = exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0
                if (resumePositionSec >= 0.0 && abs(currentSec - resumePositionSec) > 0.35) {
                    exoPlayer.seekTo((resumePositionSec * 1000.0).toLong())
                }
                exoPlayer.playbackParameters = PlaybackParameters(resumeRate.coerceIn(0.25, 4.0).toFloat())
                exoPlayer.playWhenReady = true
            }
        }
        pushSnapshotToWeb()
    }

    private fun buildHttpFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory {
        val cleanProps = HashMap<String, String>()
        var userAgent = defaultUserAgent
        for ((k, v) in headers) {
            if (k.isBlank() || v.isBlank()) continue
            if (k.equals("User-Agent", ignoreCase = true)) {
                userAgent = v
            } else {
                cleanProps[k] = v
            }
        }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(cleanProps)
    }

    private fun createMediaSource(
        url: String,
        isHlsHint: Boolean,
        isDashHint: Boolean,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): MediaSource {
        val lower = url.lowercase(Locale.ROOT)
        val item = MediaItem.fromUri(url)
        return when {
            isDashHint || lower.contains(".mpd") -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(item)
            isHlsHint || lower.contains(".m3u8") -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        }
    }

    private inline fun runWithSuppressedEvents(block: () -> Unit) {
        suppressOutboundEvents = true
        try {
            block()
        } finally {
            mainHandler.postDelayed({ suppressOutboundEvents = false }, 800L)
        }
    }

    private fun buildStateJson(eventType: String? = null): JSONObject {
        val posSec = exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0
        val durSec = if (exoPlayer.duration > 0 && exoPlayer.duration != C.TIME_UNSET) {
            exoPlayer.duration / 1000.0
        } else {
            0.0
        }
        return JSONObject().apply {
            if (eventType != null) put("event", eventType)
            put("active", hasActiveMedia)
            put("is_playing", exoPlayer.playWhenReady && !pausedByRoomBarrier && exoPlayer.playbackState != Player.STATE_ENDED)
            put("position", posSec)
            put("duration", durSec)
            put("playback_rate", exoPlayer.playbackParameters.speed.toDouble())
            put("buffering", exoPlayer.playbackState == Player.STATE_BUFFERING)
        }
    }

    private fun pushSnapshotToWeb() {
        val json = buildStateJson().toString()
        roomWebView.evaluateJavascript("window.__nativePlayerState = $json;", null)
    }

    private fun emitEventToWeb(eventType: String) {
        val json = buildStateJson(eventType).toString()
        roomWebView.evaluateJavascript(
            "if (window.onNativePlayerEvent) { window.onNativePlayerEvent($json); }",
            null
        )
    }

    fun release() {
        mainHandler.removeCallbacks(stateTicker)
        exoPlayer.release()
    }

    inner class JsBridge {
        @JavascriptInterface
        fun isAvailable(): Boolean = true

        @JavascriptInterface
        fun loadMedia(payloadJson: String) {
            mainHandler.post {
                try {
                    val obj = JSONObject(payloadJson)
                    val videoUrl = obj.optString("video_url", "")
                    val audioUrl = obj.optString("audio_url", "").takeIf { it.isNotBlank() }
                    val isHls = obj.optBoolean("is_hls", false)
                    val isDash = obj.optBoolean("is_dash", false)
                    val title = obj.optString("title", "Untitled Stream")
                    val label = obj.optString("label", "")
                    val autoPlay = obj.optBoolean("auto_play", false)

                    val headersMap = HashMap<String, String>()
                    val headersObj = obj.optJSONObject("headers")
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = headersObj.optString(k, "")
                            if (v.isNotBlank()) headersMap[k] = v
                        }
                    }

                    loadNativePlayback(
                        videoUrl = videoUrl,
                        audioUrl = audioUrl,
                        isHls = isHls,
                        isDash = isDash,
                        title = title,
                        label = label,
                        headers = headersMap,
                        autoPlay = autoPlay
                    )
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun applySync(
            isPlaying: Boolean,
            positionSec: Double,
            canonicalRate: Double,
            effectiveRate: Double,
            seekThresholdSec: Double
        ) {
            mainHandler.post {
                applyRemoteSyncState(
                    isPlaying = isPlaying,
                    positionSec = positionSec,
                    canonicalRate = canonicalRate,
                    effectiveRate = effectiveRate,
                    seekThresholdSec = seekThresholdSec
                )
            }
        }

        @JavascriptInterface
        fun setBufferingBarrier(paused: Boolean, positionSec: Double, playbackRate: Double) {
            mainHandler.post {
                setPausedForRoomBuffer(paused, positionSec, playbackRate)
            }
        }

        @JavascriptInterface
        fun stopAndHide() {
            mainHandler.post {
                hasActiveMedia = false
                exoPlayer.stop()
                updateContainerVisibility()
            }
        }

        @JavascriptInterface
        fun extractFromPageUrl(sourceUrl: String?, wantPaired: Boolean) {
            extractFromPageUrl(sourceUrl, wantPaired, "")
        }

        @JavascriptInterface
        fun extractFromPageUrl(sourceUrl: String?, wantPaired: Boolean, streamHintJson: String?) {
            val clean = sourceUrl?.trim() ?: return
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
            val hintStr = streamHintJson ?: ""
            mainHandler.post {
                onExtractSourceUrlRequested(clean, wantPaired, hintStr)
            }
        }
    }
}
