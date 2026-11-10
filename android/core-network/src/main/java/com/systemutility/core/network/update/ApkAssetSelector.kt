package com.systemutility.core.network.update

object ApkAssetSelector {
    private val REPO_SLUG_REGEX = Regex("""^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$""")
    private val SHA256_LINE_REGEX = Regex("""^([A-Fa-f0-9]{64})\s+\*?(.+)$""")
    private val SHA256_ONLY_REGEX = Regex("""^([A-Fa-f0-9]{64})$""")
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "mips")

    /**
     * Normalizes either `owner/repo` or `https://github.com/owner/repo[.git|/releases]` into `owner/repo`.
     */
    fun normalizeGithubRepo(rawInput: String): String? {
        val cleaned = rawInput.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .trim('/')
            .removeSuffix(".git")
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val candidate = "${segments[0]}/${segments[1]}"
        return candidate.takeIf { REPO_SLUG_REGEX.matches(it) }
    }

    /**
     * Selects the best HTTPS `.apk` asset matching device ABI precedence -> `universal` -> generic `.apk`.
     */
    fun selectBestApk(
        assets: List<GitHubReleaseAssetDto>,
        supportedAbis: List<String>
    ): GitHubReleaseAssetDto? {
        val httpsApks = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.browserDownloadUrl.startsWith("https://", ignoreCase = true) &&
                !asset.name.contains("androidTest", ignoreCase = true)
        }
        if (httpsApks.isEmpty()) return null

        val preferredSet = httpsApks.filterNot { it.name.contains("unsigned", ignoreCase = true) }
            .ifEmpty { httpsApks }

        for (abi in supportedAbis) {
            val match = preferredSet.firstOrNull { asset ->
                val lower = asset.name.lowercase()
                lower.contains(abi.lowercase())
            }
            if (match != null) return match
        }

        val universal = preferredSet.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        if (universal != null) return universal

        // Generic single APK not tagged for another incompatible ABI
        return preferredSet.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            KNOWN_ABIS.none { abi -> lower.contains(abi) }
        }
    }

    fun findChecksumAsset(
        assets: List<GitHubReleaseAssetDto>,
        apkAssetName: String
    ): GitHubReleaseAssetDto? {
        val httpsAssets = assets.filter { it.browserDownloadUrl.startsWith("https://", ignoreCase = true) }
        return httpsAssets.firstOrNull { it.name.equals("$apkAssetName.sha256", ignoreCase = true) }
            ?: httpsAssets.firstOrNull { it.name.equals("$apkAssetName.sha256sum", ignoreCase = true) }
            ?: httpsAssets.firstOrNull { it.name.equals("checksums.txt", ignoreCase = true) }
            ?: httpsAssets.firstOrNull { it.name.equals("sha256sums.txt", ignoreCase = true) }
    }

    fun parseExpectedSha256(
        checksumContent: String?,
        apkAssetName: String,
        assetDigestField: String? = null
    ): String? {
        assetDigestField?.trim()?.let { digest ->
            if (digest.startsWith("sha256:", ignoreCase = true)) {
                val hex = digest.substringAfter(':').trim()
                if (SHA256_ONLY_REGEX.matches(hex)) {
                    return hex.lowercase()
                }
            }
        }

        if (checksumContent.isNullOrBlank()) return null
        val lines = checksumContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        for (line in lines) {
            val match = SHA256_LINE_REGEX.matchEntire(line)
            if (match != null) {
                val hash = match.groupValues[1].lowercase()
                val filePart = match.groupValues[2].trim().substringAfterLast('/').substringAfterLast('\\')
                if (filePart.equals(apkAssetName, ignoreCase = true)) {
                    return hash
                }
            }
        }

        if (lines.size == 1) {
            val single = SHA256_ONLY_REGEX.matchEntire(lines.first())
            if (single != null) {
                return single.groupValues[1].lowercase()
            }
        }
        return null
    }
}
