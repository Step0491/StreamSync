package com.streamsync.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.streamsync.app.extractor.DetectedStream
import com.streamsync.app.extractor.StreamKind

/**
 * Tabbed Material3 BottomSheet for inspecting categorized streams
 * (Combined, Manual Video+Audio Pair, Video-Only, Audio-Only) with codecs, resolution,
 * language, and one-tap synchronized Room playback.
 */
object StreamSelectorSheet {

    private enum class SheetTab {
        COMBINED,
        PAIR,
        VIDEO_ONLY,
        AUDIO_ONLY,
        MANUAL_URLS
    }

    fun show(
        context: Context,
        streams: List<DetectedStream>,
        onSelectPlayback: (video: DetectedStream, audio: DetectedStream?) -> Unit,
        onClearStreams: () -> Unit,
        onExtractCurrentPage: () -> Unit
    ) {
        val combined = streams.filter { it.kind == StreamKind.COMBINED }
        val videoOnly = streams.filter { it.kind == StreamKind.VIDEO_ONLY }
        val audioOnly = streams.filter { it.kind == StreamKind.AUDIO_ONLY }
        val pairableVideos = videoOnly + combined

        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131A26"))
            setPadding(dp(16), dp(14), dp(16), dp(20))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // --- Header Bar ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = "Streams (${streams.size})"
            setTextColor(Color.parseColor("#E6EDF7"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val extractBtn = createActionButton(context, "Extract Page", "#6366F1", "#FFFFFF", dp(9), dp(6)).apply {
            setOnClickListener {
                dialog.dismiss()
                onExtractCurrentPage()
            }
        }
        val clearBtn = createActionButton(context, "Clear", "#1B2434", "#93A4BD", dp(9), dp(6)).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(6)
            layoutParams = lp
            setOnClickListener {
                onClearStreams()
                dialog.dismiss()
            }
        }
        val closeBtn = createActionButton(context, "Close", "#1B2434", "#E6EDF7", dp(9), dp(6)).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(6)
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(extractBtn)
        headerRow.addView(clearBtn)
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // --- Hint Banner if separated high-res tracks exist ---
        if (videoOnly.isNotEmpty() && audioOnly.isNotEmpty()) {
            val hintBanner = TextView(context).apply {
                text = "Separated Video (${videoOnly.size}) & Audio (${audioOnly.size}) tracks detected — use 'Pair V + A' to stream any Video + Audio language in sync."
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 12f
                background = roundedBg("#1E2440", "#4F46E5", dp(8))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(10)
                layoutParams = lp
            }
            root.addView(hintBanner)
        }

        // --- Tabs Row ---
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabRow)
        root.addView(tabScroll)

        // --- Content Scroll Container ---
        val contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.56f).toInt()
            )
        }
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentScroll.addView(contentContainer)
        root.addView(contentScroll)

        var activeTab = when {
            streams.isEmpty() -> SheetTab.MANUAL_URLS
            combined.isEmpty() && videoOnly.isNotEmpty() && audioOnly.isNotEmpty() -> SheetTab.PAIR
            combined.isNotEmpty() -> SheetTab.COMBINED
            videoOnly.isNotEmpty() -> SheetTab.VIDEO_ONLY
            else -> SheetTab.AUDIO_ONLY
        }
        var preselectedVideo: DetectedStream? = null
        var preselectedAudio: DetectedStream? = null

        val tabButtons = LinkedHashMap<SheetTab, Button>()

        fun renderContent() {
            for ((t, btn) in tabButtons) {
                val isSelected = t == activeTab
                btn.background = roundedBg(
                    if (isSelected) "#6366F1" else "#1B2434",
                    if (isSelected) "#6366F1" else "#283348",
                    dp(8)
                )
                btn.setTextColor(Color.parseColor(if (isSelected) "#FFFFFF" else "#93A4BD"))
            }

            contentContainer.removeAllViews()

            when (activeTab) {
                SheetTab.PAIR -> {
                    renderPairBuilder(
                        context = context,
                        container = contentContainer,
                        videos = pairableVideos,
                        audios = audioOnly,
                        initialVideo = preselectedVideo,
                        initialAudio = preselectedAudio,
                        dp = ::dp,
                        onPairConfirm = { v, a ->
                            dialog.dismiss()
                            onSelectPlayback(v, a)
                        }
                    )
                }
                SheetTab.COMBINED -> {
                    renderStreamList(
                        context = context,
                        container = contentContainer,
                        items = combined,
                        emptyMsg = "No combined Video+Audio streams detected on this page. Check 'Pair V + A' or 'Video-Only'.",
                        hasAudioTracksToPair = audioOnly.isNotEmpty(),
                        hasVideoTracksToPair = false,
                        dp = ::dp,
                        onPlaySolo = { s ->
                            dialog.dismiss()
                            onSelectPlayback(s, null)
                        },
                        onJumpToPair = { s ->
                            preselectedVideo = s
                            activeTab = SheetTab.PAIR
                            renderContent()
                        }
                    )
                }
                SheetTab.VIDEO_ONLY -> {
                    renderStreamList(
                        context = context,
                        container = contentContainer,
                        items = videoOnly,
                        emptyMsg = "No separated Video-Only tracks detected.",
                        hasAudioTracksToPair = audioOnly.isNotEmpty(),
                        hasVideoTracksToPair = false,
                        dp = ::dp,
                        onPlaySolo = { s ->
                            dialog.dismiss()
                            onSelectPlayback(s, null)
                        },
                        onJumpToPair = { s ->
                            preselectedVideo = s
                            activeTab = SheetTab.PAIR
                            renderContent()
                        }
                    )
                }
                SheetTab.AUDIO_ONLY -> {
                    renderStreamList(
                        context = context,
                        container = contentContainer,
                        items = audioOnly,
                        emptyMsg = "No separated Audio-Only tracks detected.",
                        hasAudioTracksToPair = false,
                        hasVideoTracksToPair = pairableVideos.isNotEmpty(),
                        dp = ::dp,
                        onPlaySolo = { s ->
                            dialog.dismiss()
                            onSelectPlayback(s, null)
                        },
                        onJumpToPair = { s ->
                            preselectedAudio = s
                            activeTab = SheetTab.PAIR
                            renderContent()
                        }
                    )
                }
                SheetTab.MANUAL_URLS -> {
                    renderManualUrlsTab(
                        context = context,
                        container = contentContainer,
                        dp = ::dp,
                        onPlayManual = { v, a ->
                            dialog.dismiss()
                            onSelectPlayback(v, a)
                        }
                    )
                }
            }
        }

        val tabSpecs = listOf(
            SheetTab.COMBINED to "Combined (${combined.size})",
            SheetTab.PAIR to "Pair V + A",
            SheetTab.VIDEO_ONLY to "Video-Only (${videoOnly.size})",
            SheetTab.AUDIO_ONLY to "Audio-Only (${audioOnly.size})",
            SheetTab.MANUAL_URLS to "Manual URLs"
        )

        for ((tab, title) in tabSpecs) {
            val btn = createActionButton(context, title, "#1B2434", "#93A4BD", dp(12), dp(7)).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(6)
                layoutParams = lp
                setOnClickListener {
                    activeTab = tab
                    renderContent()
                }
            }
            tabButtons[tab] = btn
            tabRow.addView(btn)
        }

        renderContent()

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun renderManualUrlsTab(
        context: Context,
        container: LinearLayout,
        dp: (Int) -> Int,
        onPlayManual: (DetectedStream, DetectedStream?) -> Unit
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1B2434", "#283348", dp(10))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val infoTv = TextView(context).apply {
            text = "Paste 1 Video URL + optional 1 Audio URL directly (e.g. from Video DownloadHelper). Plays natively in ExoPlayer & syncs to Room, bypassing yt-dlp / Cloudflare."
            setTextColor(Color.parseColor("#93A4BD"))
            textSize = 12f
        }

        val vLabel = TextView(context).apply {
            text = "Video URL (Combined or Video-Only)"
            setTextColor(Color.parseColor("#E6EDF7"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }

        val vInput = android.widget.EditText(context).apply {
            hint = "https://.../video.mp4 or .m3u8"
            setTextColor(Color.parseColor("#E6EDF7"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12.5f
            isSingleLine = true
            background = roundedBg("#0B0F17", "#283348", dp(8))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }

        val aLabel = TextView(context).apply {
            text = "Audio URL (Optional — for separated audio track)"
            setTextColor(Color.parseColor("#E6EDF7"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val aInput = android.widget.EditText(context).apply {
            hint = "https://.../audio.m4a or .m3u8 (optional)"
            setTextColor(Color.parseColor("#E6EDF7"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12.5f
            isSingleLine = true
            background = roundedBg("#0B0F17", "#283348", dp(8))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            lp.bottomMargin = dp(14)
            layoutParams = lp
        }

        val playBtn = createActionButton(
            context,
            "Play Direct URL(s) in ExoPlayer & Room",
            "#6366F1",
            "#FFFFFF",
            dp(14),
            dp(11)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val vUrl = vInput.text.toString().trim()
                val aUrl = aInput.text.toString().trim()
                if (!vUrl.startsWith("http://", ignoreCase = true) && !vUrl.startsWith("https://", ignoreCase = true)) {
                    Toast.makeText(context, "Enter a valid http(s) Video URL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val vLower = vUrl.lowercase()
                val vStream = DetectedStream(
                    id = vUrl,
                    url = vUrl,
                    normalizedUrl = vUrl,
                    kind = if (aUrl.isNotEmpty()) StreamKind.VIDEO_ONLY else StreamKind.COMBINED,
                    container = if (vLower.contains(".m3u8")) "m3u8" else if (vLower.contains(".mpd")) "mpd" else "mp4",
                    label = if (aUrl.isNotEmpty()) "Manual Video + Audio" else "Manual Direct Stream",
                    isHls = vLower.contains(".m3u8"),
                    isDash = vLower.contains(".mpd")
                )
                val aStream = if (aUrl.startsWith("http://", ignoreCase = true) || aUrl.startsWith("https://", ignoreCase = true)) {
                    val aLower = aUrl.lowercase()
                    DetectedStream(
                        id = aUrl,
                        url = aUrl,
                        normalizedUrl = aUrl,
                        kind = StreamKind.AUDIO_ONLY,
                        container = if (aLower.contains(".m3u8")) "m3u8" else if (aLower.contains(".mpd")) "mpd" else "m4a",
                        label = "Manual Audio Track",
                        isHls = aLower.contains(".m3u8"),
                        isDash = aLower.contains(".mpd")
                    )
                } else {
                    null
                }
                onPlayManual(vStream, aStream)
            }
        }

        box.addView(infoTv)
        box.addView(vLabel)
        box.addView(vInput)
        box.addView(aLabel)
        box.addView(aInput)
        box.addView(playBtn)
        container.addView(box)
    }

    private fun renderPairBuilder(
        context: Context,
        container: LinearLayout,
        videos: List<DetectedStream>,
        audios: List<DetectedStream>,
        initialVideo: DetectedStream?,
        initialAudio: DetectedStream?,
        dp: (Int) -> Int,
        onPairConfirm: (DetectedStream, DetectedStream) -> Unit
    ) {
        val playableVideos = videos.filter { !it.isDrmProtected }
        val playableAudios = audios.filter { !it.isDrmProtected }

        if (playableVideos.isEmpty() || playableAudios.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "Manual pairing requires at least 1 Video stream and 1 Audio-Only stream.\nStart playback or switch quality/language in the webpage player so its tracks are captured."
                setTextColor(Color.parseColor("#93A4BD"))
                textSize = 13f
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            container.addView(emptyTv)
            return
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1B2434", "#283348", dp(10))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val vLabel = TextView(context).apply {
            text = "1. Select Video Stream (${playableVideos.size} available)"
            setTextColor(Color.parseColor("#E6EDF7"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        val videoLabels = playableVideos.map { s ->
            val tag = if (s.kind == StreamKind.VIDEO_ONLY) "Video-Only" else "Muxed Video"
            "${s.label} [$tag • ${s.metadataSubtitle()}]"
        }
        val videoSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, videoLabels)
            background = roundedBg("#0B0F17", "#283348", dp(8))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            lp.bottomMargin = dp(14)
            layoutParams = lp
        }
        if (initialVideo != null) {
            val idx = playableVideos.indexOfFirst { it.normalizedUrl == initialVideo.normalizedUrl }
            if (idx >= 0) videoSpinner.setSelection(idx)
        }

        val aLabel = TextView(context).apply {
            text = "2. Select Audio Track (${playableAudios.size} available)"
            setTextColor(Color.parseColor("#E6EDF7"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        val audioLabels = playableAudios.map { s ->
            "${s.label} [${s.metadataSubtitle()}]"
        }
        val audioSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, audioLabels)
            background = roundedBg("#0B0F17", "#283348", dp(8))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            lp.bottomMargin = dp(16)
            layoutParams = lp
        }
        if (initialAudio != null) {
            val idx = playableAudios.indexOfFirst { it.normalizedUrl == initialAudio.normalizedUrl }
            if (idx >= 0) audioSpinner.setSelection(idx)
        }

        val playPairedBtn = createActionButton(
            context,
            "Play Selected Video + Audio in Sync",
            "#6366F1",
            "#FFFFFF",
            dp(14),
            dp(11)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val v = playableVideos[videoSpinner.selectedItemPosition]
                val a = playableAudios[audioSpinner.selectedItemPosition]
                onPairConfirm(v, a)
            }
        }

        box.addView(vLabel)
        box.addView(videoSpinner)
        box.addView(aLabel)
        box.addView(audioSpinner)
        box.addView(playPairedBtn)
        container.addView(box)
    }

    private fun renderStreamList(
        context: Context,
        container: LinearLayout,
        items: List<DetectedStream>,
        emptyMsg: String,
        hasAudioTracksToPair: Boolean,
        hasVideoTracksToPair: Boolean,
        dp: (Int) -> Int,
        onPlaySolo: (DetectedStream) -> Unit,
        onJumpToPair: (DetectedStream) -> Unit
    ) {
        if (items.isEmpty()) {
            val tv = TextView(context).apply {
                text = emptyMsg
                setTextColor(Color.parseColor("#93A4BD"))
                textSize = 13f
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            container.addView(tv)
            return
        }

        for (stream in items) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg("#1B2434", "#283348", dp(10))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }

            val titleTv = TextView(context).apply {
                text = stream.label
                setTextColor(Color.parseColor(if (stream.isDrmProtected) "#FCA5A5" else "#E6EDF7"))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            }

            val metaTv = TextView(context).apply {
                text = stream.metadataSubtitle()
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 11.5f
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(2)
                layoutParams = lp
            }

            val urlTv = TextView(context).apply {
                text = shortenUrlForDisplay(stream.url)
                setTextColor(Color.parseColor("#93A4BD"))
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(2)
                layoutParams = lp
            }

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            if (stream.isDrmProtected) {
                val drmNote = TextView(context).apply {
                    text = "Encrypted (Widevine/PlayReady DRM — cannot extract)"
                    setTextColor(Color.parseColor("#FCA5A5"))
                    textSize = 11.5f
                }
                btnRow.addView(drmNote)
            } else {
                when (stream.kind) {
                    StreamKind.COMBINED -> {
                        val playBtn = createActionButton(context, "Play in Room", "#6366F1", "#FFFFFF", dp(12), dp(7)).apply {
                            setOnClickListener { onPlaySolo(stream) }
                        }
                        btnRow.addView(playBtn)
                        if (hasAudioTracksToPair) {
                            val pairAltBtn = createActionButton(context, "Pair External Audio", "#0B0F17", "#A5B4FC", dp(10), dp(7)).apply {
                                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                lp.marginStart = dp(6)
                                layoutParams = lp
                                setOnClickListener { onJumpToPair(stream) }
                            }
                            btnRow.addView(pairAltBtn)
                        }
                    }
                    StreamKind.VIDEO_ONLY -> {
                        if (hasAudioTracksToPair) {
                            val pairBtn = createActionButton(context, "Pair with Audio...", "#6366F1", "#FFFFFF", dp(12), dp(7)).apply {
                                setOnClickListener { onJumpToPair(stream) }
                            }
                            btnRow.addView(pairBtn)
                        }
                        val mutedBtn = createActionButton(context, "Play Muted", "#0B0F17", "#FCA5A5", dp(10), dp(7)).apply {
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            if (hasAudioTracksToPair) lp.marginStart = dp(6)
                            layoutParams = lp
                            setOnClickListener { onPlaySolo(stream) }
                        }
                        btnRow.addView(mutedBtn)
                    }
                    StreamKind.AUDIO_ONLY -> {
                        if (hasVideoTracksToPair) {
                            val pairBtn = createActionButton(context, "Pair with Video...", "#6366F1", "#FFFFFF", dp(12), dp(7)).apply {
                                setOnClickListener { onJumpToPair(stream) }
                            }
                            btnRow.addView(pairBtn)
                        }
                        val audioBtn = createActionButton(context, "Play Audio", "#0B0F17", "#E6EDF7", dp(10), dp(7)).apply {
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            if (hasVideoTracksToPair) lp.marginStart = dp(6)
                            layoutParams = lp
                            setOnClickListener { onPlaySolo(stream) }
                        }
                        btnRow.addView(audioBtn)
                    }
                }

                val copyBtn = createActionButton(context, "Copy", "#0B0F17", "#93A4BD", dp(10), dp(7)).apply {
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.marginStart = dp(6)
                    layoutParams = lp
                    setOnClickListener {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Stream URL", stream.url))
                        Toast.makeText(context, "Stream URL copied", Toast.LENGTH_SHORT).show()
                    }
                }
                btnRow.addView(copyBtn)
            }

            card.addView(titleTv)
            card.addView(metaTv)
            card.addView(urlTv)
            card.addView(btnRow)
            container.addView(card)
        }
    }

    private fun shortenUrlForDisplay(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = u.host ?: ""
            val path = u.path ?: ""
            "$host$path"
        } catch (_: Exception) {
            url.take(80)
        }
    }

    private fun createActionButton(
        context: Context,
        label: String,
        bgHex: String,
        fgHex: String,
        padH: Int,
        padV: Int
    ): Button {
        val density = context.resources.displayMetrics.density
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(padH, padV, padH, padV)
            setTextColor(Color.parseColor(fgHex))
            background = roundedBg(bgHex, "#283348", (7 * density).toInt())
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(2, Color.parseColor(strokeHex))
        }
    }
}
