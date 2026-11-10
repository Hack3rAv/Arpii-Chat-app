package com.systemutility.feature.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.systemutility.core.database.dao.MediaDao
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.model.EncryptedAttachmentMeta
import com.systemutility.core.model.MediaCategory
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.api.safeApiCall
import com.systemutility.core.network.dto.MediaDownloadAuthRequest
import com.systemutility.core.network.dto.MediaUploadInitRequest
import com.systemutility.core.result.AppResult
import com.systemutility.core.security.StreamCipherEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class StagedEncryptedAttachment(
    val entity: AttachmentEntity,
    val meta: EncryptedAttachmentMeta
)

@Singleton
class EncryptedMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SystemUtilityApi,
    private val mediaDao: MediaDao,
    private val cipherEngine: StreamCipherEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val vaultCacheDir = File(context.filesDir, "media_cache").apply { mkdirs() }
    private val encStagingDir = File(context.filesDir, "enc_staging").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun observeVisualMedia(): Flow<List<AttachmentEntity>> = mediaDao.observeGalleryVisualMedia()
    fun observeDocsAndAudio(): Flow<List<AttachmentEntity>> = mediaDao.observeGalleryDocumentsAndAudio()

    fun createCameraCaptureUri(): Uri {
        val capturesDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val file = File(capturesDir, "cap_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun getFileProviderUri(localFile: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
    }

    /**
     * Encrypts and uploads a local file or Content URI, keeping plaintext only in app-private cache
     * and uploading zero-knowledge AES-256-GCM chunks to R2.
     */
    suspend fun encryptAndUploadMedia(
        messageId: String,
        sourceUri: Uri?,
        sourceFile: File?,
        category: MediaCategory,
        durationMs: Long = 0L,
        waveformCsv: String = "",
        onProgress: (Int) -> Unit = {}
    ): AppResult<StagedEncryptedAttachment> = withContext(ioDispatcher) {
        val attachmentId = UUID.randomUUID().toString()
        val resolvedMeta = resolveMetadata(sourceUri, sourceFile, category)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMeta.mimeType)
            ?: resolvedMeta.fileName.substringAfterLast('.', "bin").take(8)
        val cachedPlainFile = File(vaultCacheDir, "${attachmentId}.$ext")
        val stagedEncFile = File(encStagingDir, "${attachmentId}.enc")

        try {
            // 1. Copy plaintext into app-private vault cache so sender retains offline copy cleanly
            if (sourceFile != null) {
                sourceFile.copyTo(cachedPlainFile, overwrite = true)
            } else if (sourceUri != null) {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(cachedPlainFile).use { output -> input.copyTo(output) }
                } ?: return@withContext AppResult.Error("FILE_READ_ERROR", "Unable to open selected file.")
            } else {
                return@withContext AppResult.Error("INVALID_INPUT", "No media source supplied.")
            }

            // Optional compact preview thumbnail for visual media
            val thumbPath = if (category == MediaCategory.IMAGE) {
                generateThumbnail(cachedPlainFile, attachmentId)
            } else {
                null
            }

            // 2. Stream-encrypt with fresh random 256-bit AES key + 96-bit base IV
            val encResult = FileInputStream(cachedPlainFile).use { input ->
                FileOutputStream(stagedEncFile).use { output ->
                    cipherEngine.encryptStream(input, output)
                }
            }

            val draftEntity = AttachmentEntity(
                id = attachmentId,
                messageId = messageId,
                category = category.apiValue,
                fileName = resolvedMeta.fileName,
                mimeType = resolvedMeta.mimeType,
                sizeBytes = cachedPlainFile.length(),
                r2ObjectKey = null,
                fileKeyBase64 = encResult.keyBase64,
                baseIvBase64 = encResult.baseIvBase64,
                sha256CiphertextHex = encResult.sha256CiphertextHex,
                localPlainPath = cachedPlainFile.absolutePath,
                thumbnailPath = thumbPath,
                durationMs = durationMs,
                waveformCsv = waveformCsv,
                transferState = "UPLOADING",
                progressPercent = 5
            )
            mediaDao.upsertAttachment(draftEntity)
            onProgress(5)

            // 3. Init upload on Worker
            val initRes = safeApiCall {
                api.initMediaUpload(
                    MediaUploadInitRequest(
                        encryptedSizeBytes = encResult.encryptedBytes,
                        sha256Ciphertext = encResult.sha256CiphertextHex,
                        mimeCategory = category.apiValue
                    )
                )
            }
            val init = when (initRes) {
                is AppResult.Error -> {
                    mediaDao.updateTransferState(attachmentId, "FAILED", 0)
                    return@withContext initRes
                }
                is AppResult.Success -> initRes.data
            }

            mediaDao.updateTransferState(attachmentId, "UPLOADING", 10, init.r2ObjectKey)

            // 4. Stream encrypted bytes to R2 with real chunked progress callbacks
            val body = ProgressFileRequestBody(stagedEncFile) { pct ->
                val mapped = 10 + (pct * 85) / 100
                onProgress(mapped)
            }
            val uploadRes = safeApiCall { api.uploadMediaBlob(init.mediaId, body) }
            if (uploadRes is AppResult.Error) {
                mediaDao.updateTransferState(attachmentId, "FAILED", 0, init.r2ObjectKey)
                return@withContext uploadRes
            }

            mediaDao.updateTransferState(attachmentId, "DOWNLOADED", 100, init.r2ObjectKey)
            onProgress(100)

            val finalEntity = draftEntity.copy(
                r2ObjectKey = init.r2ObjectKey,
                transferState = "DOWNLOADED",
                progressPercent = 100
            )
            val meta = EncryptedAttachmentMeta(
                attachmentId = attachmentId,
                category = category.apiValue,
                fileName = finalEntity.fileName,
                mimeType = finalEntity.mimeType,
                sizeBytes = finalEntity.sizeBytes,
                r2ObjectKey = init.r2ObjectKey,
                fileKeyBase64 = encResult.keyBase64,
                baseIvBase64 = encResult.baseIvBase64,
                sha256CiphertextHex = encResult.sha256CiphertextHex,
                durationMs = durationMs,
                waveformCsv = waveformCsv
            )
            AppResult.Success(StagedEncryptedAttachment(finalEntity, meta))
        } catch (t: Throwable) {
            SafeLogger.e("Media", "Encrypted upload failed: ${t.message}", t)
            mediaDao.updateTransferState(attachmentId, "FAILED", 0)
            AppResult.Error("MEDIA_UPLOAD_FAILED", t.message ?: "Failed uploading encrypted media.")
        } finally {
            stagedEncFile.delete()
        }
    }

    suspend fun downloadAndDecrypt(attachment: AttachmentEntity): AppResult<File> = withContext(ioDispatcher) {
        val existingPath = attachment.localPlainPath
        if (existingPath != null && File(existingPath).exists()) {
            return@withContext AppResult.Success(File(existingPath))
        }
        val objectKey = attachment.r2ObjectKey
            ?: return@withContext AppResult.Error("NO_OBJECT_KEY", "Missing R2 key.")

        val tempEnc = File(encStagingDir, "dl_${attachment.id}.enc")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mimeType)
            ?: attachment.fileName.substringAfterLast('.', "bin").take(8)
        val targetPlain = File(vaultCacheDir, "${attachment.id}.$ext")

        try {
            mediaDao.updateTransferState(attachment.id, "DOWNLOADING", 5)
            val authRes = safeApiCall {
                api.authorizeMediaDownload(MediaDownloadAuthRequest(r2ObjectKey = objectKey))
            }
            val grant = when (authRes) {
                is AppResult.Error -> {
                    mediaDao.updateTransferState(attachment.id, "FAILED", 0)
                    return@withContext authRes
                }
                is AppResult.Success -> authRes.data.grant
            }
            val resp = api.downloadMediaBlob(objectKey, grant)
            if (!resp.isSuccessful || resp.body() == null) {
                mediaDao.updateTransferState(attachment.id, "FAILED", 0)
                return@withContext AppResult.Error("HTTP_${resp.code()}", "Failed downloading encrypted media.")
            }

            val body = resp.body()!!
            val totalLen = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var lastReported = 5

            body.byteStream().use { input ->
                FileOutputStream(tempEnc).use { output ->
                    val buf = ByteArray(16384)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        readTotal += n
                        if (totalLen > 0) {
                            val pct = ((readTotal * 90) / totalLen).toInt().coerceIn(5, 95)
                            if (pct - lastReported >= 10) {
                                lastReported = pct
                                mediaDao.updateTransferState(attachment.id, "DOWNLOADING", pct)
                            }
                        }
                    }
                }
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(attachment.sha256CiphertextHex, ignoreCase = true)) {
                mediaDao.updateTransferState(attachment.id, "FAILED", 0)
                return@withContext AppResult.Error("INTEGRITY_ERROR", "Encrypted media checksum mismatch.")
            }

            val keyBytes = Base64.getDecoder().decode(attachment.fileKeyBase64)
            FileInputStream(tempEnc).use { encIn ->
                FileOutputStream(targetPlain).use { plainOut ->
                    cipherEngine.decryptStream(encIn, plainOut, keyBytes)
                }
            }

            mediaDao.markDownloaded(attachment.id, targetPlain.absolutePath)
            AppResult.Success(targetPlain)
        } catch (t: Throwable) {
            SafeLogger.e("Media", "Decrypt download failed: ${t.message}", t)
            targetPlain.delete()
            mediaDao.updateTransferState(attachment.id, "FAILED", 0)
            AppResult.Error("MEDIA_DOWNLOAD_FAILED", t.message ?: "Failed decrypting attachment.")
        } finally {
            tempEnc.delete()
        }
    }

    suspend fun uploadEncryptedAvatar(sourceUri: Uri): AppResult<Pair<String, String>> = withContext(ioDispatcher) {
        val stagedEnc = File(encStagingDir, "avatar_${System.currentTimeMillis()}.enc")
        try {
            val encResult = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(stagedEnc).use { output -> cipherEngine.encryptStream(input, output) }
            } ?: return@withContext AppResult.Error("READ_FAILED", "Cannot open avatar photo.")

            val initRes = safeApiCall {
                api.initMediaUpload(
                    MediaUploadInitRequest(
                        encryptedSizeBytes = encResult.encryptedBytes,
                        sha256Ciphertext = encResult.sha256CiphertextHex,
                        mimeCategory = MediaCategory.AVATAR.apiValue
                    )
                )
            }
            val init = when (initRes) {
                is AppResult.Error -> return@withContext initRes
                is AppResult.Success -> initRes.data
            }

            val upRes = safeApiCall {
                api.uploadMediaBlob(init.mediaId, ProgressFileRequestBody(stagedEnc) {})
            }
            when (upRes) {
                is AppResult.Error -> upRes
                is AppResult.Success -> AppResult.Success(init.r2ObjectKey to encResult.keyBase64)
            }
        } finally {
            stagedEnc.delete()
        }
    }

    suspend fun downloadAndDecryptAvatar(
        r2ObjectKey: String,
        keyBase64: String,
        userId: String
    ): AppResult<File> = withContext(ioDispatcher) {
        val avatarsDir = File(context.filesDir, "avatars").apply { mkdirs() }
        val target = File(avatarsDir, "${userId}_${r2ObjectKey.hashCode()}.jpg")
        if (target.exists() && target.length() > 0L) {
            return@withContext AppResult.Success(target)
        }
        val tempEnc = File(encStagingDir, "avatar_dl_${System.currentTimeMillis()}.enc")
        try {
            val authRes = safeApiCall {
                api.authorizeMediaDownload(MediaDownloadAuthRequest(r2ObjectKey = r2ObjectKey))
            }
            val grant = when (authRes) {
                is AppResult.Error -> return@withContext authRes
                is AppResult.Success -> authRes.data.grant
            }
            val resp = api.downloadMediaBlob(r2ObjectKey, grant)
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext AppResult.Error("HTTP_${resp.code()}", "Unable to fetch profile avatar.")
            }
            resp.body()!!.byteStream().use { input ->
                FileOutputStream(tempEnc).use { output -> input.copyTo(output) }
            }
            val keyBytes = Base64.getDecoder().decode(keyBase64)
            FileInputStream(tempEnc).use { encIn ->
                FileOutputStream(target).use { plainOut ->
                    cipherEngine.decryptStream(encIn, plainOut, keyBytes)
                }
            }
            AppResult.Success(target)
        } catch (t: Throwable) {
            target.delete()
            AppResult.Error("AVATAR_DECRYPT_FAILED", t.message ?: "Unable to decrypt avatar.")
        } finally {
            tempEnc.delete()
        }
    }

    suspend fun saveToDownloads(attachment: AttachmentEntity): AppResult<String> = withContext(ioDispatcher) {
        val localFile = when (val dl = downloadAndDecrypt(attachment)) {
            is AppResult.Error -> return@withContext dl
            is AppResult.Success -> dl.data
        }
        return@withContext try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, attachment.fileName)
                    put(MediaStore.Downloads.MIME_TYPE, attachment.mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SystemUtility")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext AppResult.Error("EXPORT_FAILED", "MediaStore insert failed.")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(localFile).use { input -> input.copyTo(out) }
                }
            } else {
                val destDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SystemUtility"
                ).apply { mkdirs() }
                localFile.copyTo(File(destDir, attachment.fileName), overwrite = true)
            }
            AppResult.Success("Saved to Downloads/SystemUtility/${attachment.fileName}")
        } catch (t: Throwable) {
            AppResult.Error("SAVE_FAILED", t.message ?: "Unable to export file.")
        }
    }

    suspend fun deleteLocalCache(attachmentId: String) = withContext(ioDispatcher) {
        val att = mediaDao.getById(attachmentId) ?: return@withContext
        att.localPlainPath?.let { File(it).delete() }
        mediaDao.clearLocalFile(attachmentId)
    }

    private fun generateThumbnail(plainImage: File, attachmentId: String): String? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(plainImage.absolutePath, opts) ?: return null
            val out = File(thumbDir, "$attachmentId.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            bmp.recycle()
            out.absolutePath
        }.getOrNull()
    }

    private data class ResolvedMediaMeta(val fileName: String, val mimeType: String)

    private fun resolveMetadata(uri: Uri?, file: File?, category: MediaCategory): ResolvedMediaMeta {
        if (file != null) {
            val mime = when (category) {
                MediaCategory.VOICE -> "audio/mp4"
                MediaCategory.IMAGE -> "image/jpeg"
                MediaCategory.VIDEO -> "video/mp4"
                MediaCategory.AUDIO -> "audio/mpeg"
                else -> "application/octet-stream"
            }
            return ResolvedMediaMeta(file.name, mime)
        }
        val cr = context.contentResolver
        val mime = uri?.let { cr.getType(it) } ?: when (category) {
            MediaCategory.IMAGE -> "image/jpeg"
            MediaCategory.VIDEO -> "video/mp4"
            MediaCategory.AUDIO, MediaCategory.VOICE -> "audio/mp4"
            else -> "application/octet-stream"
        }
        var name: String? = null
        uri?.let {
            cr.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        val fallbackExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val finalName = name ?: "${category.apiValue}_${System.currentTimeMillis()}.$fallbackExt"
        return ResolvedMediaMeta(finalName, mime)
    }

    private class ProgressFileRequestBody(
        private val file: File,
        private val onProgress: (Int) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            val buffer = ByteArray(16384)
            var uploaded = 0L
            var lastPct = 0
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    val pct = ((uploaded * 100) / total).toInt().coerceIn(0, 100)
                    if (pct - lastPct >= 10 || pct == 100) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
        }
    }
}
