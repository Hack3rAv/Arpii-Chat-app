package com.systemutility.core.network.api

import com.systemutility.core.network.dto.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface SystemUtilityApi {

    @GET("api/v1/health")
    suspend fun health(): Response<Unit>

    @POST("api/v1/auth/salt")
    suspend fun getSalt(@Body body: SaltRequest): Response<SaltResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("api/v1/auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Unit>

    @POST("api/v1/auth/recover")
    suspend fun recoverAccount(@Body body: RecoverAccountRequest): Response<Unit>

    @GET("api/v1/auth/sessions")
    suspend fun listSessions(): Response<SessionsListResponse>

    @POST("api/v1/auth/sessions/{sessionId}/revoke")
    suspend fun revokeSession(@Path("sessionId") sessionId: String): Response<Unit>

    @POST("api/v1/auth/devices/{deviceId}/revoke")
    suspend fun revokeDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @POST("api/v1/auth/devices/{deviceId}/approve")
    suspend fun approveDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @POST("api/v1/auth/devices/{deviceId}/reject")
    suspend fun rejectDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @GET("api/v1/profile")
    suspend fun getProfiles(): Response<ProfileResponse>

    @PATCH("api/v1/profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): Response<ProfileResponse>

    // Phase 2 Keys & Messaging
    @POST("api/v1/keys/bundle")
    suspend fun uploadPreKeys(@Body body: UploadPreKeysRequest): Response<Unit>

    @GET("api/v1/keys/partner")
    suspend fun fetchPartnerPreKeyBundle(): Response<PeerPreKeyBundleDto>

    @POST("api/v1/messages/send")
    suspend fun sendMessage(@Body body: SendMessageRequest): Response<EncryptedMessageDto>

    @GET("api/v1/messages/sync")
    suspend fun syncMessages(@Query("afterSeq") afterSeq: Long): Response<SyncMessagesResponse>

    @POST("api/v1/messages/ack")
    suspend fun ackReceipts(@Body body: AckReceiptRequest): Response<Unit>

    @POST("api/v1/messages/{messageId}/edit")
    suspend fun editMessage(@Path("messageId") id: String, @Body body: SendMessageRequest): Response<Unit>

    @POST("api/v1/messages/{messageId}/delete")
    suspend fun deleteMessage(@Path("messageId") id: String): Response<Unit>

    // Phase 3 Media
    @POST("api/v1/media/init-upload")
    suspend fun initMediaUpload(@Body body: MediaUploadInitRequest): Response<MediaUploadInitResponse>

    @PUT("api/v1/media/blob/{mediaId}")
    suspend fun uploadMediaBlob(@Path("mediaId") mediaId: String, @Body encryptedStream: RequestBody): Response<Unit>

    @POST("api/v1/media/authorize-download")
    suspend fun authorizeMediaDownload(@Body body: MediaDownloadAuthRequest): Response<MediaDownloadAuthResponse>

    @Streaming
    @GET("api/v1/media/blob/{r2ObjectKey}")
    suspend fun downloadMediaBlob(
        @Path("r2ObjectKey", encoded = true) r2ObjectKey: String,
        @Query("grant") grant: String
    ): Response<ResponseBody>

    // Phase 4 TURN & Calls
    @GET("api/v1/turn-credentials")
    suspend fun getTurnCredentials(): Response<TurnCredentialsResponse>

    @POST("api/v1/calls/log")
    suspend fun recordCallLog(@Body body: CallLogRequest): Response<Unit>

    // Phase 6 Backups
    @POST("api/v1/backups/manifest")
    suspend fun recordBackupManifest(@Body body: RecordBackupRequest): Response<Unit>

    @GET("api/v1/backups/manifest")
    suspend fun listBackupManifests(): Response<BackupManifestListResponse>
}
