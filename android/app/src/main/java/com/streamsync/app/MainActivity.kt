package com.streamsync.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
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
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var roomWebView: WebView
    private lateinit var browserWebView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnTabRoom: Button
    private lateinit var btnTabBrowser: Button
    private lateinit var btnStreams: Button

    private val detectedStreams = ConcurrentHashMap<String, DetectedStream>()
    private var showingRoom = true
    private var currentBrowserUrl = "https://www.google.com"

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
        val btnGo: Button = findViewById(R.id.btnGo)

        val savedServer = prefs.getString("server_url", "https://streamsync.onrender.com")
            ?: "https://streamsync.onrender.com"

        configureWebView(roomWebView, isMediaBrowser = false)
        configureWebView(browserWebView, isMediaBrowser = true)

        etUrl.setText(savedServer)
        roomWebView.loadUrl(savedServer)
        browserWebView.loadUrl(currentBrowserUrl)

        btnTabRoom.setOnClickListener { switchTab(showRoom = true) }
        btnTabBrowser.setOnClickListener { switchTab(showRoom = false) }
        btnStreams.setOnClickListener { showDetectedStreamsDialog() }

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
                val base = (roomWebView.url ?: prefs.getString("server_url", "https://streamsync.onrender.com"))!!
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
}
