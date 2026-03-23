package com.claude.remote.features.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.claude.remote.core.ssh.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    outputFlow: Flow<String>,
    onResize: ((cols: Int, rows: Int) -> Unit)? = null,
    onInput: ((String) -> Unit)? = null,
    webViewHolder: TerminalWebViewHolder,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val pageLoaded = remember { MutableStateFlow(webViewHolder.isInitialized) }

    // Update callbacks on the singleton so the JS interface always uses current ones
    webViewHolder.onResize = onResize
    webViewHolder.onInput = onInput

    val webView = remember {
        webViewHolder.detachFromParent()
        val wv = webViewHolder.getOrCreate(context)

        if (!webViewHolder.isInitialized) {
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            wv.settings.allowFileAccessFromFileURLs = true
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            wv.settings.builtInZoomControls = false
            wv.settings.displayZoomControls = false

            val bgColor = if (webViewHolder.isDarkTheme) "#1C1917" else "#FFF8F4"
            wv.setBackgroundColor(android.graphics.Color.parseColor(bgColor))

            // Prevent parent Compose layout from intercepting scroll events
            wv.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // Let WebView handle the touch for native scroll
            }

            wv.isFocusable = false
            wv.isFocusableInTouchMode = false

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    DebugLog.log("WEBVIEW", "onPageFinished: $url")
                    DebugLog.log("WEBVIEW", "UserAgent: ${view?.settings?.userAgentString}")
                    webViewHolder.markInitialized()
                    pageLoaded.value = true
                    val fs = webViewHolder.fontSize
                    val dark = webViewHolder.isDarkTheme
                    view?.evaluateJavascript("if(window.setFontSize)setFontSize($fs)", null)
                    view?.evaluateJavascript("if(window.setThemeDark)setThemeDark($dark)", null)
                    view?.let { sendResizeToJs(it) }
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    message?.let {
                        DebugLog.log("WEBVIEW-JS", "${it.messageLevel()}: ${it.message()}")
                    }
                    return true
                }
            }

            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun onTerminalReady() {
                    DebugLog.log("WEBVIEW", "Terminal signaled ready")
                }

                @JavascriptInterface
                fun onTerminalInput(data: String) {
                    DebugLog.log("WEBVIEW", "Terminal input: ${data.take(50)}")
                    webViewHolder.onInput?.invoke(data)
                }

                @JavascriptInterface
                fun onTerminalResize(cols: Int, rows: Int) {
                    DebugLog.log("WEBVIEW", "Terminal resized: ${cols}x${rows}")
                    webViewHolder.onResize?.invoke(cols, rows)
                }
            }, "AndroidBridge")

            var resizeRunnable: Runnable? = null
            wv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                resizeRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable { sendResizeToJs(v as WebView) }
                resizeRunnable = runnable
                handler.postDelayed(runnable, 150)
            }

            DebugLog.log("WEBVIEW", "Loading terminal.html")
            wv.loadUrl("file:///android_asset/terminal.html")
        } else {
            pageLoaded.value = true
            wv.post { sendResizeToJs(wv) }
        }

        wv
    }

    LaunchedEffect(webView) {
        DebugLog.log("WEBVIEW", "Waiting for page load...")
        pageLoaded.first { it }
        DebugLog.log("WEBVIEW", "Page loaded, starting to collect output")

        var count = 0
        outputFlow.collect { chunk ->
            count++
            val b64 = Base64.encodeToString(
                chunk.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            if (count <= 5 || count % 50 == 0) {
                DebugLog.log("WEBVIEW", "Sending chunk #$count (${chunk.length}b, b64=${b64.length})")
            }
            webView.post {
                webView.evaluateJavascript("writeBase64(\"$b64\")", null)
            }
        }
    }

    AndroidView(
        factory = {
            webViewHolder.detachFromParent()
            webView
        },
        modifier = modifier
    )
}

private fun sendResizeToJs(view: WebView) {
    val w = view.width
    val h = view.height
    if (w > 0 && h > 0) {
        val scale = view.resources.displayMetrics.density
        val cssW = (w / scale).toInt()
        val cssH = (h / scale).toInt()
        DebugLog.log("WEBVIEW", "Layout: ${w}x${h}px, CSS: ${cssW}x${cssH}dp")
        view.post {
            view.evaluateJavascript("if(window.setTermSize)setTermSize($cssW,$cssH)", null)
        }
    }
}
