package com.systemutility.feature.chat

import android.content.Context
import com.systemutility.core.database.dao.MediaDao
import com.systemutility.core.database.dao.MessageDao
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.database.entity.ConversationEntity
import com.systemutility.core.database.entity.MessageEntity
import com.systemutility.core.database.entity.OutboxEntity
import com.systemutility.core.database.entity.ReactionEntity
import com.systemutility.core.database.entity.UserEntity
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.database.signal.RemoteBundleInput
import com.systemutility.core.database.signal.SignalCryptoEngine
import com.systemutility.core.di.ApplicationScope
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.model.EncryptedAttachmentMeta
import com.systemutility.core.model.EncryptedEnvelopePayload
import com.systemutility.core.model.MediaCategory
import com.systemutility.core.model.NotificationPreferences
import com.systemutility.feature.media.EncryptedMediaRepository
import java.io.File
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.api.safeApiCall
import com.systemutility.core.network.dto.AckReceiptRequest
import com.systemutility.core.network.dto.EncryptedMessageDto
import com.systemutility.core.network.dto.KyberPreKeyDto
import com.systemutility.core.network.dto.OneTimePreKeyDto
import com.systemutility.core.network.dto.SendMessageRequest
import com.systemutility.core.network.dto.UploadPreKeysRequest
import com.systemutility.core.network.ws.RealtimeWebSocketClient
import com.systemutility.core.network.ws.WsConnectionState
import com.systemutility.core.result.AppResult
import com.systemutility.core.security.SecureSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.UntrustedIdentityException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SystemUtilityApi,
    private val sessionStore: SecureSessionStore,
    private val userDao: UserDao,
    private val messageDao: MessageDao,
    private val mediaDao: MediaDao,
    private val prefsDataStore: UserPreferencesDataStore,
    private val signalEngine: SignalCryptoEngine,
    private val wsClient: RealtimeWebSocketClient,
    private val mediaRepository: EncryptedMediaRepository,
    private val notificationHelper: ChatNotificationHelper,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val started = AtomicBoolean(false)
    private val syncMutex = Mutex()
    private val outboxMutex = Mutex()
    private val decryptMutex = Mutex()

    private val _partnerOnline = MutableStateFlow(false)
    val partnerOnline: StateFlow<Boolean> = _partnerOnline.asStateFlow()

    private val _partnerTyping = MutableStateFlow(false)
    val partnerTyping: StateFlow<Boolean> = _partnerTyping.asStateFlow()

    private val _isChatVisible = MutableStateFlow(false)
    private var typingClearJob: Job? = null
    @Volatile
    private var lastSyncedPartnerSignalDeviceId = 1

    fun startRealtimeObserver() {
        if (!started.compareAndSet(false, true)) return

        appScope.launch(ioDispatcher) {
            lastSyncedPartnerSignalDeviceId = prefsDataStore.getPartnerSignalDeviceIdOnce()
            if (sessionStore.getSession() != null) {
                ensurePartnerProfileLoaded()
                uploadPreKeysIfNeeded()
                syncFromServer()
                flushOutbox(triggerRetryWorker = true)
                purgeExpiredMessages()
            }
        }

        appScope.launch(ioDispatcher) {
            wsClient.connectionState.collect { state ->
                if (state == WsConnectionState.CONNECTED && sessionStore.getSession() != null) {
                    ensurePartnerProfileLoaded()
                    uploadPreKeysIfNeeded()
                    syncFromServer()
                    flushOutbox(triggerRetryWorker = true)
                    purgeExpiredMessages()
                }
            }
        }

        appScope.launch(ioDispatcher) {
            wsClient.incomingEvents.collect { event ->
                val type = event["type"]?.jsonPrimitive?.content ?: return@collect
                when (type) {
                    "presence" -> {
                        val online = event["online"]?.jsonPrimitive?.booleanOrNull ?: false
                        _partnerOnline.value = online
                        if (!online) _partnerTyping.value = false
                    }

                    "typing" -> {
                        val isTyping = event["isTyping"]?.jsonPrimitive?.booleanOrNull ?: false
                        _partnerTyping.value = isTyping
                        typingClearJob?.cancel()
                        if (isTyping) {
                            typingClearJob = appScope.launch {
                                delay(4_500L)
                                _partnerTyping.value = false
                            }
                        }
                    }

                    "encrypted_message" -> {
                        val msgJson = event["message"]?.jsonObject ?: return@collect
                        val dto = json.decodeFromJsonElement(EncryptedMessageDto.serializer(), msgJson)
                        processIncomingDtoAndAdvanceSeq(dto, fromPushOrRealtime = true)
                    }

                    "message_edited" -> {
                        val msgJson = event["message"]?.jsonObject ?: return@collect
                        val dto = json.decodeFromJsonElement(EncryptedMessageDto.serializer(), msgJson)
                        processIncomingDtoAndAdvanceSeq(dto, fromPushOrRealtime = true)
                    }

                    "message_deleted" -> {
                        val msgId = event["messageId"]?.jsonPrimitive?.content ?: return@collect
                        val ts = event["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val serverSeq = event["serverSeq"]?.jsonPrimitive?.longOrNull ?: 0L
                        messageDao.markDeleted(msgId, ts)
                        advanceSeqIfContiguous(serverSeq)
                    }

                    "receipt_update" -> {
                        val status = event["status"]?.jsonPrimitive?.content ?: return@collect
                        val ts = event["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val ids = event["messageIds"]?.jsonArray?.mapNotNull {
                            runCatching { it.jsonPrimitive.content }.getOrNull()
                        }.orEmpty()
                        ids.forEach { messageDao.updateDeliveryStatus(it, status, ts) }
                    }
                }
            }
        }

        // Periodic disappearing message sweeper
        appScope.launch(ioDispatcher) {
            while (true) {
                delay(15_000L)
                purgeExpiredMessages()
            }
        }
    }

    suspend fun reconnectAndSyncNow() = withContext(ioDispatcher) {
        if (sessionStore.getSession() == null) return@withContext
        wsClient.connect()
        ensurePartnerProfileLoaded()
        uploadPreKeysIfNeeded()
        syncFromServer()
        flushOutbox(triggerRetryWorker = true)
        purgeExpiredMessages()
    }

    suspend fun ensurePartnerProfileLoaded() = withContext(ioDispatcher) {
        if (sessionStore.getSession() == null) return@withContext
        if (userDao.getPartnerUser() != null) return@withContext
        val res = safeApiCall { api.getProfiles() }
        if (res is AppResult.Success) {
            res.data.partnerUser?.let { partner ->
                var localAvatar: String? = null
                if (!partner.avatarR2Key.isNullOrBlank() && !partner.avatarKeyBase64.isNullOrBlank()) {
                    val dl = mediaRepository.downloadAndDecryptAvatar(
                        partner.avatarR2Key!!,
                        partner.avatarKeyBase64!!,
                        partner.id
                    )
                    if (dl is AppResult.Success) {
                        localAvatar = dl.data.absolutePath
                    }
                }
                userDao.upsertUser(
                    UserEntity(
                        id = partner.id,
                        slotId = partner.slotId,
                        username = partner.username,
                        displayName = partner.displayName,
                        about = partner.about,
                        encryptionIdentityId = partner.encryptionIdentityId,
                        avatarLocalPath = localAvatar,
                        avatarR2Key = partner.avatarR2Key,
                        avatarKeyBase64 = partner.avatarKeyBase64,
                        lastSeenAt = partner.lastSeenAt,
                        privacyShowLastSeen = partner.privacyShowLastSeen,
                        privacyShowOnline = partner.privacyShowOnline,
                        isSelf = false
                    )
                )
                val existingConv = userDao.getPrimaryConversation()
                userDao.upsertConversation(
                    existingConv?.copy(partnerUserId = partner.id)
                        ?: ConversationEntity(id = "primary_pair", partnerUserId = partner.id)
                )
            }
        }
    }

    fun observeMessages(): Flow<List<MessageEntity>> = messageDao.observeAllMessages()
    fun observeReactions(): Flow<List<ReactionEntity>> = messageDao.observeAllReactions()
    fun observePinnedMessage(): Flow<MessageEntity?> = messageDao.observeLatestPinnedMessage()
    fun observeAttachments(): Flow<List<AttachmentEntity>> = mediaDao.observeAllAttachments()

    fun setChatVisible(visible: Boolean) {
        _isChatVisible.value = visible
        if (visible) {
            notificationHelper.cancelMessageNotification()
            appScope.launch(ioDispatcher) {
                markAllPartnerMessagesRead()
            }
        }
    }

    suspend fun uploadPreKeysIfNeeded() = withContext(ioDispatcher) {
        if (sessionStore.getSession() == null) return@withContext
        val bundle = signalEngine.prepareLocalBundle(minOneTimeCount = 20)
        val req = UploadPreKeysRequest(
            registrationId = bundle.registrationId,
            identityKeyB64 = bundle.identityKeyB64,
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyB64 = bundle.signedPreKeyB64,
            signedPreKeySignatureB64 = bundle.signedPreKeySignatureB64,
            kyberLastResortId = bundle.kyberLastResortId,
            kyberLastResortB64 = bundle.kyberLastResortB64,
            kyberLastResortSigB64 = bundle.kyberLastResortSigB64,
            oneTimePreKeys = bundle.oneTimePreKeys.map { OneTimePreKeyDto(it.keyId, it.publicKeyB64) },
            kyberPreKeys = bundle.kyberPreKeys.map { KyberPreKeyDto(it.keyId, it.publicKeyB64, it.signatureB64) }
        )
        safeApiCall { api.uploadPreKeys(req) }
    }

    suspend fun sendTextMessage(
        body: String,
        replyToMessageId: String? = null,
        isForwarded: Boolean = false
    ): AppResult<Unit> = sendStructuredMessage(
        kind = "TEXT",
        body = body,
        replyToMessageId = replyToMessageId,
        isForwarded = isForwarded,
        attachmentMeta = null
    )

    suspend fun sendEncryptedMediaMessage(
        sourceUri: android.net.Uri?,
        sourceFile: File?,
        category: MediaCategory,
        captionOrLabel: String = "",
        durationMs: Long = 0L,
        waveformCsv: String = "",
        replyToMessageId: String? = null
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val preMessageId = UUID.randomUUID().toString()
        val prep = mediaRepository.encryptAndUploadMedia(
            messageId = preMessageId,
            sourceUri = sourceUri,
            sourceFile = sourceFile,
            category = category,
            durationMs = durationMs,
            waveformCsv = waveformCsv
        )
        sourceFile?.delete()
        val staged = when (prep) {
            is AppResult.Error -> return@withContext prep
            is AppResult.Success -> prep.data
        }
        val kind = when (category) {
            MediaCategory.IMAGE -> "IMAGE"
            MediaCategory.VIDEO -> "VIDEO"
            MediaCategory.VOICE -> "VOICE"
            MediaCategory.AUDIO -> "AUDIO"
            MediaCategory.DOCUMENT, MediaCategory.AVATAR -> "DOCUMENT"
        }
        val label = captionOrLabel.ifBlank { staged.entity.fileName }
        sendStructuredMessage(
            kind = kind,
            body = label,
            replyToMessageId = replyToMessageId,
            isForwarded = false,
            attachmentMeta = staged.meta,
            explicitMessageId = preMessageId
        )
    }

    suspend fun downloadAttachment(attachment: AttachmentEntity): AppResult<File> {
        return mediaRepository.downloadAndDecrypt(attachment)
    }

    suspend fun sendStructuredMessage(
        kind: String,
        body: String,
        replyToMessageId: String? = null,
        isForwarded: Boolean = false,
        attachmentMeta: EncryptedAttachmentMeta? = null,
        explicitMessageId: String? = null
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val self = sessionStore.getSession()
            ?: return@withContext AppResult.Error("UNAUTHENTICATED", "Not signed in.")
        if (userDao.getPartnerUser() == null) {
            ensurePartnerProfileLoaded()
        }
        val partner = userDao.getPartnerUser()
            ?: return@withContext AppResult.Error("NO_PARTNER", "Partner profile not loaded yet. Make sure both private accounts are provisioned.")

        val now = System.currentTimeMillis()
        val privacy = prefsDataStore.privacyFlow.first()
        val disappearSec = privacy.disappearingTimerSeconds.takeIf { it > 0L }
        val disappearsAt = disappearSec?.let { now + it * 1000L }

        val msgId = explicitMessageId ?: UUID.randomUUID().toString()
        val replyTarget = replyToMessageId?.let { messageDao.getById(it) }

        val entity = MessageEntity(
            id = msgId,
            conversationId = "primary_pair",
            senderUserId = self.userId,
            recipientUserId = partner.id,
            kind = kind,
            body = body,
            status = "QUEUED",
            replyToMessageId = replyToMessageId,
            replyPreviewText = replyTarget?.body?.take(80),
            isForwarded = isForwarded,
            disappearsAt = disappearsAt,
            createdAt = now,
            updatedAt = now
        )

        messageDao.upsertMessage(entity)
        val preview = when (kind) {
            "IMAGE" -> "Photo"
            "VIDEO" -> "Video"
            "VOICE" -> "Voice message"
            "AUDIO" -> "Audio"
            "DOCUMENT" -> "Document"
            else -> body
        }
        userDao.updateConversationPreview(msgId, preview, now)
        messageDao.enqueueOutbox(OutboxEntity(messageId = msgId, attemptCount = 0, nextAttemptAt = now))

        dispatchOutboxMessage(msgId, attachmentMeta, triggerRetryWorker = true)
    }

    suspend fun retryFailedMessage(messageId: String): AppResult<Unit> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        messageDao.enqueueOutbox(OutboxEntity(messageId = messageId, attemptCount = 0, nextAttemptAt = now))
        dispatchOutboxMessage(messageId, null, triggerRetryWorker = true)
    }

    suspend fun trustNewPartnerIdentityAndResetSession(partnerId: String) = withContext(ioDispatcher) {
        val deviceId = prefsDataStore.getPartnerSignalDeviceIdOnce()
        signalEngine.resetPartnerSessionAndIdentity(partnerId, deviceId)
        signalEngine.setPartnerIdentityVerified(partnerId, deviceId, verified = false)
        syncFromServer()
        flushOutbox(triggerRetryWorker = true)
    }

    private suspend fun ensurePartnerSession(partnerId: String): AppResult<Int> {
        val persistedDeviceId = prefsDataStore.getPartnerSignalDeviceIdOnce()
        lastSyncedPartnerSignalDeviceId = persistedDeviceId
        if (signalEngine.hasSession(partnerId, persistedDeviceId)) {
            return AppResult.Success(persistedDeviceId)
        }
        val res = safeApiCall { api.fetchPartnerPreKeyBundle() }
        val dto = when (res) {
            is AppResult.Error -> {
                if (res.code == "PARTNER_KEYS_UNAVAILABLE") {
                    return AppResult.Error(
                        code = res.code,
                        message = "Waiting for your partner to sign in once so E2EE keys can exchange. Message queued safely."
                    )
                }
                return res
            }
            is AppResult.Success -> res.data
        }
        lastSyncedPartnerSignalDeviceId = dto.deviceId
        prefsDataStore.setPartnerSignalDeviceId(dto.deviceId)
        return runCatching {
            signalEngine.processPartnerBundle(
                RemoteBundleInput(
                    userId = dto.userId,
                    deviceId = dto.deviceId,
                    registrationId = dto.registrationId,
                    identityKeyB64 = dto.identityKeyB64,
                    signedPreKeyId = dto.signedPreKeyId,
                    signedPreKeyB64 = dto.signedPreKeyB64,
                    signedPreKeySignatureB64 = dto.signedPreKeySignatureB64,
                    oneTimePreKeyId = dto.oneTimePreKey?.keyId,
                    oneTimePreKeyB64 = dto.oneTimePreKey?.publicKeyB64,
                    kyberPreKeyId = dto.kyberPreKey.keyId,
                    kyberPreKeyB64 = dto.kyberPreKey.publicKeyB64,
                    kyberPreKeySignatureB64 = dto.kyberPreKey.signatureB64
                )
            )
            AppResult.Success(dto.deviceId)
        }.getOrElse { err ->
            AppResult.Error("SIGNAL_HANDSHAKE_FAILED", err.message ?: "Failed to establish Signal session.")
        }
    }

    private suspend fun dispatchOutboxMessage(
        messageId: String,
        explicitAttachmentMeta: EncryptedAttachmentMeta?,
        triggerRetryWorker: Boolean = true
    ): AppResult<Unit> {
        val msg = messageDao.getById(messageId) ?: return AppResult.Success(Unit)
        val deviceIdRes = ensurePartnerSession(msg.recipientUserId)
        val targetSignalDeviceId = when (deviceIdRes) {
            is AppResult.Error -> {
                scheduleOutboxRetry(msg.id, deviceIdRes.message, triggerRetryWorker)
                return deviceIdRes
            }
            is AppResult.Success -> deviceIdRes.data
        }

        val attachmentMeta = explicitAttachmentMeta ?: mediaDao.getByMessageId(msg.id).firstOrNull()?.let { att ->
            att.r2ObjectKey?.let { key ->
                EncryptedAttachmentMeta(
                    attachmentId = att.id,
                    category = att.category,
                    fileName = att.fileName,
                    mimeType = att.mimeType,
                    sizeBytes = att.sizeBytes,
                    r2ObjectKey = key,
                    fileKeyBase64 = att.fileKeyBase64,
                    baseIvBase64 = att.baseIvBase64,
                    sha256CiphertextHex = att.sha256CiphertextHex,
                    durationMs = att.durationMs,
                    waveformCsv = att.waveformCsv
                )
            }
        }

        val payload = EncryptedEnvelopePayload(
            action = "MESSAGE",
            messageId = msg.id,
            kind = msg.kind,
            body = msg.body,
            isForwarded = msg.isForwarded,
            expiresInSeconds = msg.disappearsAt?.let { ((it - msg.createdAt) / 1000L).coerceAtLeast(1L) },
            attachment = attachmentMeta
        )

        val encrypted = runCatching {
            signalEngine.encryptEnvelope(msg.recipientUserId, targetSignalDeviceId, payload)
        }.getOrElse { err ->
            scheduleOutboxRetry(msg.id, err.message, triggerRetryWorker)
            return AppResult.Error("ENCRYPT_FAILED", err.message ?: "Signal encryption error.")
        }

        val sendRes = safeApiCall {
            api.sendMessage(
                SendMessageRequest(
                    id = msg.id,
                    recipientUserId = msg.recipientUserId,
                    messageType = encrypted.messageType,
                    ciphertextB64 = encrypted.ciphertextB64,
                    replyToMessageId = msg.replyToMessageId,
                    disappearsAt = msg.disappearsAt
                )
            )
        }

        return when (sendRes) {
            is AppResult.Success -> {
                messageDao.markSent(msg.id, "SENT", sendRes.data.serverSeq, sendRes.data.updatedAt)
                messageDao.removeOutbox(msg.id)
                prefsDataStore.advanceLastSyncedServerSeq(sendRes.data.serverSeq)
                AppResult.Success(Unit)
            }
            is AppResult.Error -> {
                scheduleOutboxRetry(msg.id, sendRes.message, triggerRetryWorker)
                sendRes
            }
        }
    }

    private suspend fun scheduleOutboxRetry(messageId: String, error: String?, triggerRetryWorker: Boolean) {
        val next = System.currentTimeMillis() + 15_000L
        messageDao.scheduleOutboxRetry(messageId, next, error)
        if (triggerRetryWorker) {
            MessageSyncWorker.enqueueDelayedRetry(context, 15L)
        }
    }

    suspend fun flushOutbox(triggerRetryWorker: Boolean = true): Boolean = withContext(ioDispatcher) {
        outboxMutex.withLock {
            if (sessionStore.getSession() == null) return@withLock true
            val due = messageDao.getDueOutboxItems(System.currentTimeMillis() + 60_000L)
            var allSucceeded = true
            for (item in due) {
                val res = dispatchOutboxMessage(item.messageId, null, triggerRetryWorker = false)
                if (res is AppResult.Error) {
                    allSucceeded = false
                }
            }
            if (!allSucceeded && triggerRetryWorker) {
                MessageSyncWorker.enqueueDelayedRetry(context, 15L)
            }
            allSucceeded
        }
    }

    suspend fun editMessage(messageId: String, newBody: String): AppResult<Unit> = withContext(ioDispatcher) {
        val msg = messageDao.getById(messageId)
            ?: return@withContext AppResult.Error("NOT_FOUND", "Message not found.")
        val self = sessionStore.getSession()
            ?: return@withContext AppResult.Error("UNAUTHENTICATED", "Not signed in.")
        if (msg.senderUserId != self.userId || msg.isDeleted) {
            return@withContext AppResult.Error("FORBIDDEN", "Cannot edit this message.")
        }

        val now = System.currentTimeMillis()
        messageDao.editMessageBody(messageId, newBody, now)

        val partnerDevice = when (val r = ensurePartnerSession(msg.recipientUserId)) {
            is AppResult.Error -> return@withContext r
            is AppResult.Success -> r.data
        }

        val enc = signalEngine.encryptEnvelope(
            msg.recipientUserId,
            partnerDevice,
            EncryptedEnvelopePayload(
                action = "EDIT",
                messageId = UUID.randomUUID().toString(),
                targetMessageId = messageId,
                body = newBody
            )
        )

        safeApiCall {
            api.editMessage(
                id = messageId,
                body = SendMessageRequest(
                    id = messageId,
                    recipientUserId = msg.recipientUserId,
                    messageType = enc.messageType,
                    ciphertextB64 = enc.ciphertextB64
                )
            )
        }
    }

    suspend fun deleteForMe(messageId: String) = withContext(ioDispatcher) {
        messageDao.markDeleted(messageId, System.currentTimeMillis())
    }

    suspend fun deleteForEveryone(messageId: String): AppResult<Unit> = withContext(ioDispatcher) {
        messageDao.markDeleted(messageId, System.currentTimeMillis())
        safeApiCall { api.deleteMessage(messageId) }
    }

    suspend fun togglePin(messageId: String, pinned: Boolean) = withContext(ioDispatcher) {
        messageDao.setPinned(messageId, pinned, System.currentTimeMillis())
    }

    suspend fun toggleReaction(messageId: String, emoji: String) = withContext(ioDispatcher) {
        val self = sessionStore.getSession() ?: return@withContext
        val partner = userDao.getPartnerUser() ?: return@withContext
        val existing = messageDao.observeAllReactions().first()
            .firstOrNull { it.messageId == messageId && it.userId == self.userId }

        val targetEmoji = if (existing?.emoji == emoji) "" else emoji
        val now = System.currentTimeMillis()
        if (targetEmoji.isEmpty()) {
            messageDao.removeReaction(messageId, self.userId)
        } else {
            messageDao.upsertReaction(ReactionEntity(messageId, self.userId, targetEmoji, now))
        }

        val partnerDevice = when (val r = ensurePartnerSession(partner.id)) {
            is AppResult.Error -> return@withContext
            is AppResult.Success -> r.data
        }
        val controlId = UUID.randomUUID().toString()
        val enc = signalEngine.encryptEnvelope(
            partner.id,
            partnerDevice,
            EncryptedEnvelopePayload(
                action = "REACTION",
                messageId = controlId,
                targetMessageId = messageId,
                emoji = targetEmoji
            )
        )
        safeApiCall {
            api.sendMessage(
                SendMessageRequest(
                    id = controlId,
                    recipientUserId = partner.id,
                    messageType = enc.messageType,
                    ciphertextB64 = enc.ciphertextB64
                )
            )
        }
    }

    suspend fun sendTypingIndicator(isTyping: Boolean) = withContext(ioDispatcher) {
        val privacy = prefsDataStore.privacyFlow.first()
        if (!privacy.sendTypingIndicators) return@withContext
        wsClient.sendJson("""{"type":"typing","isTyping":$isTyping}""")
    }

    suspend fun syncFromServer() = withContext(ioDispatcher) {
        syncMutex.withLock {
            if (sessionStore.getSession() == null) return@withLock
            var cursor = maxOf(
                prefsDataStore.getLastSyncedServerSeqOnce(),
                messageDao.getMaxServerSeq()
            )
            while (true) {
                val res = safeApiCall { api.syncMessages(cursor) }
                when (res) {
                    is AppResult.Error -> break
                    is AppResult.Success -> {
                        val batch = res.data.messages
                        if (batch.isEmpty()) {
                            if (res.data.maxSeq > cursor) {
                                prefsDataStore.advanceLastSyncedServerSeq(res.data.maxSeq)
                            }
                            break
                        }
                        for (dto in batch) {
                            processIncomingDto(dto, fromPushOrRealtime = false)
                            prefsDataStore.advanceLastSyncedServerSeq(dto.serverSeq)
                        }
                        val nextCursor = maxOf(res.data.maxSeq, batch.maxOf { it.serverSeq })
                        prefsDataStore.advanceLastSyncedServerSeq(nextCursor)
                        if (batch.size < 200 || nextCursor <= cursor) {
                            break
                        }
                        cursor = nextCursor
                    }
                }
            }
        }
    }

    private suspend fun processIncomingDtoAndAdvanceSeq(dto: EncryptedMessageDto, fromPushOrRealtime: Boolean) {
        val currentSeq = maxOf(prefsDataStore.getLastSyncedServerSeqOnce(), messageDao.getMaxServerSeq())
        if (dto.serverSeq > currentSeq + 1) {
            // Fill any intermediate server sequence gap cleanly in order
            syncFromServer()
            return
        }
        processIncomingDto(dto, fromPushOrRealtime)
        prefsDataStore.advanceLastSyncedServerSeq(dto.serverSeq)
    }

    private suspend fun advanceSeqIfContiguous(serverSeq: Long) {
        if (serverSeq <= 0L) return
        val currentSeq = maxOf(prefsDataStore.getLastSyncedServerSeqOnce(), messageDao.getMaxServerSeq())
        if (serverSeq <= currentSeq + 1) {
            prefsDataStore.advanceLastSyncedServerSeq(serverSeq)
        } else {
            appScope.launch(ioDispatcher) { syncFromServer() }
        }
    }

    private suspend fun processIncomingDto(dto: EncryptedMessageDto, fromPushOrRealtime: Boolean) = decryptMutex.withLock {
        val self = sessionStore.getSession() ?: return
        if (dto.senderUserId == self.userId) {
            // Update own sent message status / deletion if synced
            if (dto.isDeletedForEveryone) {
                messageDao.markDeleted(dto.id, dto.updatedAt)
            } else {
                messageDao.markSent(dto.id, dto.receiptStatus ?: "SENT", dto.serverSeq, dto.updatedAt)
            }
            return
        }

        lastSyncedPartnerSignalDeviceId = dto.senderDeviceId
        prefsDataStore.setPartnerSignalDeviceId(dto.senderDeviceId)

        if (dto.isDeletedForEveryone || dto.ciphertextB64.isEmpty()) {
            messageDao.markDeleted(dto.id, dto.updatedAt)
            refreshConversationPreviewIfLatest(dto.id, "Message deleted", dto.updatedAt)
            return
        }

        val payload = runCatching {
            signalEngine.decryptEnvelope(dto.senderUserId, dto.senderDeviceId, dto.messageType, dto.ciphertextB64)
        }.getOrElse { err ->
            if (err is DuplicateMessageException) {
                // Already decrypted on an earlier WS/sync pass; ignore cleanly.
                return
            }
            SafeLogger.w("ChatCrypto", "Failed decrypting envelope id=${dto.id}: ${err.message}")
            if (messageDao.getById(dto.id) == null) {
                val fallbackBody = if (err is UntrustedIdentityException) {
                    "[Partner security key changed — verify Safety Number]"
                } else {
                    "[Encrypted message could not be decrypted on this session]"
                }
                messageDao.upsertMessage(
                    MessageEntity(
                        id = dto.id,
                        conversationId = "primary_pair",
                        senderUserId = dto.senderUserId,
                        recipientUserId = dto.recipientUserId,
                        kind = "TEXT",
                        body = fallbackBody,
                        status = "DELIVERED",
                        replyToMessageId = dto.replyToMessageId,
                        serverSeq = dto.serverSeq,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt
                    )
                )
            }
            return
        }

        when (payload.action) {
            "REACTION" -> {
                val targetId = payload.targetMessageId ?: return
                val emoji = payload.emoji.orEmpty()
                if (emoji.isEmpty()) {
                    messageDao.removeReaction(targetId, dto.senderUserId)
                } else {
                    messageDao.upsertReaction(ReactionEntity(targetId, dto.senderUserId, emoji, dto.updatedAt))
                }
            }

            "EDIT" -> {
                val targetId = payload.targetMessageId ?: dto.id
                messageDao.editMessageBody(targetId, payload.body, dto.updatedAt)
                refreshConversationPreviewIfLatest(targetId, payload.body, dto.updatedAt)
            }

            else -> {
                if (dto.isEdited) {
                    messageDao.editMessageBody(dto.id, payload.body, dto.updatedAt)
                    refreshConversationPreviewIfLatest(dto.id, payload.body, dto.updatedAt)
                    return
                }

                val existing = messageDao.getById(dto.id)
                if (existing != null) return

                val replyTarget = dto.replyToMessageId?.let { messageDao.getById(it) }
                val messageEntity = MessageEntity(
                    id = dto.id,
                    conversationId = "primary_pair",
                    senderUserId = dto.senderUserId,
                    recipientUserId = dto.recipientUserId,
                    kind = payload.kind,
                    body = payload.body,
                    status = "DELIVERED",
                    replyToMessageId = dto.replyToMessageId,
                    replyPreviewText = replyTarget?.body?.take(80),
                    isForwarded = payload.isForwarded,
                    disappearsAt = dto.disappearsAt ?: payload.expiresInSeconds?.let { dto.createdAt + it * 1000L },
                    serverSeq = dto.serverSeq,
                    createdAt = dto.createdAt,
                    updatedAt = dto.updatedAt
                )
                messageDao.upsertMessage(messageEntity)

                payload.attachment?.let { att ->
                    mediaDao.upsertAttachment(
                        AttachmentEntity(
                            id = att.attachmentId,
                            messageId = dto.id,
                            category = att.category,
                            fileName = att.fileName,
                            mimeType = att.mimeType,
                            sizeBytes = att.sizeBytes,
                            r2ObjectKey = att.r2ObjectKey,
                            fileKeyBase64 = att.fileKeyBase64,
                            baseIvBase64 = att.baseIvBase64,
                            sha256CiphertextHex = att.sha256CiphertextHex,
                            durationMs = att.durationMs,
                            waveformCsv = att.waveformCsv,
                            transferState = "PENDING",
                            progressPercent = 0
                        )
                    )
                }

                val preview = when (payload.kind) {
                    "IMAGE" -> "Photo"
                    "VIDEO" -> "Video"
                    "VOICE" -> "Voice message"
                    "AUDIO" -> "Audio"
                    "DOCUMENT" -> "Document"
                    else -> payload.body
                }
                userDao.updateConversationPreview(dto.id, preview, dto.createdAt)

                if (_isChatVisible.value) {
                    userDao.resetUnread()
                    sendReceipt(listOf(dto.id), "READ")
                } else {
                    userDao.incrementUnread()
                    sendReceipt(listOf(dto.id), "DELIVERED")
                    if (fromPushOrRealtime) {
                        val partner = userDao.getPartnerUser()
                        val notifPrefs: NotificationPreferences = prefsDataStore.notificationFlow.first()
                        notificationHelper.showMessageNotification(
                            prefs = notifPrefs,
                            senderName = partner?.displayName ?: "Partner",
                            messagePreview = preview
                        )
                    }
                }
            }
        }
    }

    suspend fun markAllPartnerMessagesRead() = withContext(ioDispatcher) {
        val partner = userDao.getPartnerUser() ?: return@withContext
        val all = messageDao.observeAllMessages().first()
        val unreadIds = all.filter { it.senderUserId == partner.id && it.status != "READ" && !it.isDeleted }.map { it.id }
        userDao.resetUnread()
        if (unreadIds.isNotEmpty()) {
            val now = System.currentTimeMillis()
            unreadIds.forEach { messageDao.updateDeliveryStatus(it, "READ", now) }
            sendReceipt(unreadIds, "READ")
        }
    }

    private suspend fun sendReceipt(messageIds: List<String>, status: String) {
        if (messageIds.isEmpty()) return
        if (status == "READ") {
            val privacy = prefsDataStore.privacyFlow.first()
            if (!privacy.sendReadReceipts) return
        }
        safeApiCall { api.ackReceipts(AckReceiptRequest(messageIds, status)) }
    }

    suspend fun setDisappearingTimer(seconds: Long) = withContext(ioDispatcher) {
        val current = prefsDataStore.privacyFlow.first()
        prefsDataStore.updatePrivacy(current.copy(disappearingTimerSeconds = seconds))
    }

    suspend fun toggleConversationMute(muted: Boolean) = withContext(ioDispatcher) {
        userDao.setMuted(muted)
        val notif = prefsDataStore.notificationFlow.first()
        prefsDataStore.updateNotifications(notif.copy(muteConversation = muted))
    }

    suspend fun purgeExpiredMessages() = withContext(ioDispatcher) {
        messageDao.purgeExpiredMessages(System.currentTimeMillis())
    }

    private suspend fun refreshConversationPreviewIfLatest(messageId: String, newPreview: String, updatedAt: Long) {
        val latest = messageDao.getLatestMessage()
        if (latest?.id == messageId) {
            userDao.updateConversationPreview(messageId, newPreview, updatedAt)
        }
    }

    fun enqueueSyncWorker() {
        MessageSyncWorker.enqueueImmediate(context)
    }
}
