package com.kit.wallet.data.remote

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Raw secure-messaging routes. Production callers must go through
 * `RemoteSecureMessagingTransport`, which applies activation fencing and wire validation.
 */
internal interface SecureMessagingWireApi {
    @GET("api/kit-wallet/v1/messaging/keys/status")
    suspend fun messagingKeyStatus(): ApiEnvelope<MessagingKeyStatusDto>

    @PUT("api/kit-wallet/v1/messaging/keys")
    suspend fun publishMessagingKeyBundle(
        @Body request: PublishMessagingKeyBundleRequest,
    ): ApiEnvelope<MessagingKeyStatusDto>

    @GET("api/kit-wallet/v1/messaging/conversations")
    suspend fun messagingConversations(): ApiEnvelope<MessagingConversationListDto>

    @POST("api/kit-wallet/v1/messaging/conversations")
    suspend fun createDirectMessagingConversation(
        @Body request: CreateDirectMessagingConversationRequest,
    ): ApiEnvelope<MessagingConversationDto>

    @GET("api/kit-wallet/v1/messaging/conversations/{conversation}/device-roster")
    suspend fun messagingDeviceRoster(
        @Path("conversation") conversationId: String,
    ): ApiEnvelope<MessagingDeviceRosterDto>

    @GET(
        "api/kit-wallet/v1/messaging/conversations/{conversation}/" +
            "device-roster/{rosterRevision}",
    )
    suspend fun historicalMessagingDeviceRoster(
        @Path("conversation") conversationId: String,
        @Path("rosterRevision") rosterRevision: String,
    ): ApiEnvelope<MessagingDeviceRosterDto>

    @POST("api/kit-wallet/v1/messaging/conversations/{conversation}/key-bundles")
    suspend fun consumeMessagingKeyBundles(
        @Path("conversation") conversationId: String,
        @Body request: ConsumeMessagingKeyBundlesRequest = ConsumeMessagingKeyBundlesRequest(),
    ): ApiEnvelope<ConsumedMessagingKeyBundlesDto>

    @POST("api/kit-wallet/v1/messaging/conversations/{conversation}/messages")
    suspend fun sendEncryptedMessage(
        @Path("conversation") conversationId: String,
        @Body request: SendEncryptedMessageRequest,
    ): ApiEnvelope<EncryptedMessageDto>

    @GET(
        "api/kit-wallet/v1/messaging/conversations/{conversation}/" +
            "history-backfill/candidates",
    )
    suspend fun messagingHistoryBackfillCandidates(
        @Path("conversation") conversationId: String,
        @Query("target_device_id") targetDeviceId: String,
        @Query("target_enrollment_epoch") targetEnrollmentEpoch: Long,
        @Query("after") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<MessagingHistoryBackfillCandidatesDto>

    @POST(
        "api/kit-wallet/v1/messaging/conversations/{conversation}/messages/{message}/" +
            "history-envelopes",
    )
    suspend fun storeMessagingHistoryEnvelope(
        @Path("conversation") conversationId: String,
        @Path("message") messageId: String,
        @Body request: StoreMessagingHistoryEnvelopeRequest,
    ): ApiEnvelope<MessagingHistoryEnvelopeResultDto>

    @GET("api/kit-wallet/v1/messaging/sync")
    suspend fun syncEncryptedMessages(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<MessagingSyncDto>

    @POST("api/kit-wallet/v1/messaging/messages/delivery-acks")
    suspend fun acknowledgeMessageDelivery(
        @Body request: AcknowledgeMessageDeliveryRequest,
    ): ApiEnvelope<MessageDeliveryAcknowledgementDto>

    @POST("api/kit-wallet/v1/messaging/conversations/{conversation}/read-receipts")
    suspend fun markMessagingConversationRead(
        @Path("conversation") conversationId: String,
        @Body request: MarkMessagingConversationReadRequest,
    ): ApiEnvelope<MessagingReadReceiptDto>

    /** Uploads one opaque end-to-end encrypted attachment ciphertext blob. */
    @Multipart
    @POST("api/kit-wallet/v1/messaging/attachments")
    suspend fun uploadMessagingAttachment(
        @Part("media_type") mediaType: okhttp3.RequestBody,
        @Part ciphertext: MultipartBody.Part,
    ): ApiEnvelope<MessagingAttachmentUploadDto>

    /** Streams one opaque end-to-end encrypted attachment ciphertext blob. */
    @Streaming
    @GET("api/kit-wallet/v1/messaging/attachments/{storageKey}")
    suspend fun downloadMessagingAttachment(
        @Path("storageKey") storageKey: String,
    ): ResponseBody
}
