package com.claude.remote.features.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.claude.remote.core.ssh.SshClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileUploadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val REMOTE_UPLOAD_DIR = "~/Downloads/attachments"
        private const val CHUNK_SIZE = 4096
    }

    suspend fun uploadFile(uri: Uri, sshClient: SshClient): Attachment {
        return withContext(Dispatchers.IO) {
            val fileName = getFileName(uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val fileSize = getFileSize(uri)

            // Ensure remote directory exists
            sshClient.executeCommand("mkdir -p $REMOTE_UPLOAD_DIR")

            val remotePath = "$REMOTE_UPLOAD_DIR/$fileName"

            // Upload file content via base64 encoding through SSH
            // We read the file in chunks, base64-encode each chunk, and append to remote file
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open file: $uri")

            inputStream.use { stream ->
                // Clear/create the file first
                sshClient.executeCommand("rm -f '$remotePath' && touch '$remotePath'")

                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                    val encoded = Base64.getEncoder().encodeToString(chunk)
                    sshClient.executeCommand("echo '$encoded' | base64 -d >> '$remotePath'")
                }
            }

            Attachment(
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = fileSize,
                remotePath = remotePath,
                isUploaded = true
            )
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "attachment"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) {
                size = cursor.getLong(sizeIndex)
            }
        }
        return size
    }
}
