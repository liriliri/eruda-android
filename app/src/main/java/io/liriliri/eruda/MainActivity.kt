package io.liriliri.eruda

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val TAG = "Eruda"
    override fun onCreate(savedInstanceState: Bundle?) {
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
                if (!isHttpUrl(input)) {
                    try {
                        input = URLEncoder.encode(input, "utf-8")
                    } catch (e: UnsupportedEncodingException) {
                        Log.e(TAG, e.message.toString())
                    }
                    input = "https://www.google.com/search?q=${input}"
                }
                webView.loadUrl(input)
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                if (isHttpUrl(url)) {
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
                if (request.isForMainFrame) {
                    val url = request.url.toString()
                    if (!isHttpUrl(url)) {
                        return null
                    }

                    val client = OkHttpClient.Builder().followRedirects(true).build()
                    val req = Request.Builder()
                        .url(request.url.toString())
                        .headers(request.requestHeaders.toHeaders())
                        .build()

                    return try {
                        val response = client.newCall(req).execute()
                        val body = response.body?.string()
                        WebResourceResponse("text/html", response.header("content-encoding", "utf-8"), body?.byteInputStream())
                    } catch (e: Exception) {
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
                        script.src = '//cdn.jsdelivr.net/npm/eruda'; 
                        document.body.appendChild(script); 
                        script.onload = function () { 
                            eruda.init();
                            if (define) {
                                window.define = define;
                            }
                        }
                    })();
                """
                webView.evaluateJavascript(script) {}
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
        }
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // https://stackoverflow.com/questions/57449900/letting-webview-on-android-work-with-prefers-color-scheme-dark
        if(WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY) && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON);
            WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
        }

        webView.loadUrl("https://github.com/liriliri/eruda")
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