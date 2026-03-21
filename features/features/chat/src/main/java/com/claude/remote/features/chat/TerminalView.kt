package com.claude.remote.features.chat

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    outputFlow: Flow<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Prevent zoom
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            setBackgroundColor(android.graphics.Color.parseColor("#1C1917"))

            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(
                    view: WebView?, handler: SslErrorHandler?, error: SslError?
                ) {
                    handler?.proceed()
                }
            }
            webChromeClient = WebChromeClient()

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onTerminalReady() {
                    // Terminal is ready to receive data
                }

                @JavascriptInterface
                fun onTerminalInput(data: String) {
                    // User typed in terminal — could forward to SSH
                }
            }, "AndroidBridge")

            loadUrl("file:///android_asset/terminal.html")
        }
    }

    // Collect output and push to WebView
    LaunchedEffect(webView) {
        outputFlow.collect { chunk ->
            // Escape for JavaScript string — handle special chars
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
