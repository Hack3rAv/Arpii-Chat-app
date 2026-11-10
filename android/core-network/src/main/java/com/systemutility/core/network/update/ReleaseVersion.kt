package com.systemutility.core.network.update

data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val buildNumber: Long? = null,
    val raw: String
) : Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        val preA = preRelease
        val preB = other.preRelease
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1 // Stable > prerelease
            preB == null -> -1
            else -> comparePreRelease(preA, preB)
        }
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val partsA = a.split('.')
        val partsB = b.split('.')
        val max = maxOf(partsA.size, partsB.size)
        for (i in 0 until max) {
            val partA = partsA.getOrNull(i) ?: return -1
            val partB = partsB.getOrNull(i) ?: return 1
            val numA = partA.toIntOrNull()
            val numB = partB.toIntOrNull()
            val cmp = when {
                numA != null && numB != null -> numA.compareTo(numB)
                numA != null -> -1 // Numeric identifiers always have lower precedence than non-numeric
                numB != null -> 1
                else -> partA.compareTo(partB)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        private val SEMVER_REGEX = Regex(
            """^(?:[vV]|release[-_/]?)?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$"""
        )

        fun parse(rawInput: String): ReleaseVersion? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            val match = SEMVER_REGEX.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotBlank() }
            val build = match.groupValues[5].takeIf { it.isNotBlank() }?.toLongOrNull()
            return ReleaseVersion(
                major = major,
                minor = minor,
                patch = patch,
                preRelease = pre,
                buildNumber = build,
                raw = trimmed
            )
        }

        fun isRemoteNewer(
            installedVersionName: String,
            installedVersionCode: Long,
            remoteTagName: String
        ): Boolean {
            val installed = parse(installedVersionName)
            val remote = parse(remoteTagName)
            if (installed != null && remote != null) {
                val cmp = remote.compareTo(installed)
                if (cmp != 0) return cmp > 0
                val remoteBuild = remote.buildNumber
                return remoteBuild != null && remoteBuild > installedVersionCode
            }
            val remoteNum = remoteTagName.filter { it.isDigit() }.toLongOrNull()
            if (remote == null && remoteNum != null && remoteNum > installedVersionCode) {
                return true
            }
            return remote != null && installed == null
        }
    }
}
