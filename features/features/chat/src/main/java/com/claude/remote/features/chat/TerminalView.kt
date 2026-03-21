package com.claude.remote.features.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val handler = remember { Handler(Looper.getMainLooper()) }
    val pageLoaded = remember { MutableStateFlow(webViewHolder.isInitialized) }

    // Mutable callback refs so the singleton WebView always calls the current composable's callbacks
    val callbacks = remember { TerminalCallbacks() }
    callbacks.onResize = onResize
    callbacks.onInput = onInput

    val webView = remember {
        webViewHolder.detachFromParent()
        val wv = webViewHolder.getOrCreate()

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

            wv.setBackgroundColor(android.graphics.Color.parseColor("#1C1917"))

            wv.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    DebugLog.log("WEBVIEW", "onPageFinished: $url")
                    webViewHolder.markInitialized()
                    pageLoaded.value = true
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
                    callbacks.onInput?.invoke(data)
                }

                @JavascriptInterface
                fun onTerminalResize(cols: Int, rows: Int) {
                    DebugLog.log("WEBVIEW", "Terminal resized: ${cols}x${rows}")
                    callbacks.onResize?.invoke(cols, rows)
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
            // Already initialized — just trigger a resize
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

/** Mutable callback holder so the singleton WebView always uses current callbacks */
private class TerminalCallbacks {
    var onResize: ((cols: Int, rows: Int) -> Unit)? = null
    var onInput: ((String) -> Unit)? = null
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
