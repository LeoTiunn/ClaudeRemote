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
import androidx.compose.runtime.DisposableEffect
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pageLoaded = remember { MutableStateFlow(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val pendingResize = remember { Runnable { } } // placeholder, replaced below

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            setBackgroundColor(android.graphics.Color.parseColor("#1C1917"))

                // Prevent parent from intercepting touch — let WebView handle scroll
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false  // Let WebView handle the event
                }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    DebugLog.log("WEBVIEW", "onPageFinished: $url")
                    pageLoaded.value = true
                    view?.let { sendResizeToJs(it) }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    message?.let {
                        DebugLog.log("WEBVIEW-JS", "${it.messageLevel()}: ${it.message()}")
                    }
                    return true
                }
            }

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onTerminalReady() {
                    DebugLog.log("WEBVIEW", "Terminal signaled ready")
                }

                @JavascriptInterface
                fun onTerminalInput(data: String) {
                    DebugLog.log("WEBVIEW", "Terminal input: ${data.take(50)}")
                    onInput?.invoke(data)
                }

                @JavascriptInterface
                fun onTerminalResize(cols: Int, rows: Int) {
                    DebugLog.log("WEBVIEW", "Terminal resized: ${cols}x${rows}")
                    onResize?.invoke(cols, rows)
                }
            }, "AndroidBridge")

            // Debounced resize on layout changes (keyboard animation fires dozens of events)
            var resizeRunnable: Runnable? = null
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                resizeRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable { sendResizeToJs(v as WebView) }
                resizeRunnable = runnable
                handler.postDelayed(runnable, 150)
            }

            DebugLog.log("WEBVIEW", "Loading terminal.html")
            loadUrl("file:///android_asset/terminal.html")
        }
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

    DisposableEffect(Unit) {
        onDispose {
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
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
