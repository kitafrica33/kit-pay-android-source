package com.kit.wallet.data.remote

import com.kit.wallet.data.session.SessionFence
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/** Every request is tied to its initiating account, including capability discovery. */
interface ScheduledCallsApi {
    @GET("api/kit-wallet/v1/capabilities")
    suspend fun capabilities(@Tag owner: SessionFence): ApiEnvelope<CapabilitiesDto>

    @GET("api/kit-wallet/v1/scheduled-calls")
    suspend fun list(
        @Tag owner: SessionFence,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<ScheduledCallsPageDto>

    @POST("api/kit-wallet/v1/scheduled-calls")
    suspend fun create(@Body request: CreateScheduledCallRequest, @Tag owner: SessionFence): ApiEnvelope<ScheduledCallDto>

    @GET("api/kit-wallet/v1/scheduled-calls/{id}")
    suspend fun get(@Path("id") id: String, @Tag owner: SessionFence): ApiEnvelope<ScheduledCallDto>

    @PATCH("api/kit-wallet/v1/scheduled-calls/{id}")
    suspend fun update(@Path("id") id: String, @Body request: UpdateScheduledCallRequest, @Tag owner: SessionFence): ApiEnvelope<ScheduledCallDto>

    @POST("api/kit-wallet/v1/scheduled-calls/{id}/cancel")
    suspend fun cancel(@Path("id") id: String, @Body request: ScheduledCallRevisionRequest, @Tag owner: SessionFence): ApiEnvelope<ScheduledCallDto>

    @POST("api/kit-wallet/v1/scheduled-calls/{id}/respond")
    suspend fun respond(@Path("id") id: String, @Body request: ScheduledCallResponseRequest, @Tag owner: SessionFence): ApiEnvelope<ScheduledCallDto>

    @POST("api/kit-wallet/v1/scheduled-calls/{id}/invite-link")
    suspend fun scheduleInvite(@Path("id") id: String, @Tag owner: SessionFence): ApiEnvelope<CallInviteLinkDto>

    @POST("api/kit-wallet/v1/calls/{id}/invite-link")
    suspend fun callInvite(@Path("id") id: String, @Tag owner: SessionFence): ApiEnvelope<CallInviteLinkDto>

    @GET("api/kit-wallet/v1/call-invite-links/{token}")
    suspend fun preview(@Path("token") token: String, @Tag owner: SessionFence): ApiEnvelope<CallInvitePreviewDto>

    @POST("api/kit-wallet/v1/call-invite-links/{token}")
    suspend fun redeem(@Path("token") token: String, @Body request: RedeemCallInviteRequest, @Tag owner: SessionFence): ApiEnvelope<RedeemedCallInviteDto>
}
