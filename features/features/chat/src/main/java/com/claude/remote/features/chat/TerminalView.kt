package com.claude.remote.features.chat

import android.annotation.SuppressLint
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
                    pageLoaded.value = true
                }
            }
            webChromeClient = WebChromeClient()

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onTerminalReady() {
                    // Terminal is ready
                }

                @JavascriptInterface
                fun onTerminalInput(data: String) {
                    // User typed in terminal — could forward to SSH
                }
            }, "AndroidBridge")

            loadUrl("file:///android_asset/terminal.html")
        }
    }

    // Wait for page to load, then collect output and push to WebView
    LaunchedEffect(webView) {
        // Wait until the page has finished loading
        pageLoaded.first { it }

        outputFlow.collect { chunk ->
            val escaped = chunk
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u0007", "")  // Strip BEL
            webView.post {
                webView.evaluateJavascript("writeData(\"$escaped\")", null)
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
