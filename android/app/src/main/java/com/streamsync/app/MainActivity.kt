package com.streamsync.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.streamsync.app.adblock.AdBlockEngine
import com.streamsync.app.adblock.FilterRuleEngine
import com.streamsync.app.browser.GeckoBrowserController
import com.streamsync.app.extractor.DetectedStream
import com.streamsync.app.extractor.StreamSniffer
import com.streamsync.app.player.NativeRoomPlayerController
import com.streamsync.app.ui.StreamSelectorSheet
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var topModeBar: LinearLayout
    private lateinit var addressBar: LinearLayout
    private lateinit var roomPane: LinearLayout
    private lateinit var nativePlayerContainer: LinearLayout
    private lateinit var nativePlayerHeader: LinearLayout
    private lateinit var nativePlayerView: PlayerView
    private lateinit var tvNativePlayerTitle: TextView
    private lateinit var tvNativePlayerBadge: TextView

    private lateinit var roomWebView: WebView
    private lateinit var browserGeckoView: GeckoView
    private lateinit var etUrl: EditText
    private lateinit var btnShield: Button
    private lateinit var btnTabRoom: LinearLayout
    private lateinit var btnTabBrowser: LinearLayout
    private lateinit var btnStreams: LinearLayout
    private lateinit var btnUpdate: LinearLayout
    private lateinit var tvStreamLabel: TextView
    private lateinit var tvUpdateLabel: TextView

    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var streamSniffer: StreamSniffer
    private lateinit var geckoBrowser: GeckoBrowserController
    private lateinit var nativePlayerController: NativeRoomPlayerController

    @Volatile
    private var latestStreams: List<DetectedStream> = emptyList()
    @Volatile
    private var hasUnviewedStreams = false
    private var showingRoom = true
    private var currentBrowserUrl = "https://www.google.com"
    private var lastBrowserDomain = ""
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private val prefs by lazy { getSharedPreferences("streamsync_prefs", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        topModeBar = findViewById(R.id.topModeBar)
        addressBar = findViewById(R.id.addressBar)
        roomPane = findViewById(R.id.roomPane)
        nativePlayerContainer = findViewById(R.id.nativePlayerContainer)
        nativePlayerHeader = findViewById(R.id.nativePlayerHeader)
        nativePlayerView = findViewById(R.id.nativePlayerView)
        tvNativePlayerTitle = findViewById(R.id.tvNativePlayerTitle)
        tvNativePlayerBadge = findViewById(R.id.tvNativePlayerBadge)

        roomWebView = findViewById(R.id.roomWebView)
        browserGeckoView = findViewById(R.id.browserGeckoView)
        etUrl = findViewById(R.id.etUrl)
        btnShield = findViewById(R.id.btnShield)
        btnTabRoom = findViewById(R.id.btnTabRoom)
        btnTabBrowser = findViewById(R.id.btnTabBrowser)
        btnStreams = findViewById(R.id.btnStreams)
        btnUpdate = findViewById(R.id.btnUpdate)
        tvStreamLabel = findViewById(R.id.tvStreamLabel)
        tvUpdateLabel = findViewById(R.id.tvUpdateLabel)
        val btnGo: Button = findViewById(R.id.btnGo)

        val defaultServer = "https://streamsync-pj98.onrender.com/"
        var savedServer = prefs.getString("server_url", defaultServer) ?: defaultServer
        if (savedServer == "https://streamsync.onrender.com" || savedServer == "https://streamsync.onrender.com/") {
            savedServer = defaultServer
            prefs.edit().putString("server_url", defaultServer).apply()
        }

        // Initialize AdBlock Engine (per-site state & popup/redirect shield)
        adBlockEngine = AdBlockEngine(applicationContext, prefs)

        val defaultUa = WebSettings.getDefaultUserAgent(this)

        // Initialize VDH-grade Stream Sniffer fed directly by GeckoView WebExtension (filterResponseData)
        streamSniffer = StreamSniffer(
            defaultUserAgent = defaultUa,
            onStreamsUpdated = { sorted ->
                val prevSize = latestStreams.size
                latestStreams = sorted
                if (sorted.isNotEmpty() && sorted.size > prevSize) {
                    hasUnviewedStreams = true
                } else if (sorted.isEmpty()) {
                    hasUnviewedStreams = false
                }
                runOnUiThread { updateStreamsButton() }
            },
            onPopupBlockedByJs = {
                adBlockEngine.recordJsBlockedPopup()
                runOnUiThread { updateShieldButton() }
            }
        )

        // Initialize Native Media3 ExoPlayer Controller bridged to roomWebView
        nativePlayerController = NativeRoomPlayerController(
            context = this,
            playerContainer = nativePlayerContainer,
            playerView = nativePlayerView,
            tvPlayerTitle = tvNativePlayerTitle,
            tvPlayerBadge = tvNativePlayerBadge,
            roomWebView = roomWebView,
            defaultUserAgent = defaultUa,
            onFullscreenToggle = { fullscreen -> applyFullscreenMode(fullscreen) },
            onExtractSourceUrlRequested = { srcUrl, wantPaired ->
                extractStreamSilentlyForGuest(srcUrl, wantPaired)
            }
        )

        // Initialize Mozilla GeckoView (Firefox Engine) + Built-In WebExtension Sniffer & Shield
        geckoBrowser = GeckoBrowserController(
            context = this,
            geckoView = browserGeckoView,
            adBlockEngine = adBlockEngine,
            streamSniffer = streamSniffer,
            onLocationChanged = { url ->
                currentBrowserUrl = url
                val dom = FilterRuleEngine.parseUriSafe(url)?.host?.let { FilterRuleEngine.registrableDomain(it) }.orEmpty()
                if (dom.isNotEmpty() && dom != lastBrowserDomain) {
                    lastBrowserDomain = dom
                    adBlockEngine.resetPageCounter()
                }
                runOnUiThread {
                    if (!showingRoom) etUrl.setText(url)
                    updateShieldButton()
                }
            },
            onTitleChanged = { _ -> },
            onShieldStatsChanged = {
                runOnUiThread { updateShieldButton() }
            }
        )

        CookieManager.getInstance().setAcceptCookie(true)
        configureRoomWebView()

        etUrl.setText(savedServer)
        roomWebView.loadUrl(savedServer)
        geckoBrowser.loadUrl(currentBrowserUrl)

        btnTabRoom.setOnClickListener { switchTab(showRoom = true) }
        btnTabBrowser.setOnClickListener { switchTab(showRoom = false) }
        btnStreams.setOnClickListener { openStreamSelector() }
        btnUpdate.setOnClickListener { checkForUpdates() }

        btnShield.setOnClickListener {
            val enabledNow = adBlockEngine.toggleShieldForUrl(currentBrowserUrl)
            geckoBrowser.syncShieldStateToExtension(currentBrowserUrl)
            val host = adBlockEngine.extractHost(currentBrowserUrl) ?: "this site"
            Toast.makeText(
                this,
                if (enabledNow) "Gecko Ad/Popup Shield enabled for $host" else "Shield paused for $host",
                Toast.LENGTH_SHORT
            ).show()
            updateShieldButton()
            geckoBrowser.reload()
        }

        btnGo.setOnClickListener { navigateCurrentTab() }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateCurrentTab()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nativePlayerController.exitFullscreenIfNeeded()) {
                    return
                }
                if (showingRoom) {
                    if (roomWebView.canGoBack()) {
                        roomWebView.goBack()
                    } else {
                        finish()
                    }
                } else {
                    if (geckoBrowser.canGoBack) {
                        geckoBrowser.goBack()
                    } else {
                        switchTab(showRoom = true)
                    }
                }
            }
        })

        updateTabStyles()
        updateShieldButton()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureRoomWebView() {
        roomWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        roomWebView.addJavascriptInterface(nativePlayerController.jsBridge, "AndroidNativePlayer")
        roomWebView.clearCache(true)
        roomWebView.webChromeClient = WebChromeClient()
        roomWebView.webViewClient = WebViewClient()
    }

    private fun updateStreamsButton() {
        tvStreamLabel.text = "Stream"
        btnStreams.isSelected = hasUnviewedStreams
    }

    private fun updateShieldButton() {
        btnShield.visibility = if (showingRoom) View.GONE else View.VISIBLE
        val enabled = adBlockEngine.isShieldEnabledForUrl(currentBrowserUrl)
        val count = adBlockEngine.blockedOnCurrentPage
        if (enabled) {
            btnShield.text = if (count > 0) "Shield ($count)" else "Shield: ON"
            btnShield.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            btnShield.text = "Shield: OFF"
            btnShield.setTextColor(Color.parseColor("#FCA5A5"))
        }
    }

    private fun switchTab(showRoom: Boolean) {
        showingRoom = showRoom
        roomPane.visibility = if (showRoom) View.VISIBLE else View.GONE
        browserGeckoView.visibility = if (showRoom) View.GONE else View.VISIBLE
        nativePlayerController.setRoomTabVisibility(showRoom)
        etUrl.setText(
            if (showRoom) (roomWebView.url ?: prefs.getString("server_url", ""))
            else geckoBrowser.currentUrl
        )
        updateTabStyles()
        updateShieldButton()
    }

    private fun updateTabStyles() {
        btnTabRoom.isSelected = showingRoom
        btnTabBrowser.isSelected = !showingRoom
    }

    private fun applyFullscreenMode(fullscreen: Boolean) {
        val density = resources.displayMetrics.density
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            topModeBar.visibility = View.GONE
            addressBar.visibility = View.GONE
            nativePlayerHeader.visibility = View.GONE
            roomWebView.visibility = View.GONE
            nativePlayerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            nativePlayerView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            topModeBar.visibility = View.VISIBLE
            addressBar.visibility = View.VISIBLE
            nativePlayerHeader.visibility = View.VISIBLE
            roomWebView.visibility = View.VISIBLE
            nativePlayerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            nativePlayerView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (225 * density).toInt()
            )
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun navigateCurrentTab() {
        var url = etUrl.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (showingRoom) {
            prefs.edit().putString("server_url", url).apply()
            roomWebView.loadUrl(url)
        } else {
            currentBrowserUrl = url
            streamSniffer.clearForNewTopLevelPage(url)
            adBlockEngine.resetPageCounter()
            geckoBrowser.loadUrl(url)
        }
    }

    private fun openStreamSelector() {
        hasUnviewedStreams = false
        updateStreamsButton()
        StreamSelectorSheet.show(
            context = this,
            streams = latestStreams,
            onSelectPlayback = { videoStream, audioStream ->
                sendStreamSelectionToRoom(videoStream, audioStream)
            },
            onClearStreams = {
                hasUnviewedStreams = false
                updateStreamsButton()
                streamSniffer.clearForNewTopLevelPage(currentBrowserUrl)
            },
            onExtractCurrentPage = {
                extractCurrentBrowserPageInRoom()
            }
        )
    }

    private fun extractCurrentBrowserPageInRoom() {
        val targetUrl = geckoBrowser.currentUrl.ifBlank { currentBrowserUrl }
        if (targetUrl.isBlank() || !targetUrl.startsWith("http")) {
            Toast.makeText(this, "Open a video page in Browser first.", Toast.LENGTH_SHORT).show()
            return
        }
        switchTab(showRoom = true)
        val quotedUrl = JSONObject.quote(targetUrl)
        roomWebView.evaluateJavascript(
            """
            (function() {
              const inp = document.getElementById('url-input');
              const form = document.getElementById('extract-form');
              if (inp && form) {
                inp.value = $quotedUrl;
                form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
              }
            })();
            """.trimIndent(),
            null
        )
        Toast.makeText(this, "Extracting page via StreamSync Server...", Toast.LENGTH_SHORT).show()
    }

    private fun sendStreamSelectionToRoom(videoStream: DetectedStream, audioStream: DetectedStream?) {
        hasUnviewedStreams = false
        updateStreamsButton()
        val pageTitle = (geckoBrowser.currentTitle.ifBlank { videoStream.pageTitle ?: "Detected Stream" }).take(140)
        val mergedHeaders = LinkedHashMap<String, String>(videoStream.requestHeaders)
        if (audioStream != null) {
            for ((k, v) in audioStream.requestHeaders) {
                if (v.isNotBlank() && !mergedHeaders.containsKey(k)) {
                    mergedHeaders[k] = v
                }
            }
        }

        val combinedLabel = if (audioStream != null) {
            "${videoStream.resolutionBadge() ?: videoStream.container.uppercase()} + ${audioStream.language ?: audioStream.container.uppercase()} (Synced)"
        } else {
            videoStream.label
        }

        switchTab(showRoom = true)

        nativePlayerController.loadNativePlayback(
            videoUrl = videoStream.url,
            audioUrl = audioStream?.url,
            isHls = videoStream.isHls,
            isDash = videoStream.isDash,
            title = pageTitle,
            label = combinedLabel,
            headers = mergedHeaders,
            autoPlay = true
        )

        val headersJson = JSONObject()
        for ((k, v) in mergedHeaders) {
            if (!k.equals("Cookie", ignoreCase = true)) {
                headersJson.put(k, v)
            }
        }

        val jsPayload = JSONObject().apply {
            put("video_url", videoStream.url)
            put("audio_url", audioStream?.url ?: "")
            put("is_hls", videoStream.isHls)
            put("is_dash", videoStream.isDash)
            put("title", pageTitle)
            put("label", combinedLabel)
            put("headers", headersJson)
            put("source_url", geckoBrowser.currentUrl.ifBlank { currentBrowserUrl })
            put("already_loaded_native", true)
        }

        val jsVideo = JSONObject.quote(videoStream.url)
        val jsAudio = JSONObject.quote(audioStream?.url ?: "")
        val jsTitle = JSONObject.quote(pageTitle)
        val jsPayloadQuoted = JSONObject.quote(jsPayload.toString())

        roomWebView.onResume()
        roomWebView.evaluateJavascript(
            """
            (function() {
              var p = JSON.parse($jsPayloadQuoted);
              if (window.loadFromNativePayload) {
                window.loadFromNativePayload(p);
                return true;
              }
              if (window.loadFromNativeApp) {
                window.loadFromNativeApp($jsVideo, $jsAudio, $jsTitle);
                return true;
              }
              return false;
            })();
            """.trimIndent()
        ) { result ->
            if (result != "true") {
                val base = (roomWebView.url ?: prefs.getString("server_url", "https://streamsync-pj98.onrender.com/"))!!
                val uri = Uri.parse(base).buildUpon()
                    .appendQueryParameter("video", videoStream.url)
                    .appendQueryParameter("title", pageTitle)
                if (audioStream != null) {
                    uri.appendQueryParameter("audio", audioStream.url)
                }
                roomWebView.loadUrl(uri.build().toString())
            }
        }

        Toast.makeText(
            this,
            if (audioStream != null) "Paired Video + Audio loaded in ExoPlayer & Room" else "Stream loaded in ExoPlayer & Room",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun extractStreamSilentlyForGuest(sourceUrl: String, wantPaired: Boolean) {
        currentBrowserUrl = sourceUrl
        streamSniffer.clearForNewTopLevelPage(sourceUrl)
        adBlockEngine.resetPageCounter()
        // Keep GeckoView INVISIBLE (rather than GONE) while extracting so Gecko media/iframe timers run normally
        if (showingRoom) {
            browserGeckoView.visibility = View.INVISIBLE
        }
        geckoBrowser.setGuestAutoPlayMode(true)
        geckoBrowser.loadUrl(sourceUrl)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startMs = System.currentTimeMillis()
        var firstCandidateMs = 0L
        val poller = object : Runnable {
            override fun run() {
                val list = latestStreams.filter { !it.isDrmProtected }
                val combined = list.filter { it.kind == com.streamsync.app.extractor.StreamKind.COMBINED }
                val videoOnly = list.filter { it.kind == com.streamsync.app.extractor.StreamKind.VIDEO_ONLY }
                val audioOnly = list.filter { it.kind == com.streamsync.app.extractor.StreamKind.AUDIO_ONLY }

                var chosenVideo: DetectedStream? = null
                var chosenAudio: DetectedStream? = null

                if (wantPaired && videoOnly.isNotEmpty() && audioOnly.isNotEmpty()) {
                    chosenVideo = videoOnly.first()
                    chosenAudio = audioOnly.first()
                } else if (combined.isNotEmpty() || (videoOnly.isNotEmpty() && audioOnly.isNotEmpty())) {
                    if (firstCandidateMs == 0L) firstCandidateMs = System.currentTimeMillis()
                    if (!wantPaired || System.currentTimeMillis() - firstCandidateMs >= 1800L) {
                        if (videoOnly.isNotEmpty() && audioOnly.isNotEmpty()) {
                            chosenVideo = videoOnly.first()
                            chosenAudio = audioOnly.first()
                        } else if (combined.isNotEmpty()) {
                            chosenVideo = combined.first()
                        }
                    }
                }

                if (chosenVideo != null) {
                    // Stop hidden GeckoView page immediately so there is ZERO background audio bleed
                    geckoBrowser.setGuestAutoPlayMode(false)
                    geckoBrowser.loadUrl("about:blank")
                    if (showingRoom) {
                        browserGeckoView.visibility = View.GONE
                    }

                    val mergedHeaders = LinkedHashMap<String, String>(chosenVideo.requestHeaders)
                    if (chosenAudio != null) {
                        for ((k, v) in chosenAudio.requestHeaders) {
                            if (v.isNotBlank() && !mergedHeaders.containsKey(k)) mergedHeaders[k] = v
                        }
                    }
                    val label = if (chosenAudio != null) {
                        "Refreshed Video + Audio (Synced)"
                    } else {
                        chosenVideo.label
                    }
                    nativePlayerController.loadNativePlayback(
                        videoUrl = chosenVideo.url,
                        audioUrl = chosenAudio?.url,
                        isHls = chosenVideo.isHls,
                        isDash = chosenVideo.isDash,
                        title = geckoBrowser.currentTitle.ifBlank { "Synced Stream" },
                        label = label,
                        headers = mergedHeaders,
                        autoPlay = true
                    )
                    val payload = JSONObject().apply {
                        put("video_url", chosenVideo.url)
                        put("audio_url", chosenAudio?.url ?: "")
                        put("is_hls", chosenVideo.isHls)
                        put("is_dash", chosenVideo.isDash)
                        put("label", label)
                        put("already_loaded_native", true)
                    }
                    val quoted = JSONObject.quote(payload.toString())
                    roomWebView.evaluateJavascript(
                        "if (window.onGuestNativeReExtracted) { window.onGuestNativeReExtracted(JSON.parse($quoted)); }",
                        null
                    )
                    return
                }

                if (System.currentTimeMillis() - startMs < 15_000L) {
                    handler.postDelayed(this, 500L)
                } else {
                    geckoBrowser.setGuestAutoPlayMode(false)
                    geckoBrowser.loadUrl("about:blank")
                    if (showingRoom) {
                        browserGeckoView.visibility = View.GONE
                    }
                    roomWebView.evaluateJavascript(
                        "if (window.onGuestNativeExtractFailed) { window.onGuestNativeExtractFailed('timeout'); }",
                        null
                    )
                }
            }
        }
        handler.postDelayed(poller, 1200L)
    }

    private fun checkForUpdates() {
        btnUpdate.isEnabled = false
        tvUpdateLabel.text = "Check..."
        val currentBuild = BuildConfig.VERSION_CODE
        val currentVersion = BuildConfig.VERSION_NAME

        networkExecutor.execute {
            try {
                val conn = (URL("https://api.github.com/repos/Step0491/StreamSync/releases?per_page=15")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "StreamSync-Android/$currentVersion")
                }

                val code = conn.responseCode
                if (code != 200) {
                    throw IllegalStateException("GitHub API returned HTTP $code")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(body)
                var bestBuild = -1
                var apkUrl: String? = null

                for (rIdx in 0 until releases.length()) {
                    val rel = releases.getJSONObject(rIdx)
                    val tagName = rel.optString("tag_name", "")
                    if (!tagName.startsWith("android-build-", ignoreCase = true)) continue
                    val buildNum = Regex("""(\d+)$""").find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                    val assets = rel.optJSONArray("assets") ?: continue
                    var foundApk: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            foundApk = asset.optString("browser_download_url")
                            break
                        }
                    }
                    if (foundApk != null && buildNum > bestBuild) {
                        bestBuild = buildNum
                        apkUrl = foundApk
                    }
                }

                if (bestBuild < 0) {
                    throw IllegalStateException("No android-build release with APK found")
                }
                val remoteBuild = bestBuild

                runOnUiThread {
                    btnUpdate.isEnabled = true
                    tvUpdateLabel.text = "Update"
                    if (remoteBuild <= currentBuild) {
                        AlertDialog.Builder(this)
                            .setTitle("App Up to Date")
                            .setMessage("Installed: v$currentVersion (build $currentBuild)\nLatest release: build $remoteBuild")
                            .setPositiveButton("OK", null)
                            .show()
                    } else if (apkUrl != null) {
                        AlertDialog.Builder(this)
                            .setTitle("Update Available")
                            .setMessage("Installed: v$currentVersion (build $currentBuild)\nNew version: v0.3.$remoteBuild (build $remoteBuild)")
                            .setPositiveButton("Download & Install") { _, _ ->
                                downloadAndInstallApk(apkUrl, remoteBuild)
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        Toast.makeText(this, "New build $remoteBuild found, but no APK asset attached.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    tvUpdateLabel.text = "Update"
                    Toast.makeText(
                        this,
                        "Update check failed (v$currentVersion build $currentBuild): ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun downloadAndInstallApk(apkUrl: String, remoteBuild: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Enable 'Install unknown apps' for StreamSync, then tap Update again.", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            return
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val progressText = TextView(this).apply {
            text = "0% (0.0 MB)"
            setPadding(0, 0, 0, 12)
        }
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 24)
            addView(progressText)
            addView(progressBar)
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading build $remoteBuild")
            .setView(dialogLayout)
            .setCancelable(false)
            .show()

        btnUpdate.isEnabled = false
        tvUpdateLabel.text = "0%"

        networkExecutor.execute {
            try {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "streamsync-build-$remoteBuild.apk")

                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val totalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    conn.contentLengthLong
                } else {
                    conn.contentLength.toLong()
                }

                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val pct = if (totalBytes > 0) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                val mbDone = downloaded / (1024f * 1024f)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024f * 1024f) else 0f
                                runOnUiThread {
                                    if (pct >= 0) {
                                        progressBar.isIndeterminate = false
                                        progressBar.progress = pct
                                        progressText.text = String.format("%d%% (%.1f / %.1f MB)", pct, mbDone, mbTotal)
                                        tvUpdateLabel.text = "$pct%"
                                    } else {
                                        progressBar.isIndeterminate = true
                                        progressText.text = String.format("Downloading... %.1f MB", mbDone)
                                    }
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    progressDialog.dismiss()
                    btnUpdate.isEnabled = true
                    tvUpdateLabel.text = "Update"
                    installApkFile(apkFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    btnUpdate.isEnabled = true
                    tvUpdateLabel.text = "Update"
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApkFile(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        geckoBrowser.close()
        streamSniffer.shutdown()
        nativePlayerController.release()
        networkExecutor.shutdownNow()
    }
}
