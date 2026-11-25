package com.streamsync.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var roomWebView: WebView
    private lateinit var browserWebView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnTabRoom: Button
    private lateinit var btnTabBrowser: Button
    private lateinit var btnStreams: Button
    private lateinit var btnUpdate: Button

    private val detectedStreams = ConcurrentHashMap<String, DetectedStream>()
    private var showingRoom = true
    private var currentBrowserUrl = "https://www.google.com"
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private val prefs by lazy { getSharedPreferences("streamsync_prefs", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        roomWebView = findViewById(R.id.roomWebView)
        browserWebView = findViewById(R.id.browserWebView)
        etUrl = findViewById(R.id.etUrl)
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

        configureWebView(roomWebView, isMediaBrowser = false)
        configureWebView(browserWebView, isMediaBrowser = true)

        etUrl.setText(savedServer)
        roomWebView.loadUrl(savedServer)
        browserWebView.loadUrl(currentBrowserUrl)

        btnTabRoom.setOnClickListener { switchTab(showRoom = true) }
        btnTabBrowser.setOnClickListener { switchTab(showRoom = false) }
        btnStreams.setOnClickListener { showDetectedStreamsDialog() }
        btnUpdate.setOnClickListener { checkForUpdates() }

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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, isMediaBrowser: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (isMediaBrowser && url != null) {
                    currentBrowserUrl = url
                    if (!showingRoom) etUrl.setText(url)
                    detectedStreams.clear()
                    updateStreamsButton()
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (isMediaBrowser && request != null) {
                    inspectUrl(request.url.toString())
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                if (isMediaBrowser && url != null) {
                    inspectUrl(url)
                }
                super.onLoadResource(view, url)
            }
        }
    }

    private fun inspectUrl(url: String) {
        val stream = MediaDetector.classify(url) ?: return
        if (detectedStreams.putIfAbsent(stream.url, stream) == null) {
            runOnUiThread { updateStreamsButton() }
        }
    }

    private fun updateStreamsButton() {
        btnStreams.text = "Streams (${detectedStreams.size})"
    }

    private fun switchTab(showRoom: Boolean) {
        showingRoom = showRoom
        roomWebView.visibility = if (showRoom) View.VISIBLE else View.GONE
        browserWebView.visibility = if (showRoom) View.GONE else View.VISIBLE
        etUrl.setText(
            if (showRoom) (roomWebView.url ?: prefs.getString("server_url", ""))
            else currentBrowserUrl
        )
        updateTabStyles()
    }

    private fun updateTabStyles() {
        btnTabRoom.alpha = if (showingRoom) 1.0f else 0.55f
        btnTabBrowser.alpha = if (!showingRoom) 1.0f else 0.55f
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
            browserWebView.loadUrl(url)
        }
    }

    private fun sendStreamToRoom(videoUrl: String, audioUrl: String? = null) {
        val pageTitle = browserWebView.title ?: "Detected Stream"
        switchTab(showRoom = true)

        val jsVideo = JSONObject.quote(videoUrl)
        val jsAudio = JSONObject.quote(audioUrl ?: "")
        val jsTitle = JSONObject.quote(pageTitle)

        roomWebView.evaluateJavascript(
            "if (window.loadFromNativeApp) { window.loadFromNativeApp($jsVideo, $jsAudio, $jsTitle); true; } else { false; }"
        ) { result ->
            if (result != "true") {
                val base = (roomWebView.url ?: prefs.getString("server_url", "https://streamsync-pj98.onrender.com/"))!!
                val uri = Uri.parse(base).buildUpon()
                    .appendQueryParameter("video", videoUrl)
                    .appendQueryParameter("title", pageTitle)
                if (!audioUrl.isNullOrEmpty()) {
                    uri.appendQueryParameter("audio", audioUrl)
                }
                roomWebView.loadUrl(uri.build().toString())
            }
        }
        Toast.makeText(this, "Stream loaded in StreamSync Room", Toast.LENGTH_SHORT).show()
    }

    private fun showDetectedStreamsDialog() {
        val items = detectedStreams.values.toList()
        if (items.isEmpty()) {
            Toast.makeText(
                this,
                "No streams detected yet. Open a page in the Browser tab and press play.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val videos = items.filter { it.kind == StreamKind.VIDEO || it.kind == StreamKind.COMBINED }
        val audios = items.filter { it.kind == StreamKind.AUDIO }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 12)
        }

        if (videos.isNotEmpty() && audios.isNotEmpty()) {
            val pairHeader = TextView(this).apply {
                text = "Pair Separated Video + Audio"
                textSize = 15f
                setPadding(0, 0, 0, 8)
            }
            val videoSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    videos.map { "${it.label}: ${it.url.take(48)}" }
                )
            }
            val audioSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    audios.map { "${it.label}: ${it.url.take(48)}" }
                )
            }
            val pairBtn = Button(this).apply {
                text = "Pair & Play in Room"
            }
            container.addView(pairHeader)
            container.addView(videoSpinner)
            container.addView(audioSpinner)
            container.addView(pairBtn)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Detected Streams (${items.size})")
                .setView(container)
                .setItems(items.map { "${it.label}\n${it.url.take(70)}" }.toTypedArray()) { _, which ->
                    sendStreamToRoom(items[which].url)
                }
                .setNegativeButton("Close", null)
                .show()

            pairBtn.setOnClickListener {
                val v = videos[videoSpinner.selectedItemPosition]
                val a = audios[audioSpinner.selectedItemPosition]
                dialog.dismiss()
                sendStreamToRoom(v.url, a.url)
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Detected Streams (${items.size})")
                .setItems(items.map { "${it.label}\n${it.url.take(70)}" }.toTypedArray()) { _, which ->
                    sendStreamToRoom(items[which].url)
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun checkForUpdates() {
        btnUpdate.isEnabled = false
        btnUpdate.text = "Checking..."
        val currentBuild = BuildConfig.VERSION_CODE
        val currentVersion = BuildConfig.VERSION_NAME

        networkExecutor.execute {
            try {
                val conn = (URL("https://api.github.com/repos/Step0491/StreamSync/releases/latest")
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
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val remoteBuild = Regex("""(\d+)$""").find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: throw IllegalStateException("Invalid release tag: $tagName")

                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

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
                            .setMessage("Installed: v$currentVersion (build $currentBuild)\nNew version: v0.1.$remoteBuild (build $remoteBuild)")
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
        networkExecutor.shutdownNow()
    }
}
