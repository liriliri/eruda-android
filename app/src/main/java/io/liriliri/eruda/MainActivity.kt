package io.liriliri.eruda

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

// https://github.com/mengkunsoft/MkBrowser
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnStart: ImageView
    private lateinit var btnGoBack: ImageView
    private lateinit var btnGoForward: ImageView
    private lateinit var favicon: ImageView
    private lateinit var manager: InputMethodManager
    private val TAG = "Eruda.MainActivity"
    var mFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileUrl: String? = null

    // Feature 1: in-memory console-log session storage (persists across page navigations).
    // Access is guarded by synchronized blocks in storeLog / getLogs.
    private val MAX_CONSOLE_ENTRIES = 500
    private val consoleLogs = mutableListOf<Map<String, String>>()

    // Feature 2: local cache for the eruda script (avoids re-downloading on each page load).
    // The explicit /eruda.js path is used so jsDelivr returns the JS directly without a redirect.
    private val ERUDA_CDN_URL = "https://cdn.jsdelivr.net/npm/eruda/eruda.js"
    private val CACHE_MAX_AGE_MS = 7 * 24 * 3600 * 1000L  // 7 days
    private val erudaScriptCacheFile: File by lazy { File(filesDir, "eruda_cache.js") }
    // Shared OkHttpClient for eruda script downloads (reuses connection pool).
    private val httpClient = OkHttpClient()

    /** JavaScript-to-Android bridge exposed as `window.ErudaAndroid`. */
    inner class ErudaBridge {
        @android.webkit.JavascriptInterface
        fun storeLog(level: String, args: String) {
            val entry = mapOf(
                "level" to level,
                "args" to args,
                "time" to System.currentTimeMillis().toString()
            )
            synchronized(consoleLogs) {
                consoleLogs.add(entry)
                if (consoleLogs.size > MAX_CONSOLE_ENTRIES) {
                    consoleLogs.removeAt(0)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun getLogs(): String {
            val json = JSONArray()
            synchronized(consoleLogs) {
                consoleLogs.forEach { log ->
                    val obj = JSONObject()
                    obj.put("level", log["level"] ?: "log")
                    obj.put("args", log["args"] ?: "")
                    obj.put("time", log["time"] ?: "0")
                    json.put(obj)
                }
            }
            return json.toString()
        }
    }

    /** True while waiting for the user to return from the "All Files Access" settings screen. */
    private var pendingAllFilesAccess = false

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val url = pendingFileUrl
            pendingFileUrl = null
            if (granted && url != null) {
                webView.loadUrl(url)
            } else if (url != null) {
                Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        initView()
        initWebView()
    }

    private fun initView() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        textUrl = findViewById(R.id.textUrl)
        favicon = findViewById(R.id.webIcon)
        btnStart = findViewById(R.id.btnStart)
        btnGoBack = findViewById(R.id.goBack)
        btnGoForward = findViewById(R.id.goForward)

        btnStart.setOnClickListener {
            if (textUrl.hasFocus()) {
                if (manager.isActive) {
                    manager.hideSoftInputFromWindow(textUrl.applicationWindowToken, 0)
                }
                var input = textUrl.text.toString()
                if (!isHttpUrl(input) && !isFileUrl(input)) {
                    if (mayBeUrl(input)) {
                        input = "https://${input}"
                    } else {
                        try {
                            input = URLEncoder.encode(input, "utf-8")
                        } catch (e: UnsupportedEncodingException) {
                            Log.e(TAG, e.message.toString())
                        }
                        input = "https://www.google.com/search?q=${input}"
                    }
                }
                if (isFileUrl(input)) {
                    loadFileUrl(input)
                } else {
                    webView.loadUrl(input)
                }
                textUrl.clearFocus()
            } else {
                webView.reload()
            }
        }

        btnGoBack.setOnClickListener {
            webView.goBack()
        }

        btnGoForward.setOnClickListener {
            webView.goForward()
        }

        textUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                textUrl.setText(webView.url)
                textUrl.setSelection(textUrl.text.length)
                btnStart.setImageResource(R.drawable.arrow_right)
            } else {
                textUrl.setText(webView.title)
                btnStart.setImageResource(R.drawable.refresh)
            }
        }
        textUrl.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN) {
                btnStart.callOnClick()
                textUrl.clearFocus()
            }

            return@setOnKeyListener false
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun initWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                if (isHttpUrl(url) || isFileUrl(url)) {
                    return false
                }

                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    false
                } catch (e: Exception) {
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // Serve file:// URLs via the app process to bypass the WebView renderer
                // sandbox, which lacks direct access to external storage on Android 10+.
                if (isFileUrl(url)) {
                    return serveFileUrl(request.url)
                }

                // Feature 2: serve the eruda script from local cache to avoid re-downloading.
                if (url.startsWith(ERUDA_CDN_URL)) {
                    return serveCachedErudaScript()
                }

                if (request.isForMainFrame) {
                    if (!isHttpUrl(url)) {
                        return null
                    }
                    Log.i(TAG, "Loading url: $url")

                    var headers = request.requestHeaders.toHeaders()
                    val contentType = headers["content-type"]
                    if (contentType == "application/x-www-form-urlencoded") {
                        return null
                    }
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (cookie != null) {
                        headers = (headers.toMap() + Pair("cookie", cookie)).toHeaders()
                    }
                    Log.i(TAG, "Intercept url: $url")
                    Log.i(TAG, "Request headers: ${headers.toMap()}")

                    val client = OkHttpClient.Builder().followRedirects(false).build()
                    val req = Request.Builder()
                        .url(url)
                        .headers(headers)
                        .build()

                    return try {
                        val response = client.newCall(req).execute()
                        if (response.headers["content-security-policy"] == null) {
                            return null
                        }
                        val resHeaders =
                            response.headers.toMap().filter { it.key != "content-security-policy" }
                        Log.i(TAG, "Response headers: $resHeaders")

                        return WebResourceResponse(
                            "text/html",
                            response.header("content-encoding", "utf-8"),
                            response.code,
                            "ok",
                            resHeaders,
                            response.body?.byteStream()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, e.message.toString())
                        null
                    }
                }

                return null
            }

            override fun onPageStarted(view: WebView?, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                setTextUrl("Loading...")
                this@MainActivity.favicon.setImageResource(R.drawable.tool)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                progressBar.visibility = View.INVISIBLE
                title = view.title
                setTextUrl(view.title)

                val script = """
                    (function () {
                        if (window.eruda) return;
                        var define;
                        if (window.define) {
                            define = window.define;
                            window.define = null;
                        }
                        var script = document.createElement('script'); 
                        script.src = 'https://cdn.jsdelivr.net/npm/eruda/eruda.js'; 
                        document.body.appendChild(script); 
                        script.onload = function () { 
                            eruda.init();
                            if (define) {
                                window.define = define;
                            }
                            if (window.ErudaAndroid) {
                                // Capture eruda's already-hooked console methods before our wrapping.
                                var _eruda = {};
                                ['log','warn','error','info','debug'].forEach(function(lvl) {
                                    _eruda[lvl] = console[lvl].bind(console);
                                });
                                // Wrap each method so new logs are forwarded to Android storage.
                                ['log','warn','error','info','debug'].forEach(function(lvl) {
                                    (function(l, orig) {
                                        console[l] = function() {
                                            orig.apply(console, arguments);
                                            try {
                                                var msg = Array.prototype.slice.call(arguments).map(function(a) {
                                                    try { return typeof a === 'string' ? a : JSON.stringify(a); }
                                                    catch(e) { return String(a); }
                                                }).join(' ');
                                                window.ErudaAndroid.storeLog(l, msg);
                                            } catch(e) {}
                                        };
                                    })(lvl, _eruda[lvl]);
                                });
                                // Replay persisted logs from previous pages directly into eruda
                                // via the pre-wrap console references (_eruda) so they appear in
                                // the UI without going through our persistence wrapper, which
                                // prevents duplicate entries on subsequent page reloads.
                                // '\u23f0' (⏰) visually marks replayed historical entries.
                                try {
                                    var stored = JSON.parse(window.ErudaAndroid.getLogs() || '[]');
                                    stored.forEach(function(entry) {
                                        var lvl = entry.level;
                                        if (_eruda[lvl]) {
                                            _eruda[lvl](
                                                '\u23f0 ' +
                                                new Date(parseInt(entry.time, 10)).toLocaleTimeString() +
                                                ' ' + entry.args
                                            );
                                        }
                                    });
                                } catch(e) {}
                            }
                        }
                    })();
                """
                webView.evaluateJavascript(script) {}
            }
        }

        val selectFileLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(
                            result.resultCode,
                            result.data
                        )
                    )
                    mFilePathCallback = null
                }
            }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)

                progressBar.progress = newProgress
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                super.onReceivedIcon(view, icon)

                favicon.setImageBitmap(icon)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(null)
                    mFilePathCallback = null
                }
                mFilePathCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                try {
                    selectFileLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    mFilePathCallback = null
                    return false
                }
                return true
            }
        }
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true

        // Register the Android bridge so JavaScript can call window.ErudaAndroid.*
        webView.addJavascriptInterface(ErudaBridge(), "ErudaAndroid")

        if (resources.getString(R.string.mode) == "night") {
            // https://stackoverflow.com/questions/57449900/letting-webview-on-android-work-with-prefers-color-scheme-dark
            val supportForceDarkStrategy =
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
            val supportForceDark = WebViewFeature.isFeatureSupported(
                WebViewFeature.FORCE_DARK
            )
            if (supportForceDarkStrategy && supportForceDark) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        }

        webView.loadUrl("https://github.com/liriliri/eruda")
    }

    private fun serveFileUrl(uri: android.net.Uri): WebResourceResponse? {
        return try {
            val path = uri.path ?: return null
            // Canonicalize to resolve '..' sequences and prevent path traversal.
            val file = java.io.File(path).canonicalFile
            if (!file.exists() || !file.canRead()) return null
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "xml" -> "text/xml"
                "txt" -> "text/plain"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                else -> android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
            WebResourceResponse(mimeType, "utf-8", java.io.FileInputStream(file))
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file URL: ${e.message}")
            null
        }
    }

    /**
     * Feature 2: serve the eruda script from a local cache to avoid re-downloading it on every
     * page load.  The cache is stored in the app's private files directory and refreshed after
     * [CACHE_MAX_AGE_MS].  A stale cache is used as a fallback when the network is unavailable.
     */
    private fun serveCachedErudaScript(): WebResourceResponse? {
        val headers = mapOf("Access-Control-Allow-Origin" to "*")

        // Serve from cache if it exists and is not stale.
        if (erudaScriptCacheFile.exists() &&
            System.currentTimeMillis() - erudaScriptCacheFile.lastModified() < CACHE_MAX_AGE_MS
        ) {
            Log.i(TAG, "Serving eruda script from cache")
            return WebResourceResponse(
                "application/javascript", "utf-8", 200, "OK",
                headers, erudaScriptCacheFile.inputStream()
            )
        }

        // Download from CDN, save to cache, and serve.
        // Note: shouldInterceptRequest is called on a background thread by WebView, so
        // blocking network I/O here is safe and does not block the main thread.
        Log.i(TAG, "Downloading eruda script from CDN")
        return try {
            val req = Request.Builder().url(ERUDA_CDN_URL).build()
            val bytes = httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Eruda CDN returned HTTP ${response.code} ${response.message}")
                    return@use null
                }
                response.body?.bytes().also {
                    if (it == null) Log.e(TAG, "Empty response body from eruda CDN")
                }
            } ?: return null
            try {
                erudaScriptCacheFile.writeBytes(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write eruda script cache: ${e.message}")
            }
            WebResourceResponse(
                "application/javascript", "utf-8", 200, "OK",
                headers, bytes.inputStream()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download eruda script: ${e.message}")
            // Fall back to a stale cached copy rather than failing completely.
            if (erudaScriptCacheFile.exists()) {
                Log.i(TAG, "Serving stale eruda script from cache")
                WebResourceResponse(
                    "application/javascript", "utf-8", 200, "OK",
                    headers, erudaScriptCacheFile.inputStream()
                )
            } else {
                Log.e(TAG, "Eruda script unavailable: network error and no local cache")
                null
            }
        }
    }

    private fun loadFileUrl(url: String) {
        when {
            // Android 11+ (API 30+): READ_EXTERNAL_STORAGE no longer covers non-media files
            // (e.g. HTML, JS, CSS) in shared storage. "All Files Access" is required.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    webView.loadUrl(url)
                } else {
                    pendingFileUrl = url
                    Toast.makeText(this, R.string.all_files_access_required, Toast.LENGTH_LONG).show()
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        pendingAllFilesAccess = true
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            pendingAllFilesAccess = true
                        } catch (e2: Exception) {
                            pendingFileUrl = null
                            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            // Android 6–10 (API 23–29): use READ_EXTERNAL_STORAGE runtime permission
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    webView.loadUrl(url)
                } else {
                    pendingFileUrl = url
                    storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            // Below Android 6: no runtime permission needed
            else -> webView.loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        // Retry loading a pending file:// URL when the user returns from the
        // "All Files Access" settings screen having granted the permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pendingAllFilesAccess) {
            pendingAllFilesAccess = false
            val url = pendingFileUrl
            if (url != null && Environment.isExternalStorageManager()) {
                pendingFileUrl = null
                webView.loadUrl(url)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    if (manager.isActive) {
                        manager.hideSoftInputFromWindow(textUrl.applicationWindowToken, 0)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setTextUrl(text: String?) {
        if (!textUrl.hasFocus() && text != null) {
            textUrl.setText(text)
        }
    }
}

fun isHttpUrl(url: String): Boolean {
    return url.startsWith("http:") || url.startsWith("https:")
}

fun isFileUrl(url: String): Boolean {
    return url.startsWith("file://")
}

fun mayBeUrl(text: String): Boolean {
    val domains = arrayOf(".com", ".io", ".me", ".org", ".net", ".tv", ".cn")

    return domains.any { text.contains(it) }
}