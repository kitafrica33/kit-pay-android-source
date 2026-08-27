package com.kit.wallet.data.remote

import com.kit.wallet.data.session.SessionFence
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Tag

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
    suspend fun messagingConversations(
        @Tag expectedOwner: SessionFence? = null,
    ): ApiEnvelope<MessagingConversationListDto>

    @POST("api/kit-wallet/v1/messaging/conversations")
    suspend fun createMessagingConversation(
        @Body request: CreateMessagingConversationRequest,
    ): ApiEnvelope<MessagingConversationDto>

    @PATCH("api/kit-wallet/v1/messaging/conversations/{conversation}")
    suspend fun updateMessagingConversation(
        @Path("conversation") conversationId: String,
        @Body request: UpdateMessagingConversationRequest,
    ): ApiEnvelope<MessagingConversationDto>

    @PUT("api/kit-wallet/v1/messaging/conversations/{conversation}/photo")
    suspend fun attachMessagingConversationPhoto(
        @Path("conversation") conversationId: String,
        @Body request: AttachMessagingConversationPhotoRequest,
    ): ApiEnvelope<MessagingConversationDto>

    @DELETE("api/kit-wallet/v1/messaging/conversations/{conversation}/photo")
    suspend fun removeMessagingConversationPhoto(
        @Path("conversation") conversationId: String,
    ): ApiEnvelope<MessagingConversationDto>

    @POST("api/kit-wallet/v1/messaging/conversations/{conversation}/members")
    suspend fun addMessagingConversationMember(
        @Path("conversation") conversationId: String,
        @Body request: AddMessagingConversationMemberRequest,
    ): ApiEnvelope<MessagingConversationDto>

    @PATCH("api/kit-wallet/v1/messaging/conversations/{conversation}/members/{user}")
    suspend fun updateMessagingConversationMember(
        @Path("conversation") conversationId: String,
        @Path("user") userId: String,
        @Body request: UpdateMessagingConversationMemberRequest,
    ): ApiEnvelope<MessagingConversationDto>

    /** Removes a member, or leaves the group when the target is the current account. */
    @DELETE("api/kit-wallet/v1/messaging/conversations/{conversation}/members/{user}")
    suspend fun removeMessagingConversationMember(
        @Path("conversation") conversationId: String,
        @Path("user") userId: String,
    ): ApiEnvelope<MessagingConversationDto>

    @GET("api/kit-wallet/v1/messaging/conversations/{conversation}/device-roster")
    suspend fun messagingDeviceRoster(
        @Path("conversation") conversationId: String,
        @Tag expectedOwner: SessionFence? = null,
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
        @Tag expectedOwner: SessionFence? = null,
    ): ApiEnvelope<ConsumedMessagingKeyBundlesDto>

    @POST("api/kit-wallet/v1/messaging/conversations/{conversation}/messages")
    suspend fun sendEncryptedMessage(
        @Path("conversation") conversationId: String,
        @Body request: SendEncryptedMessageRequest,
        @Tag expectedOwner: SessionFence? = null,
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

    /**
     * When one message was accepted, and when it reached and was opened by each recipient.
     *
     * The server answers this to the sender alone, so a 403 here is the contract working rather
     * than a failure to explain.
     */
    @GET("api/kit-wallet/v1/messaging/conversations/{conversation}/messages/{message}/info")
    suspend fun messagingMessageInfo(
        @Path("conversation") conversationId: String,
        @Path("message") messageId: String,
    ): ApiEnvelope<MessagingMessageInfoDto>

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
