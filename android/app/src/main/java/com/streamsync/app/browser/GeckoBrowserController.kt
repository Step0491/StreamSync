package com.streamsync.app.browser

import android.content.Context
import com.streamsync.app.adblock.AdBlockEngine
import com.streamsync.app.extractor.StreamSniffer
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * Manages Mozilla GeckoView (Firefox engine) + our built-in WebExtension
 * (streamsync_sniffer) with real webRequestBlocking + webRequest.filterResponseData
 * for Video DownloadHelper-grade stream extraction and native ad/tracker blocking.
 */
class GeckoBrowserController(
    context: Context,
    private val geckoView: GeckoView,
    private val adBlockEngine: AdBlockEngine,
    private val streamSniffer: StreamSniffer,
    private val onLocationChanged: (url: String) -> Unit,
    private val onTitleChanged: (title: String) -> Unit,
    private val onShieldStatsChanged: () -> Unit
) {
    private val runtime: GeckoRuntime = getOrCreateRuntime(context.applicationContext)
    val session: GeckoSession

    @Volatile
    private var nativePort: WebExtension.Port? = null

    @Volatile
    var currentUrl: String = "https://www.google.com"
        private set

    @Volatile
    var currentTitle: String = ""
        private set

    @Volatile
    var canGoBack: Boolean = false
        private set

    @Volatile
    var canGoForward: Boolean = false
        private set

    init {
        val sessionSettings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .build()

        session = GeckoSession(sessionSettings)

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank() && url != "about:blank") {
                    currentUrl = url
                    streamSniffer.activePageUrl = url
                    syncShieldStateToExtension(url)
                    onLocationChanged(url)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoBrowserController.canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@GeckoBrowserController.canGoForward = canGoForward
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val target = request.uri
                if (adBlockEngine.shouldBlockNavigationOrPopup(target, currentUrl, isPopupWindow = false)) {
                    onShieldStatsChanged()
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                // Block popunders/ad tabs; allow legitimate same-site links in the current tab
                if (adBlockEngine.shouldBlockNavigationOrPopup(uri, currentUrl, isPopupWindow = true)) {
                    onShieldStatsChanged()
                    return null
                }
                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    session.loadUri(uri)
                }
                return null
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    currentTitle = title
                    streamSniffer.activePageTitle = title
                    onTitleChanged(title)
                }
            }
        }

        session.open(runtime)
        geckoView.setSession(session)

        installSnifferExtension()
    }

    private fun installSnifferExtension() {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/extensions/streamsync_sniffer/",
                "streamsync-sniffer@streamsync.app"
            )
            .accept(
                { ext ->
                    ext?.setMessageDelegate(
                        object : WebExtension.MessageDelegate {
                            override fun onConnect(port: WebExtension.Port) {
                                nativePort = port
                                port.setDelegate(object : WebExtension.PortDelegate {
                                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                        if (message is JSONObject) {
                                            handleExtensionMessage(message)
                                        }
                                    }

                                    override fun onDisconnect(port: WebExtension.Port) {
                                        if (nativePort === port) nativePort = null
                                    }
                                })
                                syncShieldStateToExtension(currentUrl)
                            }
                        },
                        "streamsync_bridge"
                    )
                },
                { _ -> }
            )
    }

    private fun handleExtensionMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "blocked_request", "blocked_popup" -> {
                adBlockEngine.recordJsBlockedPopup()
                onShieldStatsChanged()
            }
            "manifest_body" -> {
                val url = msg.optString("url", "")
                val ct = msg.optString("contentType", "")
                val body = msg.optString("body", "")
                val headers = parseHeadersObj(msg.optJSONObject("headers"))
                if (url.isNotBlank() && body.isNotBlank()) {
                    streamSniffer.ingestGeckoManifestBody(url, ct, body, headers)
                }
            }
            "media_response" -> {
                val url = msg.optString("url", "")
                val ct = msg.optString("contentType", "")
                val cr = msg.optString("contentRange", "")
                val cl = msg.optString("contentLength", "")
                val headB64 = msg.optString("headBase64", "")
                val headers = parseHeadersObj(msg.optJSONObject("headers"))
                if (url.isNotBlank()) {
                    streamSniffer.ingestGeckoMediaResponse(url, ct, cr, cl, headB64, headers)
                }
            }
            "structured_stream" -> {
                streamSniffer.jsBridge.onStructuredStream(
                    url = msg.optString("url", ""),
                    kindStr = msg.optString("kind", "COMBINED"),
                    container = msg.optString("container", "mp4"),
                    label = msg.optString("label", "Stream"),
                    width = msg.optInt("width", 0),
                    height = msg.optInt("height", 0),
                    fps = msg.optDouble("fps", 0.0),
                    durationSec = msg.optDouble("durationSec", 0.0),
                    vcodec = msg.optString("vcodec", ""),
                    acodec = msg.optString("acodec", ""),
                    bitrateKbps = msg.optInt("bitrateKbps", 0),
                    contentLength = msg.optLong("contentLength", 0L),
                    language = msg.optString("language", ""),
                    isHls = msg.optBoolean("isHls", false),
                    isDash = msg.optBoolean("isDash", false),
                    pageUrl = msg.optString("pageUrl", currentUrl),
                    pageTitle = msg.optString("pageTitle", currentTitle)
                )
            }
            "page_video_meta" -> {
                val dur = msg.optDouble("durationSec", 0.0)
                val w = msg.optInt("width", 0)
                val h = msg.optInt("height", 0)
                streamSniffer.enrichWithDomVideoMetadata(dur, w, h)
            }
        }
    }

    private fun parseHeadersObj(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (k.isNotBlank() && v.isNotBlank()) {
                map[k] = v
            }
        }
        return map
    }

    fun syncShieldStateToExtension(pageUrl: String) {
        val enabled = adBlockEngine.isShieldEnabledForUrl(pageUrl)
        try {
            nativePort?.postMessage(
                JSONObject().apply {
                    put("type", "set_shield")
                    put("enabled", enabled)
                }
            )
        } catch (_: Exception) {
        }
    }

    fun loadUrl(url: String) {
        currentUrl = url
        session.loadUri(url)
    }

    fun reload() {
        session.reload()
    }

    fun goBack() {
        if (canGoBack) session.goBack()
    }

    fun close() {
        geckoView.releaseSession()
        session.close()
    }

    companion object {
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        private fun getOrCreateRuntime(appContext: Context): GeckoRuntime {
            return sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: run {
                    val cbSettings = ContentBlocking.Settings.Builder()
                        .antiTracking(
                            ContentBlocking.AntiTracking.AD or
                                ContentBlocking.AntiTracking.ANALYTIC or
                                ContentBlocking.AntiTracking.SOCIAL or
                                ContentBlocking.AntiTracking.CRYPTOMINING
                        )
                        .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                        .build()

                    val runtimeSettings = GeckoRuntimeSettings.Builder()
                        .contentBlocking(cbSettings)
                        .automaticFontSizeAdjustment(true)
                        .build()

                    GeckoRuntime.create(appContext, runtimeSettings).also { sharedRuntime = it }
                }
            }
        }
    }
}
