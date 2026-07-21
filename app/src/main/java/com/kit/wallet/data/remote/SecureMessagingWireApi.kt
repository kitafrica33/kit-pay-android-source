package com.kit.wallet.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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
}
