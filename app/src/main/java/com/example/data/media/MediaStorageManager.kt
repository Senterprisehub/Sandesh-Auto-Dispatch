package com.example.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.data.model.MediaAttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StagedMedia(
    val type: MediaAttachmentType,
    val localPath: String,
    val mimeType: String,
    val displayName: String
)

class MediaStorageManager(private val context: Context) {

    private val mediaDir: File
        get() = File(context.filesDir, "scheduled_media").apply {
            if (!exists()) mkdirs()
        }

    suspend fun stagePickedMedia(
        uri: Uri,
        forcedType: MediaAttachmentType? = null
    ): Result<StagedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val detectedMime = resolver.getType(uri) ?: "application/octet-stream"
            val displayName = resolveDisplayName(uri) ?: "attachment_${System.currentTimeMillis()}"
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(detectedMime)
                ?: displayName.substringAfterLast('.', "bin").take(8)

            val resolvedType = forcedType ?: when {
                detectedMime.startsWith("image/") -> MediaAttachmentType.IMAGE
                detectedMime.startsWith("video/") -> MediaAttachmentType.VIDEO
                else -> MediaAttachmentType.DOCUMENT
            }

            val destFile = File(mediaDir, "media_${UUID.randomUUID()}.$extension")
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to read selected attachment")

            StagedMedia(
                type = resolvedType,
                localPath = destFile.absolutePath,
                mimeType = detectedMime,
                displayName = displayName
            )
        }
    }

    fun getFileProviderUri(localPath: String): Uri? {
        val file = File(localPath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun deleteFileIfExists(localPath: String?) {
        if (localPath.isNullOrBlank()) return
        runCatching {
            val file = File(localPath)
            if (file.exists() && file.parentFile?.absolutePath == mediaDir.absolutePath) {
                file.delete()
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}
