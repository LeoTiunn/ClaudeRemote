package com.claude.remote.features.chat

import android.annotation.SuppressLint
import android.util.Base64
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.claude.remote.core.ssh.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    outputFlow: Flow<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pageLoaded = remember { MutableStateFlow(false) }

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

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    DebugLog.log("WEBVIEW", "onPageFinished: $url")
                    pageLoaded.value = true
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
                }
            }, "AndroidBridge")

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
        modifier = modifier,
        update = { view ->
            // Send actual pixel dimensions to JS for manual terminal resize
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                val scale = view.resources.displayMetrics.density
                val cssW = (w / scale).toInt()
                val cssH = (h / scale).toInt()
                DebugLog.log("WEBVIEW", "Layout: ${w}x${h}px, CSS: ${cssW}x${cssH}")
                view.evaluateJavascript("if(window.resizeTo)resizeTo($cssW,$cssH)", null)
            }
        }
    )
}
