package com.systemutility.core.network.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateUtilsTest {

    @Test
    fun `semver comparison respects major minor patch and prereleases`() {
        assertTrue(ReleaseVersion.isRemoteNewer("1.0.0", 10000L, "v1.0.1"))
        assertTrue(ReleaseVersion.isRemoteNewer("1.0.0", 10000L, "1.10.0"))
        assertTrue(ReleaseVersion.isRemoteNewer("1.2.0", 10200L, "v1.10.0"))
        assertFalse(ReleaseVersion.isRemoteNewer("1.0.0", 10000L, "v1.0.0"))
        assertFalse(ReleaseVersion.isRemoteNewer("1.0.2", 10002L, "v1.0.1"))
        assertTrue(ReleaseVersion.isRemoteNewer("1.0.1-rc.1", 10001L, "v1.0.1"))
        assertFalse(ReleaseVersion.isRemoteNewer("1.0.1", 10001L, "v1.0.1-rc.1"))
        assertTrue(ReleaseVersion.isRemoteNewer("1.0.1", 10001L, "v1.0.1+10005"))
    }

    @Test
    fun `normalize github repo extracts owner and repo slug cleanly`() {
        assertEquals("octocat/system-utility", ApkAssetSelector.normalizeGithubRepo("octocat/system-utility"))
        assertEquals("octocat/system-utility", ApkAssetSelector.normalizeGithubRepo("https://github.com/octocat/system-utility"))
        assertEquals("octocat/system-utility", ApkAssetSelector.normalizeGithubRepo("https://github.com/octocat/system-utility.git"))
        assertEquals("octocat/system-utility", ApkAssetSelector.normalizeGithubRepo("https://github.com/octocat/system-utility/releases/latest"))
        assertNull(ApkAssetSelector.normalizeGithubRepo("just-a-word"))
        assertNull(ApkAssetSelector.normalizeGithubRepo(""))
    }

    @Test
    fun `apk asset selector prioritizes supported abi then universal and enforces https`() {
        val assets = listOf(
            GitHubReleaseAssetDto("app-arm64-v8a-release.apk", "https://github.com/o/r/releases/download/v1.0.1/arm64.apk", 1234L),
            GitHubReleaseAssetDto("app-armeabi-v7a-release.apk", "https://github.com/o/r/releases/download/v1.0.1/arm32.apk", 1234L),
            GitHubReleaseAssetDto("app-universal-release.apk", "https://github.com/o/r/releases/download/v1.0.1/universal.apk", 2234L),
            GitHubReleaseAssetDto("app-x86_64-release.apk", "http://insecure.example.com/x86_64.apk", 1234L)
        )

        val pickedArm64 = ApkAssetSelector.selectBestApk(assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("app-arm64-v8a-release.apk", pickedArm64?.name)

        // Fallback to universal when device ABI is x86_64 and x86_64 asset is insecure HTTP
        val pickedFallback = ApkAssetSelector.selectBestApk(assets, listOf("x86_64"))
        assertEquals("app-universal-release.apk", pickedFallback?.name)
    }

    @Test
    fun `sha256 parser resolves checksum from api digest and checksums txt`() {
        val expectedHex = "a".repeat(64)
        val parsedDigest = ApkAssetSelector.parseExpectedSha256(
            checksumContent = null,
            apkAssetName = "app-arm64-v8a-release.apk",
            assetDigestField = "sha256:${expectedHex.uppercase()}"
        )
        assertEquals(expectedHex, parsedDigest)

        val checksumTxt = """
            ${"b".repeat(64)}  app-armeabi-v7a-release.apk
            $expectedHex *dist/app-arm64-v8a-release.apk
        """.trimIndent()
        val parsedTxt = ApkAssetSelector.parseExpectedSha256(
            checksumContent = checksumTxt,
            apkAssetName = "app-arm64-v8a-release.apk",
            assetDigestField = null
        )
        assertEquals(expectedHex, parsedTxt)
    }
}
