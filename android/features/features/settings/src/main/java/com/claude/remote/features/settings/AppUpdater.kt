package com.claude.remote.features.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val latestVersion: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
    val hasUpdate: Boolean = false,
    val currentVersion: String = "",
    val hasPreviousVersion: Boolean = false,
    val previousVersion: String? = null
)

@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val GITHUB_OWNER = "LeoTiunn"
        private const val GITHUB_REPO = "ClaudeRemote"
        private const val APK_NAME = "claude-remote.apk"
        private const val BACKUP_APK_NAME = "claude-remote-previous.apk"
        private const val PREFS_NAME = "app_updater"
        private const val KEY_PREVIOUS_VERSION = "previous_version"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UpdateState(
        currentVersion = getCurrentVersion(),
        hasPreviousVersion = getBackupFile().exists(),
        previousVersion = prefs.getString(KEY_PREVIOUS_VERSION, null)
    ))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private fun getCurrentVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    suspend fun checkForUpdate() {
        _state.value = UpdateState(isChecking = true)
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = org.json.JSONObject(response)
                val tagName = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")

                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                val currentVersion = getCurrentVersion()
                val hasUpdate = isNewerVersion(tagName, currentVersion)

                _state.value = UpdateState(
                    latestVersion = tagName,
                    downloadUrl = apkUrl,
                    hasUpdate = hasUpdate
                )
            } catch (e: Exception) {
                _state.value = UpdateState(error = "Check failed: ${e.message}")
            }
        }
    }

    private fun getBackupFile(): File {
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), BACKUP_APK_NAME)
    }

    private fun backupCurrentApk() {
        try {
            val currentApkPath = context.packageManager
                .getApplicationInfo(context.packageName, 0).sourceDir
            val src = File(currentApkPath)
            val dst = getBackupFile()
            src.copyTo(dst, overwrite = true)
            prefs.edit().putString(KEY_PREVIOUS_VERSION, getCurrentVersion()).apply()
        } catch (e: Exception) {
            // Non-fatal — update still proceeds
        }
    }

    fun rollback() {
        val backup = getBackupFile()
        if (!backup.exists()) {
            _state.value = _state.value.copy(error = "No previous version to rollback to")
            return
        }
        installApkFile(backup)
    }

    fun downloadAndInstall() {
        val url = _state.value.downloadUrl ?: return
        _state.value = _state.value.copy(isDownloading = true, error = null)

        // Backup current APK before updating
        backupCurrentApk()

        // Clean up old update APKs (not backup)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.filter { it.name == APK_NAME }?.forEach { it.delete() }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Claude Remote Update")
            .setDescription("Downloading v${_state.value.latestVersion}")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    _state.value = _state.value.copy(isDownloading = false)
                    installApk()
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk() {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_NAME
        )
        installApkFile(file)
    }

    private fun installApkFile(file: File) {
        if (!file.exists()) {
            _state.value = _state.value.copy(error = "APK file not found")
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
