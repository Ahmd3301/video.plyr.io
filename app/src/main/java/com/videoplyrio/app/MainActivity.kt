package com.videoplyrio.app

import android.content.Intent
import android.content.res.Configuration
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var mainWebView: WebView
    private lateinit var containerLayout: FrameLayout
    private var extractorWebView: WebView? = null
    private lateinit var assetLoader: WebViewAssetLoader

    private var pendingPlaylistData: String? = null
    private var isPageLoaded = false

    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var iframePollingRunnable: Runnable? = null
    private val timeoutRunnable = Runnable {
        if (isExtracting) {
            runOnUiThread {
                stopExtractionWithError("انتهت مهلة استخراج الرابط")
            }
        }
    }

    private val networkExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var isExtracting = false
    private var lastExtractionUrl: String? = null

    private var extractionAttemptCount = 0
    private var lastAttemptedUrl: String? = null

    companion object {
        private const val POLL_INTERVAL_MS = 50L

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

        private val BLOCKED_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
            ".css", ".woff", ".woff2", ".ttf", ".eot",
            ".ico", ".pdf"
        )

        private val BLOCKED_DOMAINS = listOf(
            "google-analytics", "doubleclick", "googlesyndication",
            "facebook.net", "connect.facebook", "twitter.com/i/jot",
            "analytics", "clickmagick", "popunder", "popads",
            "trafficjunky", "exoclick", "adnxs", "onclick",
            "mgid.com", "taboola", "outbrain"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFullscreenFlags()

        containerLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(containerLayout)

        mainWebView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled   = false
        }
        containerLayout.addView(mainWebView)

        setupMainWebView()
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAffinity() }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterAndroidPipMode()
    }

    override fun onDestroy() {
        cleanupHandler()
        stopExtraction()
        networkExecutor.shutdownNow()
        mainWebView.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    private fun applyFullscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupMainWebView() {
        mainWebView.settings.apply {
            javaScriptEnabled          = true
            domStorageEnabled          = true
            allowFileAccess            = true
            allowContentAccess         = true
            mixedContentMode           = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            @Suppress("SetJavaScriptEnabled")
            allowFileAccessFromFileURLs    = true
            allowUniversalAccessFromFileURLs = true
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        mainWebView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        mainWebView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        mainWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val response = assetLoader.shouldInterceptRequest(url)
                if (response != null) return response

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                pendingPlaylistData?.let {
                    executePlaylistLoad(it)
                    pendingPlaylistData = null
                }
            }
        }

        val hasDeepLink = intent?.data != null
        val targetUrl = if (hasDeepLink)
            "https://appassets.androidplatform.net/assets/player.html?deeplink=true"
        else
            "https://appassets.androidplatform.net/assets/player.html"

        mainWebView.loadUrl(targetUrl)
    }

    private fun handleIntent(intent: Intent?) {
        val dataUri = intent?.data ?: return
        if (Intent.ACTION_VIEW != intent.action) return

        if (dataUri.scheme == "videoplyrio" && dataUri.host == "open") {
            val base64Data = dataUri.getQueryParameter("data")
            if (!base64Data.isNullOrEmpty()) {
                if (isPageLoaded) executePlaylistLoad(base64Data)
                else pendingPlaylistData = base64Data
            }
        }
    }

    private fun executePlaylistLoad(base64Data: String) {
        val clean = base64Data.replace("\\s".toRegex(), "")
        mainWebView.evaluateJavascript("window.loadBase64Playlist('$clean')", null)
    }

    // ============================================================
    // Native Packer Unpacker Logic (نظام التفكيك التدفي السريع)
    // ============================================================

    fun startNativePackerExtraction(targetUrl: String) {
        if (isExtracting) stopExtraction()
        isExtracting = true

        runOnUiThread {
            mainWebView.evaluateJavascript("window.showLoadingLoop()", null)
        }

        networkExecutor.execute {
            try {
                val urlConnection = URL(targetUrl).openConnection() as HttpURLConnection
                urlConnection.apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", DESKTOP_UA)
                }

                val responseCode = urlConnection.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                    val sb = java.lang.StringBuilder()
                    val buffer = CharArray(4096)
                    var bytesRead: Int
                    var matchResult: MatchResult? = null

                    val packerPattern = Regex(
                        "eval\\(function\\(p,a,c,k,e,[dr]\\)\\{.*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)\\)\\)",
                        RegexOption.DOT_MATCHES_ALL
                    )

                    while (reader.read(buffer).also { bytesRead = it } != -1) {
                        sb.append(buffer, 0, bytesRead)
                        matchResult = packerPattern.find(sb.toString())
                        if (matchResult != null) {
                            break
                        }
                    }

                    if (matchResult != null) {
                        val p = matchResult.groupValues[1]
                        val a = matchResult.groupValues[2].toInt()
                        val c = matchResult.groupValues[3].toInt()
                        val k = matchResult.groupValues[4].split("|")

                        val unpackedCode = nativeUnpack(p, a, c, k)

                        val streamRegex = Regex("file:\"([^\"]+)\"")
                        val streamMatch = streamRegex.find(unpackedCode)

                        val streamUrl = streamMatch?.groupValues?.get(1)
                            ?: Regex("src\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']").find(unpackedCode)?.groupValues?.get(1)

                        if (!streamUrl.isNullOrEmpty() && isExtracting) {
                            runOnUiThread {
                                stopExtraction()
                                mainWebView.evaluateJavascript("window.playExtractedUrl('${streamUrl.replace("'", "\\'")}')", null)
                            }
                        } else {
                            throw Exception("لم يتم العثور على رابط البث في الكود المفكك")
                        }
                    } else {
                        throw Exception("لم يتم العثور على كود مشفر بنمط Packer")
                    }
                } else {
                    throw Exception("فشل جلب الصفحة: HTTP $responseCode")
                }
            } catch (e: Exception) {
                if (isExtracting) {
                    runOnUiThread {
                        stopExtractionWithError("خطأ أثناء الاستخراج السريع: ${e.message}")
                    }
                }
            }
        }
    }

    private fun nativeUnpack(p: String, a: Int, c: Int, k: List<String>): String {
        var unpacked = p
        for (i in c - 1 downTo 0) {
            if (i < k.size && k[i].isNotEmpty()) {
                val word36 = java.lang.Integer.toString(i, 36)
                val safeReplacement = java.util.regex.Matcher.quoteReplacement(k[i])
                unpacked = unpacked.replace(Regex("\\b$word36\\b"), safeReplacement)
            }
        }
        return unpacked
    }

    // ============================================================
    // WebView Extraction (تصفح خلفي محسن وخارق لـ فاصل إعلاني)
    // ============================================================

    fun startBackgroundExtraction(mainUrl: String) {
        if (isExtracting) stopExtraction()
        isExtracting = true

        if (mainUrl == lastAttemptedUrl) {
            extractionAttemptCount++
        } else {
            extractionAttemptCount = 1
            lastAttemptedUrl = mainUrl
        }

        val currentTimeoutMs = when (extractionAttemptCount) {
            1 -> 5000L
            2 -> 10000L
            else -> 15000L
        }

        runOnUiThread {
            mainWebView.evaluateJavascript("window.showLoadingLoop()", null)
        }

        networkExecutor.execute {
            try {
                val urlConnection = URL(mainUrl).openConnection() as HttpURLConnection
                urlConnection.apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", DESKTOP_UA)
                }

                val responseCode = urlConnection.responseCode
                var playerUrl: String? = null
                if (responseCode == 200) {
                    val html = urlConnection.inputStream.bufferedReader().use { it.readText() }
                    val playerRegex = Regex("player_iframe.*?location.*?['\"](https?://[^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    playerUrl = playerRegex.find(html)?.groupValues?.get(1)
                }

                val targetUrl = playerUrl ?: mainUrl

                runOnUiThread {
                    if (!isExtracting) return@runOnUiThread
                    extractorWebView = buildExtractorWebView()

                    val layoutParams = FrameLayout.LayoutParams(1, 1).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                    containerLayout.addView(extractorWebView, layoutParams)

                    val headers = HashMap<String, String>()
                    headers["Referer"] = deriveSiteReferer(mainUrl)

                    extractorWebView?.webViewClient = buildStage2Client()
                    extractorWebView?.loadUrl(targetUrl, headers)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isExtracting) return@runOnUiThread
                    extractorWebView = buildExtractorWebView()
                    val layoutParams = FrameLayout.LayoutParams(1, 1).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                    containerLayout.addView(extractorWebView, layoutParams)
                    extractorWebView?.webViewClient = buildStage2Client()
                    extractorWebView?.loadUrl(mainUrl)
                }
            }
        }

        handler.postDelayed(timeoutRunnable, currentTimeoutMs)
    }

    private fun buildExtractorWebView(): WebView {
        return WebView(this@MainActivity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString   = DESKTOP_UA
                mixedContentMode  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = false
                blockNetworkImage        = true
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress >= 30 && isExtracting) {
                        startPollingForM3u8()
                    }
                }
            }
        }
    }

    private fun deriveSiteReferer(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            "${uri.scheme}://${uri.host}/"
        } catch (e: Exception) {
            "https://faselhd.center/"
        }
    }

    private fun shouldBlockResource(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return BLOCKED_EXTENSIONS.any { lower.contains(it) } ||
               BLOCKED_DOMAINS.any    { lower.contains(it) }
    }

    private fun makeEmptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
    }

    private fun buildStage2Client(): WebViewClient {
        return object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: ""

                if (urlStr.contains(".m3u8", ignoreCase = true) && isExtracting) {
                    if (!urlStr.contains("seg") && !urlStr.contains("chunk")) {
                        handler.post { onM3u8Found(urlStr) }
                    }
                }

                if (shouldBlockResource(urlStr)) return makeEmptyResponse()
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                startPollingForM3u8()
            }
        }
    }

    private fun startPollingForM3u8() {
        if (pollingRunnable != null) return

        val js = """
            (function() {
                var btns = document.querySelectorAll('button[data-url*=".m3u8"], button.hd_btn');
                for (var i = 0; i < btns.length; i++) {
                    var u = btns[i].getAttribute('data-url') || btns[i].getAttribute('data-src');
                    if (u && u.indexOf('.m3u8') !== -1) return u;
                }
                var src = document.querySelector('video source[src*=".m3u8"]');
                if (src) return src.getAttribute('src');
                var vid = document.querySelector('video[src*=".m3u8"]');
                if (vid) return vid.getAttribute('src');
                var scripts = document.querySelectorAll('script:not([src])');
                for (var j = 0; j < scripts.length; j++) {
                    var txt = scripts[j].textContent;
                    var match = txt.match(/["'](https?:\/\/[^"']+\.m3u8[^"']*?)["']/);
                    if (match) return match[1];
                }
                return null;
            })()
        """.trimIndent()

        pollingRunnable = object : Runnable {
            override fun run() {
                if (!isExtracting) return
                runOnUiThread {
                    extractorWebView?.evaluateJavascript(js) { result ->
                        val m3u8 = result?.trim()?.removeSurrounding("\"")
                        if (!m3u8.isNullOrEmpty() && m3u8 != "null") {
                            onM3u8Found(m3u8)
                        } else {
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        handler.post(pollingRunnable!!)
    }

    private fun onM3u8Found(url: String) {
        if (!isExtracting) return
        runOnUiThread {
            stopExtraction()
            mainWebView.evaluateJavascript("window.playExtractedUrl('${url.replace("'", "\\'")}')", null)
        }
    }

    private fun stopExtractionWithError(msg: String) {
        stopExtraction()
        val safeMsg = msg.replace("'", "\\'")
        runOnUiThread {
            mainWebView.evaluateJavascript("window.showExtractionError('$safeMsg')", null)
        }
    }

    private fun stopExtraction() {
        isExtracting = false
        handler.removeCallbacks(timeoutRunnable)
        stopIframePolling()
        pollingRunnable?.let { handler.removeCallbacks(it) }
        pollingRunnable = null
        runOnUiThread {
            extractorWebView?.let {
                containerLayout.removeView(it)
                it.stopLoading()
                it.destroy()
            }
            extractorWebView = null
        }
    }

    private fun stopIframePolling() {
        iframePollingRunnable?.let { handler.removeCallbacks(it) }
        iframePollingRunnable = null
    }

    private fun cleanupHandler() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        runOnUiThread {
            val js = if (isInPictureInPictureMode)
                "document.querySelector('.plyr')?.classList.add('plyr--pip-active')"
            else
                "document.querySelector('.plyr')?.classList.remove('plyr--pip-active')"
            mainWebView.evaluateJavascript(js, null)
        }
    }

    fun enterAndroidPipMode() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                try { enterPictureInPictureMode(params) } catch (e: Exception) {  }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                try { enterPictureInPictureMode() } catch (e: Exception) {  }
            }
        }
    }
}

class WebAppInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun closeApp() {
        activity.finishAffinity()
    }

    @JavascriptInterface
    fun triggerExtraction(url: String) {
        activity.startBackgroundExtraction(url)
    }

    @JavascriptInterface
    fun triggerPackerExtraction(url: String) {
        activity.startNativePackerExtraction(url)
    }

    @JavascriptInterface
    fun enterPip() {
        activity.enterAndroidPipMode()
    }
}
