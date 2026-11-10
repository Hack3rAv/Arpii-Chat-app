package com.systemutility.core.network.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubReleaseAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
    @SerialName("content_type") val contentType: String = "",
    val digest: String? = null
)

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubReleaseAssetDto> = emptyList()
)

data class AvailableGitHubRelease(
    val tagName: String,
    val releaseName: String,
    val changelog: String,
    val publishedAt: String?,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val expectedSha256Hex: String?
)
