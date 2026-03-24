package com.claude.remote.features.chat

import android.net.Uri

data class Attachment(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val remotePath: String? = null,
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val isUploaded: Boolean = false
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}
