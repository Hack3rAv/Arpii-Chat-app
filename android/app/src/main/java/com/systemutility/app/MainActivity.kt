package com.systemutility.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.model.CallType
import com.systemutility.core.model.ChatAppearanceConfig
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.model.PrivacyPreferences
import com.systemutility.core.notifications.BackgroundDeliveryProvider
import com.systemutility.core.ui.theme.SystemUtilityTheme
import com.systemutility.feature.auth.AuthViewModel
import com.systemutility.feature.auth.ui.AccountRecoveryScreen
import com.systemutility.feature.auth.ui.ChangePasswordScreen
import com.systemutility.feature.auth.ui.LoginScreen
import com.systemutility.feature.auth.ui.SessionsScreen
import com.systemutility.feature.auth.ui.SplashScreen
import com.systemutility.feature.backup.BackupViewModel
import com.systemutility.feature.backup.ui.BackupScreen
import com.systemutility.feature.calls.CallPhase
import com.systemutility.feature.calls.CallViewModel
import com.systemutility.feature.calls.WebRtcCallManager
import com.systemutility.feature.calls.ui.ActiveCallOverlayScreen
import com.systemutility.feature.calls.ui.CallHistoryScreen
import com.systemutility.feature.chat.ChatRepository
import com.systemutility.feature.chat.ChatViewModel
import com.systemutility.feature.chat.ConversationHomeViewModel
import com.systemutility.feature.chat.ui.ChatScreen
import com.systemutility.feature.chat.ui.ConversationHomeScreen
import com.systemutility.feature.media.MediaGalleryViewModel
import com.systemutility.feature.media.ui.MediaGalleryScreen
import com.systemutility.feature.profile.ProfileRepository
import com.systemutility.feature.profile.ProfileViewModel
import com.systemutility.feature.profile.ui.ProfileScreen
import com.systemutility.feature.settings.SettingsViewModel
import com.systemutility.feature.settings.ui.AppLockOverlayScreen
import com.systemutility.feature.settings.ui.AppUpdatesScreen
import com.systemutility.feature.settings.ui.AppearanceSettingsScreen
import com.systemutility.feature.settings.ui.NotificationSettingsScreen
import com.systemutility.feature.settings.ui.PrivacyAndSecurityScreen
import com.systemutility.feature.settings.ui.SafetyNumberScreen
import com.systemutility.feature.settings.ui.SettingsScreen
import com.systemutility.feature.settings.ui.UpdateAvailablePromptDialog
import com.systemutility.feature.settings.update.AppUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var preferencesDataStore: UserPreferencesDataStore
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var backgroundDeliveryProvider: BackgroundDeliveryProvider
    @Inject lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appearance by preferencesDataStore.appearanceFlow.collectAsState(initial = ChatAppearanceConfig())
            val privacy by preferencesDataStore.privacyFlow.collectAsState(initial = PrivacyPreferences())

            LaunchedEffect(privacy.screenshotProtectionEnabled) {
                if (privacy.screenshotProtectionEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            SystemUtilityTheme(appearance = appearance) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SystemUtilityNavHost(
                        preferencesDataStore = preferencesDataStore,
                        privacy = privacy,
                        chatRepository = chatRepository,
                        callManager = callManager,
                        profileRepository = profileRepository,
                        backgroundDeliveryProvider = backgroundDeliveryProvider,
                        appUpdateManager = appUpdateManager
                    )
                }
            }
        }
    }
}

