package com.systemutility.core.network.update

import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.result.AppResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class GitHubReleaseClient @Inject constructor(
    @Named("external_downloads") private val externalOkHttp: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun fetchLatestRelease(
        repoInput: String,
        supportedAbis: List<String>
    ): AppResult<AvailableGitHubRelease> = withContext(ioDispatcher) {
        val repoSlug = ApkAssetSelector.normalizeGithubRepo(repoInput)
            ?: return@withContext AppResult.Error(
                code = "INVALID_REPO",
                message = "Enter a valid GitHub repository in owner/repo format."
            )

        val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SystemUtility-AndroidUpdater")
            .get()
            .build()

        try {
            externalOkHttp.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 -> return@withContext AppResult.Error(
                        code = "NO_RELEASES",
                        message = "No published GitHub Releases found for $repoSlug."
                    )
                    resp.code == 403 || resp.code == 429 -> return@withContext AppResult.Error(
                        code = "RATE_LIMITED",
                        message = "GitHub API rate limit reached. Try again later."
                    )
                    !resp.isSuccessful -> return@withContext AppResult.Error(
                        code = "HTTP_${resp.code}",
                        message = "GitHub Releases returned HTTP ${resp.code}."
                    )
                }

                val releaseDto = json.decodeFromString(GitHubReleaseDto.serializer(), rawBody)
                if (releaseDto.draft || releaseDto.prerelease) {
                    return@withContext AppResult.Error(
                        code = "NO_STABLE_RELEASE",
                        message = "Latest GitHub Release is marked draft or prerelease."
                    )
                }

                val bestApk = ApkAssetSelector.selectBestApk(releaseDto.assets, supportedAbis)
                    ?: return@withContext AppResult.Error(
                        code = "NO_COMPATIBLE_APK",
                        message = "Release ${releaseDto.tagName} has no compatible HTTPS APK asset."
                    )

                val checksumContent = ApkAssetSelector.findChecksumAsset(releaseDto.assets, bestApk.name)
                    ?.let { fetchSmallTextAsset(it.browserDownloadUrl) }

                val expectedSha = ApkAssetSelector.parseExpectedSha256(
                    checksumContent = checksumContent,
                    apkAssetName = bestApk.name,
                    assetDigestField = bestApk.digest
                )

                AppResult.Success(
                    AvailableGitHubRelease(
                        tagName = releaseDto.tagName,
                        releaseName = releaseDto.name.ifBlank { releaseDto.tagName },
                        changelog = releaseDto.body,
                        publishedAt = releaseDto.publishedAt,
                        htmlUrl = releaseDto.htmlUrl,
                        apkName = bestApk.name,
                        apkUrl = bestApk.browserDownloadUrl,
                        apkSizeBytes = bestApk.size,
                        expectedSha256Hex = expectedSha
                    )
                )
            }
        } catch (err: Throwable) {
            SafeLogger.w("Updater", "Release check failed: ${err.message}")
            AppResult.Error(
                code = "NETWORK_ERROR",
                message = err.message ?: "Unable to reach GitHub Releases."
            )
        }
    }

    suspend fun downloadAndVerifyApk(
        release: AvailableGitHubRelease,
        destinationFile: File,
        onProgress: (Int) -> Unit
    ): AppResult<File> = withContext(ioDispatcher) {
        if (!release.apkUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext AppResult.Error("INSECURE_URL", "Refused non-HTTPS APK download URL.")
        }

        val parentDir = destinationFile.parentFile ?: return@withContext AppResult.Error(
            "INVALID_PATH",
            "Missing update cache directory."
        )
        parentDir.mkdirs()

        val tempPart = File(parentDir, "${destinationFile.name}.part")
        tempPart.delete()

        val request = Request.Builder()
            .url(release.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "SystemUtility-AndroidUpdater")
            .get()
            .build()

        try {
            externalOkHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) {
                    return@withContext AppResult.Error(
                        code = "DOWNLOAD_HTTP_${resp.code}",
                        message = "Failed downloading APK (HTTP ${resp.code})."
                    )
                }

                val body = resp.body!!
                val expectedBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var totalRead = 0L
                var lastPct = -1

                body.byteStream().use { input ->
                    FileOutputStream(tempPart).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            totalRead += read
                            if (expectedBytes > 0L) {
                                val pct = ((totalRead * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct && (pct - lastPct >= 3 || pct == 100)) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }

                if (totalRead <= 0L || (release.apkSizeBytes > 0L && totalRead != release.apkSizeBytes)) {
                    tempPart.delete()
                    return@withContext AppResult.Error(
                        code = "SIZE_MISMATCH",
                        message = "Downloaded APK size mismatch ($totalRead vs ${release.apkSizeBytes} bytes)."
                    )
                }

                val expectedSha = release.expectedSha256Hex
                if (!expectedSha.isNullOrBlank()) {
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        tempPart.delete()
                        return@withContext AppResult.Error(
                            code = "CHECKSUM_MISMATCH",
                            message = "SHA-256 verification failed for ${release.apkName}."
                        )
                    }
                }

                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                if (!tempPart.renameTo(destinationFile)) {
                    tempPart.copyTo(destinationFile, overwrite = true)
                    tempPart.delete()
                }
                onProgress(100)
                AppResult.Success(destinationFile)
            }
        } catch (err: Throwable) {
            tempPart.delete()
            SafeLogger.w("Updater", "APK download failed: ${err.message}")
            AppResult.Error(
                code = "DOWNLOAD_FAILED",
                message = err.message ?: "APK download failed."
            )
        }
    }

    private fun fetchSmallTextAsset(httpsUrl: String): String? {
        if (!httpsUrl.startsWith("https://", ignoreCase = true)) return null
        val req = Request.Builder()
            .url(httpsUrl)
            .header("User-Agent", "SystemUtility-AndroidUpdater")
            .get()
            .build()
        return runCatching {
            externalOkHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val len = resp.body?.contentLength() ?: 0L
                if (len > 512 * 1024) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }
}
