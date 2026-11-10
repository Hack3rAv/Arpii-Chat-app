package com.systemutility.feature.settings.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.di.ApplicationScope
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.network.BuildConfig
import com.systemutility.core.network.update.ApkAssetSelector
import com.systemutility.core.network.update.AvailableGitHubRelease
import com.systemutility.core.network.update.GitHubReleaseClient
import com.systemutility.core.network.update.ReleaseVersion
import com.systemutility.core.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    NEEDS_INSTALL_PERMISSION,
    READY_TO_INSTALL,
    ERROR
}

data class AppUpdateUiState(
    val installedVersionName: String = "1.0.0",
    val installedVersionCode: Long = 10000L,
    val configuredRepo: String = "",
    val defaultRepo: String = "",
    val effectiveRepo: String = "",
    val autoCheckEnabled: Boolean = true,
    val lastCheckedAt: Long = 0L,
    val skippedTag: String? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val availableRelease: AvailableGitHubRelease? = null,
    val downloadProgress: Int = 0,
    val downloadedApkPath: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val shouldShowPromptDialog: Boolean = false
)

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val releaseClient: GitHubReleaseClient,
    private val prefsDataStore: UserPreferencesDataStore,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()
    private val updatesDir = File(context.cacheDir, "updates")

    private val installedInfo: Pair<String, Long> by lazy { readInstalledPackageVersion() }

    private data class RuntimeUpdateState(
        val phase: UpdatePhase = UpdatePhase.IDLE,
        val availableRelease: AvailableGitHubRelease? = null,
        val downloadProgress: Int = 0,
        val downloadedApkPath: String? = null,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val shouldShowPromptDialog: Boolean = false
    )

    private val runtimeState = MutableStateFlow(RuntimeUpdateState())

    val uiState: StateFlow<AppUpdateUiState> = combine(
        prefsDataStore.githubUpdateRepoFlow,
        prefsDataStore.autoCheckUpdatesFlow,
        prefsDataStore.lastUpdateCheckAtFlow,
        prefsDataStore.skippedUpdateTagFlow,
        runtimeState
    ) { savedRepo, autoCheck, lastCheckedAt, skippedTag, rt ->
        val defaultRepo = BuildConfig.DEFAULT_GITHUB_REPO.trim()
        val effective = savedRepo.ifBlank { defaultRepo }
        AppUpdateUiState(
            installedVersionName = installedInfo.first,
            installedVersionCode = installedInfo.second,
            configuredRepo = savedRepo,
            defaultRepo = defaultRepo,
            effectiveRepo = effective,
            autoCheckEnabled = autoCheck,
            lastCheckedAt = lastCheckedAt,
            skippedTag = skippedTag,
            phase = rt.phase,
            availableRelease = rt.availableRelease,
            downloadProgress = rt.downloadProgress,
            downloadedApkPath = rt.downloadedApkPath,
            statusMessage = rt.statusMessage,
            errorMessage = rt.errorMessage,
            shouldShowPromptDialog = rt.shouldShowPromptDialog
        )
    }.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = AppUpdateUiState(
            installedVersionName = installedInfo.first,
            installedVersionCode = installedInfo.second,
            defaultRepo = BuildConfig.DEFAULT_GITHUB_REPO.trim(),
            effectiveRepo = BuildConfig.DEFAULT_GITHUB_REPO.trim()
        )
    )

    init {
        appScope.launch(ioDispatcher) {
            cleanupStaleUpdateFiles()
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun createUnknownSourcesSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun saveGithubRepo(rawInput: String): AppResult<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            appScope.launch(ioDispatcher) { prefsDataStore.setGithubUpdateRepo("") }
            runtimeState.update {
                it.copy(
                    phase = UpdatePhase.IDLE,
                    availableRelease = null,
                    errorMessage = null,
                    statusMessage = "Reset GitHub Releases repository setting."
                )
            }
            return AppResult.Success("")
        }
        val normalized = ApkAssetSelector.normalizeGithubRepo(trimmed)
            ?: return AppResult.Error(
                code = "INVALID_REPO",
                message = "Use owner/repo or a full https://github.com/owner/repo URL."
            )
        appScope.launch(ioDispatcher) {
            prefsDataStore.setGithubUpdateRepo(normalized)
        }
        runtimeState.update {
            it.copy(
                errorMessage = null,
                statusMessage = "Update repository set to $normalized."
            )
        }
        return AppResult.Success(normalized)
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        appScope.launch(ioDispatcher) {
            prefsDataStore.setAutoCheckUpdates(enabled)
        }
    }

    fun dismissPromptDialog() {
        runtimeState.update { it.copy(shouldShowPromptDialog = false) }
    }

    fun skipCurrentRelease() {
        val tag = runtimeState.value.availableRelease?.tagName ?: return
        appScope.launch(ioDispatcher) {
            prefsDataStore.setSkippedUpdateTag(tag)
        }
        runtimeState.update { it.copy(shouldShowPromptDialog = false) }
    }

    fun clearNotices() {
        runtimeState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun checkForUpdates(force: Boolean) {
        appScope.launch(ioDispatcher) {
            checkForUpdatesSuspend(force)
        }
    }

    suspend fun checkForUpdatesSuspend(force: Boolean) {
        checkMutex.withLock {
            val currentPhase = runtimeState.value.phase
            if (currentPhase == UpdatePhase.CHECKING || currentPhase == UpdatePhase.DOWNLOADING) {
                return
            }

            val savedRepo = prefsDataStore.githubUpdateRepoFlow.first()
            val effectiveRepo = savedRepo.ifBlank { BuildConfig.DEFAULT_GITHUB_REPO.trim() }
            if (effectiveRepo.isBlank()) {
                if (force) {
                    runtimeState.update {
                        it.copy(
                            phase = UpdatePhase.ERROR,
                            errorMessage = "Configure your GitHub repository (owner/repo) above to check Git Releases.",
                            statusMessage = null
                        )
                    }
                }
                return
            }

            if (!force) {
                val autoEnabled = prefsDataStore.autoCheckUpdatesFlow.first()
                if (!autoEnabled) return
                val lastCheck = prefsDataStore.lastUpdateCheckAtFlow.first()
                if (System.currentTimeMillis() - lastCheck < AUTO_CHECK_COOLDOWN_MS) {
                    return
                }
            }

            runtimeState.update {
                it.copy(phase = UpdatePhase.CHECKING, errorMessage = null, statusMessage = null)
            }

            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val result = releaseClient.fetchLatestRelease(effectiveRepo, abis)
            prefsDataStore.setLastUpdateCheckAt(System.currentTimeMillis())

            when (result) {
                is AppResult.Error -> {
                    if (force) {
                        runtimeState.update {
                            it.copy(
                                phase = UpdatePhase.ERROR,
                                errorMessage = result.message,
                                statusMessage = null
                            )
                        }
                    } else {
                        runtimeState.update { it.copy(phase = UpdatePhase.IDLE) }
                    }
                }

                is AppResult.Success -> {
                    val release = result.data
                    val (installedName, installedCode) = installedInfo
                    val isNewer = ReleaseVersion.isRemoteNewer(installedName, installedCode, release.tagName)
                    if (!isNewer) {
                        runtimeState.update {
                            it.copy(
                                phase = UpdatePhase.UP_TO_DATE,
                                availableRelease = null,
                                shouldShowPromptDialog = false,
                                errorMessage = null,
                                statusMessage = if (force) "Up to date (v$installedName)." else null
                            )
                        }
                        return
                    }

                    val skipped = prefsDataStore.skippedUpdateTagFlow.first()
                    val showPrompt = force || skipped != release.tagName
                    val targetFile = targetApkFileForRelease(release)
                    val alreadyDownloaded = targetFile.exists() && targetFile.length() > 0L
                    runtimeState.update {
                        it.copy(
                            phase = when {
                                !alreadyDownloaded -> UpdatePhase.UPDATE_AVAILABLE
                                canRequestPackageInstalls() -> UpdatePhase.READY_TO_INSTALL
                                else -> UpdatePhase.NEEDS_INSTALL_PERMISSION
                            },
                            availableRelease = release,
                            downloadedApkPath = if (alreadyDownloaded) targetFile.absolutePath else null,
                            downloadProgress = if (alreadyDownloaded) 100 else 0,
                            shouldShowPromptDialog = showPrompt,
                            errorMessage = null,
                            statusMessage = null
                        )
                    }
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        appScope.launch(ioDispatcher) {
            downloadMutex.withLock {
                val release = runtimeState.value.availableRelease ?: return@withLock
                if (runtimeState.value.phase == UpdatePhase.DOWNLOADING) return@withLock

                val destination = targetApkFileForRelease(release)
                runtimeState.update {
                    it.copy(
                        phase = UpdatePhase.DOWNLOADING,
                        downloadProgress = 0,
                        errorMessage = null,
                        statusMessage = "Downloading ${release.apkName}…",
                        shouldShowPromptDialog = false
                    )
                }

                val dlRes = releaseClient.downloadAndVerifyApk(release, destination) { pct ->
                    runtimeState.update { st -> st.copy(downloadProgress = pct) }
                }

                when (dlRes) {
                    is AppResult.Error -> {
                        runtimeState.update {
                            it.copy(
                                phase = UpdatePhase.ERROR,
                                errorMessage = dlRes.message,
                                statusMessage = null
                            )
                        }
                    }

                    is AppResult.Success -> {
                        val apkFile = dlRes.data
                        if (canRequestPackageInstalls()) {
                            runtimeState.update {
                                it.copy(
                                    phase = UpdatePhase.READY_TO_INSTALL,
                                    downloadedApkPath = apkFile.absolutePath,
                                    downloadProgress = 100,
                                    statusMessage = "Verified ${release.tagName}. Launching installer…",
                                    errorMessage = null
                                )
                            }
                            launchInstallerInternal(apkFile)
                        } else {
                            runtimeState.update {
                                it.copy(
                                    phase = UpdatePhase.NEEDS_INSTALL_PERMISSION,
                                    downloadedApkPath = apkFile.absolutePath,
                                    downloadProgress = 100,
                                    statusMessage = "Allow \"Install unknown apps\" for System Utility to finish installing ${release.tagName}.",
                                    errorMessage = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun installDownloadedApk() {
        val path = runtimeState.value.downloadedApkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            runtimeState.update {
                it.copy(
                    phase = UpdatePhase.UPDATE_AVAILABLE,
                    downloadedApkPath = null,
                    errorMessage = "Cached APK no longer exists. Tap Update to re-download."
                )
            }
            return
        }
        if (!canRequestPackageInstalls()) {
            runtimeState.update { it.copy(phase = UpdatePhase.NEEDS_INSTALL_PERMISSION) }
            return
        }
        launchInstallerInternal(file)
    }

    fun onHostResumed() {
        val current = runtimeState.value
        if (current.phase == UpdatePhase.NEEDS_INSTALL_PERMISSION && canRequestPackageInstalls()) {
            val file = current.downloadedApkPath?.let { File(it) }?.takeIf { it.exists() } ?: return
            runtimeState.update {
                it.copy(
                    phase = UpdatePhase.READY_TO_INSTALL,
                    statusMessage = "Permission granted. Launching installer…"
                )
            }
            launchInstallerInternal(file)
        }
    }

    private fun launchInstallerInternal(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (err: Throwable) {
            SafeLogger.e("Updater", "Failed launching package installer: ${err.message}", err)
            runtimeState.update {
                it.copy(
                    phase = UpdatePhase.ERROR,
                    errorMessage = err.message ?: "Unable to open Android package installer."
                )
            }
        }
    }

    private fun targetApkFileForRelease(release: AvailableGitHubRelease): File {
        val safeTag = release.tagName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return File(updatesDir, "system-utility-$safeTag.apk")
    }

    private fun cleanupStaleUpdateFiles() {
        if (!updatesDir.exists()) return
        val (installedName, installedCode) = installedInfo
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part")) {
                file.delete()
                return@forEach
            }
            if (file.name.endsWith(".apk")) {
                val tag = file.name.removePrefix("system-utility-").removeSuffix(".apk")
                if (!ReleaseVersion.isRemoteNewer(installedName, installedCode, tag)) {
                    file.delete()
                }
            }
        }
    }

    private fun readInstalledPackageVersion(): Pair<String, Long> {
        return runCatching {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pkgInfo.versionName ?: "1.0.0"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            name to code
        }.getOrDefault("1.0.0" to 10000L)
    }

    companion object {
        private const val AUTO_CHECK_COOLDOWN_MS = 6L * 60L * 60L * 1000L
    }
}
