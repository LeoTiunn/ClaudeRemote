package com.claude.remote.features.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.claude.remote.core.ssh.DebugLog

/**
 * Display-only xterm.js terminal view.
 * All keyboard input is handled by a separate native TextField.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    onResize: ((cols: Int, rows: Int) -> Unit)? = null,
    webViewHolder: TerminalWebViewHolder,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }

    webViewHolder.onResize = onResize

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

            // Display-only: don't let WebView steal focus from TextField
            wv.isFocusable = false
            wv.isFocusableInTouchMode = false

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
                    webViewHolder.pageReady = true
                    webViewHolder.rendererDead = false
                    val fs = webViewHolder.fontSize
                    val dark = webViewHolder.isDarkTheme
                    view?.evaluateJavascript("if(window.setFontSize)setFontSize($fs)", null)
                    view?.evaluateJavascript("if(window.setThemeDark)setThemeDark($dark)", null)
                    view?.evaluateJavascript(
                        "document.querySelector('.xterm-helper-textarea')?.setAttribute('disabled','true');" +
                        "document.querySelector('.xterm-helper-textarea')?.style.display='none';",
                        null
                    )
                    view?.let { sendResizeToJs(it) }
                }

                override fun onRenderProcessGone(
                    view: WebView?, detail: RenderProcessGoneDetail?
                ): Boolean {
                    DebugLog.log("WEBVIEW", "Render process gone! crashed=${detail?.didCrash()}")
                    webViewHolder.pageReady = false
                    webViewHolder.rendererDead = true
                    return true // We handle it — don't let the system kill the Activity
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
                fun onTerminalResize(cols: Int, rows: Int) {
                    DebugLog.log("WEBVIEW", "Terminal resized: ${cols}x${rows}")
                    webViewHolder.onResize?.invoke(cols, rows)
                }
            }, "AndroidBridge")

            // Output bridge: JS polls for data via @JavascriptInterface (immune to resize)
            wv.addJavascriptInterface(webViewHolder.outputBridge, "Android")

            // No layout change listener — WebView size is fixed (Box layout)
            // Initial size set in onPageFinished via sendResizeToJs

            DebugLog.log("WEBVIEW", "Loading terminal.html")
            wv.loadUrl("file:///android_asset/terminal.html")
        } else {
            wv.post { sendResizeToJs(wv) }
        }

        wv
    }

    AndroidView(
        factory = {
            webViewHolder.detachFromParent()
            webView
        },
        modifier = modifier
    )
}

private fun sendResizeToJs(view: WebView, retries: Int = 10) {
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
    } else if (retries > 0) {
        DebugLog.log("WEBVIEW", "Layout not ready (${w}x${h}), retry in 200ms ($retries left)")
        view.postDelayed({ sendResizeToJs(view, retries - 1) }, 200)
    }
}
