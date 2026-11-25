package com.streamsync.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.streamsync.app.adblock.BrowserShieldScripts
import com.streamsync.app.adblock.FilterRuleEngine
import com.streamsync.app.extractor.DetectedStream
import com.streamsync.app.extractor.StreamSniffer
import com.streamsync.app.player.NativeRoomPlayerController
import com.streamsync.app.ui.StreamSelectorSheet
import org.json.JSONObject
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
    private lateinit var browserWebView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnShield: Button
    private lateinit var btnTabRoom: Button
    private lateinit var btnTabBrowser: Button
    private lateinit var btnStreams: Button
    private lateinit var btnUpdate: Button

    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var streamSniffer: StreamSniffer
    private lateinit var nativePlayerController: NativeRoomPlayerController

    @Volatile
    private var latestStreams: List<DetectedStream> = emptyList()
    private var showingRoom = true
    private var currentBrowserUrl = "https://www.google.com"
    private var lastClearedNavHostPath = ""
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
        browserWebView = findViewById(R.id.browserWebView)
        etUrl = findViewById(R.id.etUrl)
        btnShield = findViewById(R.id.btnShield)
        btnTabRoom = findViewById(R.id.btnTabRoom)
        btnTabBrowser = findViewById(R.id.btnTabBrowser)
        btnStreams = findViewById(R.id.btnStreams)
        btnUpdate = findViewById(R.id.btnUpdate)
        val btnGo: Button = findViewById(R.id.btnGo)

        val defaultServer = "https://streamsync-pj98.onrender.com/"
        var savedServer = prefs.getString("server_url", defaultServer) ?: defaultServer
        if (savedServer == "https://streamsync.onrender.com" || savedServer == "https://streamsync.onrender.com/") {
            savedServer = defaultServer
            prefs.edit().putString("server_url", defaultServer).apply()
        }

        // Initialize 3-Layer AdBlock Engine + Remote Filter Refresh
        adBlockEngine = AdBlockEngine(applicationContext, prefs)
        adBlockEngine.refreshRemoteFiltersAsync(networkExecutor)

        val defaultUa = WebSettings.getDefaultUserAgent(this)

        // Initialize VDH-grade Multi-Signal Stream Sniffer & Manifest Parser with real WebView User-Agent
        streamSniffer = StreamSniffer(
            defaultUserAgent = defaultUa,
            onStreamsUpdated = { sorted ->
                latestStreams = sorted
                runOnUiThread { updateStreamsButton() }
            },
            onPopupBlockedByJs = {
                adBlockEngine.recordJsBlockedPopup()
                runOnUiThread { updateShieldButton() }
            }
        )

        nativePlayerController = NativeRoomPlayerController(
            context = this,
            playerContainer = nativePlayerContainer,
            playerView = nativePlayerView,
            tvPlayerTitle = tvNativePlayerTitle,
            tvPlayerBadge = tvNativePlayerBadge,
            roomWebView = roomWebView,
            defaultUserAgent = defaultUa,
            onFullscreenToggle = { fullscreen -> applyFullscreenMode(fullscreen) }
        )

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browserWebView, true)

        configureRoomWebView()
        configureBrowserWebView()

        etUrl.setText(savedServer)
        roomWebView.loadUrl(savedServer)
        browserWebView.loadUrl(currentBrowserUrl)

        btnTabRoom.setOnClickListener { switchTab(showRoom = true) }
        btnTabBrowser.setOnClickListener { switchTab(showRoom = false) }
        btnStreams.setOnClickListener { openStreamSelector() }
        btnUpdate.setOnClickListener { checkForUpdates() }

        btnShield.setOnClickListener {
            val enabledNow = adBlockEngine.toggleShieldForUrl(currentBrowserUrl)
            val host = adBlockEngine.extractHost(currentBrowserUrl) ?: "this site"
            Toast.makeText(
                this,
                if (enabledNow) "Ad/Popup Shield enabled for $host" else "Shield paused for $host",
                Toast.LENGTH_SHORT
            ).show()
            updateShieldButton()
            browserWebView.reload()
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
                val activeView = if (showingRoom) roomWebView else browserWebView
                if (activeView.canGoBack()) {
                    activeView.goBack()
                } else if (!showingRoom) {
                    switchTab(showRoom = true)
                } else {
                    finish()
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
        roomWebView.webChromeClient = WebChromeClient()
        roomWebView.webViewClient = WebViewClient()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureBrowserWebView() {
        browserWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // Enable multi-window callback so WebChromeClient.onCreateWindow intercepts & blocks ad popups
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }

        browserWebView.addJavascriptInterface(streamSniffer.jsBridge, "StreamSnifferBridge")

        browserWebView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hitResult = view?.hitTestResult
                val candidatePopupUrl = hitResult?.extra
                if (adBlockEngine.shouldBlockNavigationOrPopup(
                        targetUrl = candidatePopupUrl,
                        currentPageUrl = currentBrowserUrl,
                        isPopupWindow = true
                    )
                ) {
                    runOnUiThread { updateShieldButton() }
                    return false
                }
                if (!candidatePopupUrl.isNullOrBlank() && isUserGesture) {
                    view.loadUrl(candidatePopupUrl)
                }
                return false
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    streamSniffer.activePageTitle = title
                }
            }
        }

        browserWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                if (!request.isForMainFrame) {
                    if (adBlockEngine.shouldBlockNavigationOrPopup(targetUrl, currentBrowserUrl, isPopupWindow = false)) {
                        runOnUiThread { updateShieldButton() }
                        return true
                    }
                    return false
                }

                // Block non-HTTP scheme hijacks (intent://, market://) and known ad-redirect domains
                if (adBlockEngine.shouldBlockNavigationOrPopup(targetUrl, currentBrowserUrl, isPopupWindow = false)) {
                    runOnUiThread { updateShieldButton() }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null) {
                    currentBrowserUrl = url
                    if (!showingRoom) etUrl.setText(url)
                    streamSniffer.activePageUrl = url

                    // Reset only the per-page AdBlock counter on new site domain;
                    // NEVER wipe detectedStreams automatically on play-button redirects/reloads
                    // (matches Video DownloadHelper behavior — user clears explicitly or via Address Bar Go).
                    val navDomain = FilterRuleEngine.parseUriSafe(url)?.host?.let { FilterRuleEngine.registrableDomain(it) }.orEmpty()
                    if (navDomain.isNotEmpty() && navDomain != lastClearedNavHostPath) {
                        lastClearedNavHostPath = navDomain
                        adBlockEngine.resetPageCounter()
                    }

                    updateShieldButton()
                    injectBrowserShieldAndSniffer(view, url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    streamSniffer.activePageTitle = view?.title ?: ""
                    injectBrowserShieldAndSniffer(view, url)
                    updateShieldButton()
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    val pageSnapshot = currentBrowserUrl
                    val blockedResp = adBlockEngine.interceptRequest(request, pageSnapshot)
                    if (blockedResp != null) {
                        runOnUiThread { updateShieldButton() }
                        return blockedResp
                    }
                    streamSniffer.inspectWebRequest(
                        request = request,
                        pageUrl = pageSnapshot,
                        pageTitle = streamSniffer.activePageTitle
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun computeTopLevelNavSignature(url: String): String {
        val uri = FilterRuleEngine.parseUriSafe(url) ?: return ""
        val host = uri.host?.let { FilterRuleEngine.registrableDomain(it) } ?: return ""
        val path = uri.path.orEmpty().trimEnd('/')
        return "$host$path"
    }

    private fun injectBrowserShieldAndSniffer(view: WebView?, pageUrl: String) {
        val wv = view ?: return
        val enabled = adBlockEngine.isShieldEnabledForUrl(pageUrl)
        val js = BrowserShieldScripts.buildInjectionScript(
            shieldEnabled = enabled,
            cosmeticSelectors = adBlockEngine.cosmeticSelectors
        )
        wv.evaluateJavascript(js, null)
    }

    private fun updateStreamsButton() {
        btnStreams.text = "Streams (${latestStreams.size})"
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
        browserWebView.visibility = if (showRoom) View.GONE else View.VISIBLE
        nativePlayerController.setRoomTabVisibility(showRoom)
        etUrl.setText(
            if (showRoom) (roomWebView.url ?: prefs.getString("server_url", ""))
            else currentBrowserUrl
        )
        updateTabStyles()
        updateShieldButton()
    }

    private fun updateTabStyles() {
        btnTabRoom.alpha = if (showingRoom) 1.0f else 0.55f
        btnTabBrowser.alpha = if (!showingRoom) 1.0f else 0.55f
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
            browserWebView.loadUrl(url)
        }
    }

    private fun openStreamSelector() {
        StreamSelectorSheet.show(
            context = this,
            streams = latestStreams,
            onSelectPlayback = { videoStream, audioStream ->
                sendStreamSelectionToRoom(videoStream, audioStream)
            },
            onClearStreams = {
                streamSniffer.clearForNewTopLevelPage(currentBrowserUrl)
            },
            onExtractCurrentPage = {
                extractCurrentBrowserPageInRoom()
            }
        )
    }

    private fun extractCurrentBrowserPageInRoom() {
        val targetUrl = browserWebView.url ?: currentBrowserUrl
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
        val pageTitle = (browserWebView.title ?: videoStream.pageTitle ?: "Detected Stream").take(140)
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

        // 1. Immediately start hardware-synced playback in Native Media3 ExoPlayer with captured headers
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

        // 2. Broadcast to Watch-Party Room via roomWebView (skipping redundant re-load on local ExoPlayer)
        val headersJson = JSONObject()
        for ((k, v) in mergedHeaders) {
            if (!k.equals("Cookie", ignoreCase = true)) {
                // Never broadcast user's session cookies to other room peers; pass Referer/Origin/UA hints only
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
            put("already_loaded_native", true)
        }

        val jsVideo = JSONObject.quote(videoStream.url)
        val jsAudio = JSONObject.quote(audioStream?.url ?: "")
        val jsTitle = JSONObject.quote(pageTitle)
        val jsPayloadArg = jsPayload.toString()

        roomWebView.evaluateJavascript(
            """
            (function() {
              if (window.loadFromNativePayload) {
                window.loadFromNativePayload($jsPayloadArg);
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

    private fun checkForUpdates() {
        btnUpdate.isEnabled = false
        btnUpdate.text = "Checking..."
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
                val releases = org.json.JSONArray(body)
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
                    btnUpdate.text = "Update"
                    if (remoteBuild <= currentBuild) {
                        AlertDialog.Builder(this)
                            .setTitle("App Up to Date")
                            .setMessage("Installed: v$currentVersion (build $currentBuild)\nLatest release: build $remoteBuild")
                            .setPositiveButton("OK", null)
                            .show()
                    } else if (apkUrl != null) {
                        AlertDialog.Builder(this)
                            .setTitle("Update Available")
                            .setMessage("Installed: v$currentVersion (build $currentBuild)\nNew version: v0.2.$remoteBuild (build $remoteBuild)")
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
                    btnUpdate.text = "Update"
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

        btnUpdate.isEnabled = false
        btnUpdate.text = "Downloading..."
        Toast.makeText(this, "Downloading build $remoteBuild...", Toast.LENGTH_SHORT).show()

        networkExecutor.execute {
            try {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "streamsync-build-$remoteBuild.apk")

                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "Update"
                    installApkFile(apkFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "Update"
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
        streamSniffer.shutdown()
        nativePlayerController.release()
        networkExecutor.shutdownNow()
    }
}