@Composable
fun SystemUtilityNavHost(
    preferencesDataStore: UserPreferencesDataStore,
    privacy: PrivacyPreferences,
    chatRepository: ChatRepository,
    callManager: WebRtcCallManager,
    profileRepository: ProfileRepository,
    backgroundDeliveryProvider: BackgroundDeliveryProvider,
    appUpdateManager: AppUpdateManager
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val callViewModel: CallViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    val activeSession by authViewModel.activeSession.collectAsState()
    val activeCall by callViewModel.activeCallState.collectAsState()
    val recordingState by callViewModel.recordingState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()

    var privacyLoaded by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val initialPrivacy = preferencesDataStore.privacyFlow.first()
        isLocked = initialPrivacy.appLockEnabled
        privacyLoaded = true
        appUpdateManager.checkForUpdates(force = false)
    }

    val activityLifecycleOwner = context as? LifecycleOwner
    DisposableEffect(privacy.appLockEnabled, activeSession?.sessionId, activityLifecycleOwner) {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val processObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    appUpdateManager.checkForUpdates(force = false)
                    if (activeSession != null) {
                        activityLifecycleOwner?.lifecycleScope?.launch {
                            chatRepository.reconnectAndSyncNow()
                            profileRepository.refreshProfiles()
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (privacy.appLockEnabled) {
                        isLocked = true
                    }
                }
                else -> Unit
            }
        }
        processLifecycle.addObserver(processObserver)

        val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                appUpdateManager.onHostResumed()
            }
        }
        activityLifecycleOwner?.lifecycle?.addObserver(resumeObserver)

        onDispose {
            processLifecycle.removeObserver(processObserver)
            activityLifecycleOwner?.lifecycle?.removeObserver(resumeObserver)
        }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Ensure realtime observers, WorkManager sync recovery, and avatar sync run after fresh sign-in
    LaunchedEffect(activeSession?.sessionId) {
        if (activeSession != null) {
            chatRepository.startRealtimeObserver()
            if (FeatureFlags.CALLS_ENABLED) {
                callManager.startSignalingObserver()
            }
            backgroundDeliveryProvider.startDelivery()
            backgroundDeliveryProvider.requestImmediateSync()
            profileRepository.refreshProfiles()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            backgroundDeliveryProvider.stopDelivery()
            isLocked = false
        }
    }

    var pendingCallType by remember { mutableStateOf<CallType?>(null) }
    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            when (pendingCallType) {
                CallType.AUDIO -> callViewModel.startVoiceCall()
                CallType.VIDEO -> callViewModel.startVideoCall()
                null -> Unit
            }
        }
        pendingCallType = null
    }

    fun launchCallWithPermissions(type: CallType) {
        val needMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val needCam = type == CallType.VIDEO && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        if (!needMic && !needCam) {
            if (type == CallType.VIDEO) callViewModel.startVideoCall() else callViewModel.startVoiceCall()
        } else {
            pendingCallType = type
            val req = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (type == CallType.VIDEO) add(Manifest.permission.CAMERA)
            }.toTypedArray()
            callPermLauncher.launch(req)
        }
    }

    // Automatically return to login if session becomes null while authenticated screens are open
    LaunchedEffect(activeSession) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (activeSession == null && currentRoute != null && currentRoute !in setOf("splash", "login", "recover")) {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "splash") {
            composable("splash") {
                SplashScreen(
                    hasActiveSession = activeSession != null,
                    onNavigateToLogin = {
                        navController.navigate("login") {
                            popUpTo("splash") { inclusive = true }
                        }
                    },
                    onNavigateToHome = {
                        navController.navigate("home") {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                )
            }

            composable("login") {
                val state by authViewModel.loginState.collectAsState()
                LoginScreen(
                    state = state,
                    onUsernameChange = authViewModel::updateUsername,
                    onPasswordChange = authViewModel::updatePassword,
                    onServerUrlChange = authViewModel::updateServerUrl,
                    onTogglePasswordVisibility = authViewModel::togglePasswordVisibility,
                    onToggleServerSettings = authViewModel::toggleServerSettings,
                    onSubmitLogin = {
                        authViewModel.submitLogin {
                            navController.navigate("home") {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                    },
                    onOpenRecovery = { navController.navigate("recover") },
                    onResetDeviceIdentity = authViewModel::rotateDeviceIdentity
                )
            }

            composable("recover") {
                val state by authViewModel.recoveryState.collectAsState()
                AccountRecoveryScreen(
                    state = state,
                    onUsernameChange = authViewModel::updateRecUsername,
                    onRecoveryCodeChange = authViewModel::updateRecCode,
                    onNewPasswordChange = authViewModel::updateRecNewPass,
                    onConfirmPasswordChange = authViewModel::updateRecConfirmPass,
                    onServerUrlChange = authViewModel::updateRecServerUrl,
                    onSubmit = authViewModel::submitAccountRecovery,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable("change_password") {
                val state by authViewModel.recoveryState.collectAsState()
                ChangePasswordScreen(
                    state = state,
                    onCurrentPasswordChange = authViewModel::updateRecCurrentPass,
                    onNewPasswordChange = authViewModel::updateRecNewPass,
                    onConfirmPasswordChange = authViewModel::updateRecConfirmPass,
                    onSubmit = authViewModel::submitPasswordChange,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable("sessions") {
                val state by authViewModel.sessionsState.collectAsState()
                SessionsScreen(
                    state = state,
                    onRefresh = authViewModel::loadSessions,
                    onRevokeSession = authViewModel::revokeSession,
                    onRevokeDevice = authViewModel::revokeDevice,
                    onApproveDevice = authViewModel::approvePendingDevice,
                    onRejectDevice = authViewModel::rejectPendingDevice,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable("home") {
                val homeViewModel: ConversationHomeViewModel = hiltViewModel()
                val homeState by homeViewModel.uiState.collectAsState()

                ConversationHomeScreen(
                    state = homeState,
                    onOpenConversation = { navController.navigate("chat") },
                    onOpenCallsHistory = { navController.navigate("call_history") },
                    onOpenGallery = { navController.navigate("gallery") },
                    onOpenBackup = { navController.navigate("backup") },
                    onOpenProfile = { navController.navigate("profile") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenSessions = { navController.navigate("sessions") },
                    availableUpdateTag = settingsState.updateState.availableRelease?.tagName,
                    onOpenUpdates = { navController.navigate("updates") }
                )
            }

            composable("chat") {
                val chatViewModel: ChatViewModel = hiltViewModel()
                val chatState by chatViewModel.uiState.collectAsState()

                ChatScreen(
                    state = chatState,
                    onBackClick = { navController.popBackStack() },
                    onDraftChange = chatViewModel::onDraftChange,
                    onSendClick = chatViewModel::sendCurrentDraft,
                    onReplyClick = chatViewModel::startReply,
                    onEditClick = chatViewModel::startEdit,
                    onClearComposerMode = chatViewModel::clearReplyOrEdit,
                    onToggleSearch = chatViewModel::toggleSearch,
                    onSearchQueryChange = chatViewModel::updateSearchQuery,
                    onReactMessage = chatViewModel::reactToMessage,
                    onTogglePin = chatViewModel::togglePin,
                    onForwardMessage = chatViewModel::forwardMessage,
                    onDeleteForMe = chatViewModel::deleteForMe,
                    onDeleteForEveryone = chatViewModel::deleteForEveryone,
                    onRetryMessage = chatViewModel::retryMessage,
                    onSetDisappearingTimer = chatViewModel::setDisappearingTimer,
                    onToggleMute = chatViewModel::toggleMute,
                    onOpenSafetyNumber = chatViewModel::loadSafetyNumber,
                    onToggleVerified = chatViewModel::togglePartnerVerified,
                    onStartVoiceCall = { launchCallWithPermissions(CallType.AUDIO) },
                    onStartVideoCall = { launchCallWithPermissions(CallType.VIDEO) },
                    onCreateCameraUri = chatViewModel::createCameraCaptureUri,
                    onGetFileProviderUri = chatViewModel::getFileProviderUri,
                    onSendMediaUri = chatViewModel::sendMediaUri,
                    onDownloadAttachment = { att -> chatViewModel.downloadAttachment(att) },
                    onSaveAttachment = chatViewModel::saveAttachmentToDownloads,
                    onStartVoiceRecording = chatViewModel::startVoiceRecording,
                    onStopAndSendVoice = chatViewModel::stopAndSendVoiceRecording,
                    onCancelVoice = chatViewModel::cancelVoiceRecording,
                    onToggleVoicePlay = chatViewModel::toggleVoicePlayback,
                    onCycleVoiceSpeed = chatViewModel::cycleVoiceSpeed,
                    onSeekVoice = chatViewModel::seekVoice,
                    onOpenMediaGallery = { navController.navigate("gallery") },
                    onDismissBanner = chatViewModel::dismissBanner,
                    onTrustNewSecurityKey = chatViewModel::trustUpdatedPartnerKeyAndResync
                )
            }

            composable("gallery") {
                val galleryViewModel: MediaGalleryViewModel = hiltViewModel()
                val galleryState by galleryViewModel.uiState.collectAsState()

                MediaGalleryScreen(
                    state = galleryState,
                    onBackClick = { navController.popBackStack() },
                    onDownloadClick = galleryViewModel::downloadAttachment,
                    onSaveToDownloadsClick = galleryViewModel::saveToDownloads,
                    onDeleteLocalClick = galleryViewModel::deleteLocalCache,
                    onClearStatus = galleryViewModel::clearStatus
                )
            }

            composable("call_history") {
                val historyState by callViewModel.historyUiState.collectAsState()
                CallHistoryScreen(
                    state = historyState,
                    onBackClick = { navController.popBackStack() },
                    onStartVoiceCall = { launchCallWithPermissions(CallType.AUDIO) },
                    onStartVideoCall = { launchCallWithPermissions(CallType.VIDEO) },
                    onPlayRecording = callViewModel::openRecordingPlayback,
                    onClosePlayback = callViewModel::closeRecordingPlayback,
                    onDeleteRecording = callViewModel::deleteRecording,
                    onClearNotice = callViewModel::clearNotice
                )
            }

            composable("profile") {
                val profileViewModel: ProfileViewModel = hiltViewModel()
                val profileState by profileViewModel.uiState.collectAsState()
                ProfileScreen(
                    state = profileState,
                    onBackClick = { navController.popBackStack() },
                    onRefresh = profileViewModel::refresh,
                    onNameChange = profileViewModel::updateDraftName,
                    onAboutChange = profileViewModel::updateDraftAbout,
                    onSaveProfile = profileViewModel::saveProfile,
                    onAvatarPicked = profileViewModel::uploadAvatarUri,
                    onRemoveAvatar = profileViewModel::removeAvatar,
                    onOpenChangePassword = { navController.navigate("change_password") },
                    onOpenSessions = { navController.navigate("sessions") },
                    onOpenSafetyNumber = { navController.navigate("safety_number") }
                )
            }

            composable("settings") {
                SettingsScreen(
                    state = settingsState,
                    onBackClick = { navController.popBackStack() },
                    onOpenProfile = { navController.navigate("profile") },
                    onOpenAppearance = { navController.navigate("appearance") },
                    onOpenNotifications = { navController.navigate("notifications") },
                    onOpenPrivacy = { navController.navigate("privacy") },
                    onOpenSafetyNumber = { navController.navigate("safety_number") },
                    onOpenBackup = { navController.navigate("backup") },
                    onOpenSessions = { navController.navigate("sessions") },
                    onOpenChangePassword = { navController.navigate("change_password") },
                    onLogoutConfirmed = {
                        authViewModel.logout {
                            isLocked = false
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    onOpenUpdates = { navController.navigate("updates") }
                )
            }

            composable("updates") {
                AppUpdatesScreen(
                    updateState = settingsState.updateState,
                    onBackClick = { navController.popBackStack() },
                    onSaveRepo = settingsViewModel::saveGithubUpdateRepo,
                    onToggleAutoCheck = settingsViewModel::setAutoCheckUpdates,
                    onCheckNow = settingsViewModel::checkForUpdatesNow,
                    onDownloadAndInstall = settingsViewModel::downloadAndInstallUpdate,
                    onInstallDownloaded = settingsViewModel::installDownloadedUpdate,
                    onRequestInstallPermission = {
                        context.startActivity(appUpdateManager.createUnknownSourcesSettingsIntent())
                    },
                    onClearNotices = {
                        settingsViewModel.clearNotice()
                        settingsViewModel.clearUpdateNotices()
                    }
                )
            }

            composable("appearance") {
                AppearanceSettingsScreen(
                    state = settingsState,
                    onBackClick = { navController.popBackStack() },
                    onThemeMode = settingsViewModel::setThemeMode,
                    onAccentHex = settingsViewModel::setAccentHex,
                    onOutgoingHex = settingsViewModel::setOutgoingBubbleHex,
                    onIncomingHex = settingsViewModel::setIncomingBubbleHex,
                    onCornerRadius = settingsViewModel::setCornerRadius,
                    onFontScale = settingsViewModel::setFontScale,
                    onWallpaper = settingsViewModel::setWallpaper
                )
            }

            composable("notifications") {
                NotificationSettingsScreen(
                    state = settingsState,
                    onBackClick = { navController.popBackStack() },
                    onStealthChange = settingsViewModel::setStealthNotification,
                    onPreviewChange = settingsViewModel::setShowPreviews,
                    onSoundChange = settingsViewModel::setMessageSound,
                    onRingtoneChange = settingsViewModel::setCallRingtone,
                    onVibrateChange = settingsViewModel::setVibrate,
                    onMuteChange = settingsViewModel::setMuteConversation,
                    onDndChange = settingsViewModel::setDnd
                )
            }

            composable("privacy") {
                PrivacyAndSecurityScreen(
                    state = settingsState,
                    onBackClick = { navController.popBackStack() },
                    onReadReceipts = settingsViewModel::setReadReceipts,
                    onTypingIndicators = settingsViewModel::setTypingIndicators,
                    onShowOnline = settingsViewModel::setShowOnline,
                    onShowLastSeen = settingsViewModel::setShowLastSeen,
                    onScreenshotProtection = settingsViewModel::setScreenshotProtection,
                    onDisappearingTimer = settingsViewModel::setDisappearingTimer,
                    onSetupPin = settingsViewModel::configureAppPin,
                    onDisableAppLock = settingsViewModel::disableAppLock,
                    onBiometricLock = settingsViewModel::setBiometricLock,
                    onOpenSafetyNumber = { navController.navigate("safety_number") },
                    onClearNotice = settingsViewModel::clearNotice
                )
            }

            composable("safety_number") {
                SafetyNumberScreen(
                    state = settingsState,
                    onLoad = settingsViewModel::refreshSafetyNumber,
                    onToggleVerified = settingsViewModel::setPartnerVerified,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable("backup") {
                val backupViewModel: BackupViewModel = hiltViewModel()
                val backupState by backupViewModel.uiState.collectAsState()
                BackupScreen(
                    state = backupState,
                    onBackClick = { navController.popBackStack() },
                    onImportRecoveryKey = backupViewModel::importRecoveryKey,
                    onCreateLocalSnapshot = backupViewModel::createLocalEncryptedSnapshot,
                    onRestoreLocalSnapshot = backupViewModel::restoreLocalSnapshot,
                    onClearNotices = backupViewModel::clearNotices
                )
            }
        }

        val appLockVisible = privacyLoaded && activeSession != null && privacy.appLockEnabled && isLocked

        // Non-intrusive GitHub Release update prompt dialog when unlocked
        if (!appLockVisible) {
            UpdateAvailablePromptDialog(
                updateState = settingsState.updateState,
                onDismissLater = settingsViewModel::dismissUpdateDialog,
                onSkipRelease = settingsViewModel::skipAvailableUpdate,
                onOpenUpdates = { navController.navigate("updates") },
                onDownloadNow = settingsViewModel::downloadAndInstallUpdate
            )
        }

        // Global App Lock Gate (covers vault when authenticated & locked)
        if (appLockVisible) {
            AppLockOverlayScreen(
                biometricEnabled = privacy.biometricLockEnabled,
                onVerifyPin = settingsViewModel::verifyPin,
                onUnlocked = { isLocked = false }
            )
        }

        // Global Full-Screen Encrypted Call & Manual Recording HUD Overlay
        if (FeatureFlags.CALLS_ENABLED && activeCall.phase != CallPhase.IDLE) {
            ActiveCallOverlayScreen(
                callState = activeCall,
                recordingState = recordingState,
                eglContext = callViewModel.getEglContext(),
                onAttachLocalSink = callViewModel::attachLocalSink,
                onDetachLocalSink = callViewModel::detachLocalSink,
                onAttachRemoteSink = callViewModel::attachRemoteSink,
                onDetachRemoteSink = callViewModel::detachRemoteSink,
                onAcceptCall = callViewModel::acceptCall,
                onRejectCall = callViewModel::rejectCall,
                onHangup = callViewModel::hangup,
                onToggleMute = callViewModel::toggleMute,
                onToggleSpeaker = callViewModel::toggleSpeaker,
                onToggleCamera = callViewModel::toggleCamera,
                onSwitchCamera = callViewModel::switchCamera,
                onStartRecording = callViewModel::startManualRecording,
                onPauseRecording = callViewModel::pauseRecording,
                onResumeRecording = callViewModel::resumeRecording,
                onStopRecording = callViewModel::stopRecording
            )
        }
    }
}
