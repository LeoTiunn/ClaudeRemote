package com.claude.remote.features.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claude.remote.core.ssh.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit = {}) {
    val logs by DebugLog.logs.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var uploadStatus by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(DebugLog.getAll()))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy All")
                }
                Button(
                    onClick = {
                        uploadStatus = "Uploading..."
                        scope.launch {
                            uploadStatus = uploadLogs(DebugLog.getAll())
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Upload")
                }
                OutlinedButton(
                    onClick = { DebugLog.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }

            uploadStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("Uploaded")) MaterialTheme.colorScheme.primary
                           else if (status.startsWith("Error")) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Text(
                text = "${logs.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Text(
                text = logs.joinToString("\n").ifEmpty { "(no logs yet)" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .verticalScroll(scrollState)
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

private suspend fun uploadLogs(logText: String): String = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "claude-remote-log-$timestamp.txt"
        val boundary = "----FormBoundary${System.currentTimeMillis()}"
        val url = URL("https://asune.asuscomm.com:30443/upload")

        // Basic auth: apa:hibikiboba
        val credentials = android.util.Base64.encodeToString(
            "apa:hibikiboba".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val connection = (url.openConnection() as HttpsURLConnection).apply {
            // Trust self-signed cert on user's own server
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, java.security.SecureRandom())
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }

            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Basic $credentials")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            append("Content-Type: text/plain\r\n")
            append("\r\n")
            append(logText)
            append("\r\n")
            append("--$boundary--\r\n")
        }

        connection.outputStream.use { os ->
            PrintWriter(OutputStreamWriter(os, Charsets.UTF_8), true).use { writer ->
                writer.print(body)
                writer.flush()
            }
        }

        val responseCode = connection.responseCode
        val responseBody = try {
            connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        }
        connection.disconnect()

        if (responseCode in 200..299) {
            // Extract the file path from response (e.g. /files/UPvBoY4c.txt)
            val pathMatch = Regex("/files/[^\"<\\s]+").find(responseBody)
            val path = pathMatch?.value ?: ""
            if (path.isNotEmpty()) {
                "Uploaded! https://asune.asuscomm.com:30443$path"
            } else {
                "Uploaded: $fileName (HTTP $responseCode)"
            }
        } else {
            "Error: HTTP $responseCode - ${responseBody.take(100)}"
        }
    } catch (e: Exception) {
        "Error: ${e.javaClass.simpleName}: ${e.message}"
    }
}
