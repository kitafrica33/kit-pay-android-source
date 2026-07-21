package com.kit.wallet

import com.kit.wallet.data.mapper.toBankInstitution
import com.kit.wallet.data.remote.ApiErrorDto
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.BankBeneficiaryDto
import com.kit.wallet.data.remote.BankBeneficiaryListDto
import com.kit.wallet.data.remote.BankingOperationListDto
import com.kit.wallet.data.remote.BankingOperationDto
import com.kit.wallet.data.remote.BankDto
import com.kit.wallet.data.remote.BankListDto
import com.kit.wallet.data.remote.BootstrapDto
import com.kit.wallet.data.remote.CallPageDto
import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.ContactListDto
import com.kit.wallet.data.remote.ContactDto
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.DeviceListDto
import com.kit.wallet.data.remote.DeviceDto
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.KycDocumentDto
import com.kit.wallet.data.remote.KycStatusDto
import com.kit.wallet.data.remote.MessageResultDto
import com.kit.wallet.data.remote.MfaStatusDto
import com.kit.wallet.data.remote.MobileMoneyAccountDto
import com.kit.wallet.data.remote.MobileMoneyAccountListDto
import com.kit.wallet.data.remote.MobileMoneyNetworkDto
import com.kit.wallet.data.remote.MobileMoneyNetworkListDto
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.kit.wallet.data.remote.MobileMoneyOperationListDto
import com.kit.wallet.data.remote.PasswordResetResultDto
import com.kit.wallet.data.remote.PaymentPinStatusDto
import com.kit.wallet.data.remote.ProviderCategoryDto
import com.kit.wallet.data.remote.ProviderOperationDto
import com.kit.wallet.data.remote.ProviderProductListDto
import com.kit.wallet.data.remote.ProviderQuoteDto
import com.kit.wallet.data.remote.PushTokenStatusDto
import com.kit.wallet.data.remote.RecoveryCodesDto
import com.kit.wallet.data.remote.RtcCredentialsDto
import com.kit.wallet.data.remote.StepUpChallengeDto
import com.kit.wallet.data.remote.TransactionPageDto
import com.kit.wallet.data.remote.VerifyEmailResultDto
import com.kit.wallet.data.remote.WalletDto
import com.kit.wallet.data.remote.WalletListDto
import com.kit.wallet.data.repository.providerDestinationPresentation
import com.kit.wallet.data.repository.toCallDisplayName
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NullableApiFlagsTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun `all response boolean flags tolerate explicit null`() {
        val capabilities = decode<CapabilitiesDto>(
            """{"currency":{"code":"UGX","scale":"2"},"features":{"messaging":null},"authentication":null,"protocols":{"messaging":{"ready":null,"version":null,"suite":null,"post_quantum":null}}}""",
        )
        val wallet = decode<WalletDto>(
            """{"id":"wallet","name":"Main","currency":{"code":"UGX","scale":"2"},"balances":{"available":"0"},"status":"active","is_primary":null}""",
        )
        val page = decode<CursorPageDto>(
            """{"next_cursor":null,"has_more":null,"limit":50}""",
        )
        val device = decode<DeviceDto>(
            """{"id":"device","name":"Phone","platform":"android","is_current":null,"is_trusted":null}""",
        )
        val contact = decode<ContactDto>(
            """{"id":"contact","name":"Amina","phone":"+256700000200","is_kit_user":null,"favorite":null}""",
        )
        val call = decode<CallDto>(
            """{"id":"call","name":"Amina","direction":"incoming","type":"voice","video":null,"state":"ended","started_at":"2026-07-18T12:00:00Z"}""",
        )

        assertNull(capabilities.features?.get("messaging"))
        assertNull(capabilities.authentication)
        assertNull(capabilities.protocols?.messaging?.ready)
        assertNull(capabilities.protocols?.messaging?.version)
        assertNull(capabilities.protocols?.messaging?.suite)
        assertNull(capabilities.protocols?.messaging?.postQuantum)
        assertNull(wallet.isPrimary)
        assertNull(page.hasMore)
        assertNull(device.isCurrent)
        assertNull(device.isTrusted)
        assertNull(contact.isKitUser)
        assertNull(contact.favorite)
        assertNull(call.video)
        assertNull(decode<MfaStatusDto>("""{"enabled":null}""").enabled)
        assertNull(
            decode<PaymentPinStatusDto>("""{"payment_pin_set":null}""").paymentPinSet,
        )
        assertNull(decode<PushTokenStatusDto>("""{"registered":null}""").registered)
        assertNull(decode<VerifyEmailResultDto>(VERIFY_RESULT_JSON).verified)
        assertNull(
            decode<PasswordResetResultDto>("""{"password_reset":null}""").passwordReset,
        )
    }

    @Test
    fun `nullable capability maps normalize unsupported values to false`() {
        val bank = decode<BankDto>(
            """{"id":"bank","code":"TEST","name":"Test Bank","country_code":"UG","currency":"UGX","capabilities":{"deposits":null}}""",
        )
        val mobileMoney = decode<MobileMoneyNetworkDto>(
            """{"id":"network","code":"MTN","name":"MTN","currency":{"code":"UGX","scale":"2"},"capabilities":null}""",
        )

        assertFalse(bank.toBankInstitution().supports("deposits"))
        assertNull(mobileMoney.capabilities)
    }

    @Test
    fun `response integer metadata tolerates explicit null`() {
        val page = decode<CursorPageDto>(
            """{"next_cursor":null,"has_more":false,"limit":null}""",
        )
        val category = decode<ProviderCategoryDto>(
            """{"id":"category","service_type":"bill","code":"power","name":"Power","display_order":null}""",
        )

        assertNull(page.limit)
        assertNull(category.displayOrder)
    }

    @Test
    fun `optional presentation collections tolerate explicit null`() {
        val bootstrap = decode<BootstrapDto>(
            """{"user":{"id":"user","name":"Kit Pay User","mfa_enabled":null},"wallets":[],"devices":[]}""",
        )
        val nullableCollections = listOf<Any?>(
            decode<ContactListDto>("""{"items":null}""").items,
            decode<ProviderProductListDto>("""{"items":null}""").items,
            decode<CallPageDto>("""{"items":null,"page":null}""").items,
            decode<BankListDto>("""{"items":null}""").items,
            decode<MobileMoneyNetworkListDto>("""{"items":null}""").items,
            decode<KycStatusDto>("""{"status":"unverified","documents":null}""").documents,
            decode<KycDocumentDto>(
                """{"type":"national_id","status":"pending","reason_codes":null}""",
            ).reasonCodes,
            decode<MfaStatusDto>("""{"enabled":null,"recovery_codes":null}""").recoveryCodes,
            decode<RecoveryCodesDto>("""{"recovery_codes":null}""").recoveryCodes,
        )

        assertFalse(bootstrap.user.mfaEnabled == true)
        assertEquals(9, nullableCollections.size)
        assertTrue(nullableCollections.all { it == null })
        assertNull(decode<CallPageDto>("""{"items":null,"page":null}""").page)
    }

    @Test
    fun `authoritative account collections reject explicit null`() {
        val malformedResponses = listOf<() -> Any>(
            {
                decode<BootstrapDto>(
                    """{"user":{"id":"user","name":"Amina"},"wallets":null,"devices":[]}""",
                )
            },
            {
                decode<BootstrapDto>(
                    """{"user":{"id":"user","name":"Amina"},"wallets":[],"devices":null}""",
                )
            },
            { decode<WalletListDto>("""{"items":null}""") },
            { decode<TransactionPageDto>("""{"items":null,"page":{"has_more":false}}""") },
            { decode<TransactionPageDto>("""{"items":[],"page":null}""") },
            { decode<DeviceListDto>("""{"items":null}""") },
            { decode<BankBeneficiaryListDto>("""{"items":null}""") },
            { decode<BankingOperationListDto>("""{"items":null}""") },
            { decode<MobileMoneyAccountListDto>("""{"items":null}""") },
            { decode<MobileMoneyOperationListDto>("""{"items":null}""") },
        )

        malformedResponses.forEach { decodeMalformed ->
            assertThrows(JsonDataException::class.java) { decodeMalformed() }
        }
    }

    @Test
    fun `optional nested response collections tolerate explicit null`() {
        val call = decode<CallDto>(
            """{"id":"call","name":"Amina","participant_user_ids":null,"direction":"incoming","type":"voice","video":null,"state":"ended","started_at":"2026-07-18T12:00:00Z"}""",
        )
        val rtc = decode<RtcCredentialsDto>(
            """{"provider":"livekit","url":"wss://calls.example.test","token":"token","room":"room","ice_servers":null,"expires_at":"2026-07-18T12:05:00Z"}""",
        )
        val challenge = decode<StepUpChallengeDto>(
            """{"id":"challenge","purpose":"wallet_transfer","intent_hash":"hash","nonce":"nonce","signing_payload":"payload","methods":null,"expires_at":"2026-07-18T12:05:00Z"}""",
        )

        assertTrue(call.participantUserIds.orEmpty().isEmpty())
        assertTrue(rtc.iceServers.orEmpty().isEmpty())
        assertTrue(challenge.methods.orEmpty().isEmpty())
    }

    @Test
    fun `optional response presentation text tolerates explicit null`() {
        val message = decode<MessageResultDto>("""{"message":null}""")
        val call = decode<CallDto>(
            """{"id":"call","name":null,"direction":"incoming","type":"voice","state":"ended","started_at":"2026-07-18T12:00:00Z"}""",
        )
        val bankBeneficiary = decode<BankBeneficiaryDto>(
            """{"id":"beneficiary","kind":"own","label":"My bank","bank":{"id":"bank","code":"TEST","name":"Test Bank","country_code":"UG","currency":"UGX"},"account_name":null,"account_number_masked":"****1234","status":"active"}""",
        )
        val mobileMoneyAccount = decode<MobileMoneyAccountDto>(
            """{"id":"account","kind":"own","label":"My phone","network":{"id":"network","code":"MTN","name":"MTN","currency":{"code":"UGX","scale":"2"}},"account_name":null,"phone_number_masked":"+256***200","status":"active"}""",
        )

        assertNull(message.message)
        assertNull(call.name)
        assertEquals("Kit Pay contact", call.name.toCallDisplayName())
        assertNull(bankBeneficiary.accountName)
        assertNull(mobileMoneyAccount.accountName)
    }

    @Test
    fun `banking operation lifecycle fields tolerate explicit null`() {
        val operation = decode<BankingOperationDto>(BANKING_OPERATION_WITH_NULLS_JSON)

        assertNull(operation.submissionStage)
        assertNull(operation.beneficiaryId)
    }

    @Test
    fun `mobile money operation and failure fields tolerate explicit null`() {
        val operation = decode<MobileMoneyOperationDto>(MOBILE_MONEY_OPERATION_WITH_NULLS_JSON)

        assertNull(operation.submissionStage)
        assertNull(operation.beneficiaryId)
        assertNull(operation.failure?.message)
    }

    @Test
    fun `provider destination tolerates null without inventing account identity`() {
        val quote = decode<ProviderQuoteDto>(PROVIDER_QUOTE_WITH_NULL_DESTINATION_JSON)
        val operation = decode<ProviderOperationDto>(PROVIDER_OPERATION_WITH_NULL_DESTINATION_JSON)

        assertNull(quote.accountDisplay)
        assertNull(operation.accountDisplay)
        assertEquals("Destination unavailable", providerDestinationPresentation(operation.accountDisplay))
    }

    @Test
    fun `null envelope status parses and fails closed`() {
        val type = Types.newParameterizedType(ApiEnvelope::class.java, String::class.java)
        val envelope = requireNotNull(
            moshi.adapter<ApiEnvelope<String>>(type)
                .fromJson("""{"ok":null,"data":"unsafe"}"""),
        )

        assertThrows(KitWalletApiException::class.java) { envelope.requireData() }
    }

    @Test
    fun `null error fields parse and use safe envelope fallbacks`() {
        val type = Types.newParameterizedType(ApiEnvelope::class.java, String::class.java)
        val envelope = requireNotNull(
            moshi.adapter<ApiEnvelope<String>>(type)
                .fromJson("""{"ok":false,"error":{"code":null,"message":null}}"""),
        )

        assertEquals(ApiErrorDto(), envelope.error)
        val error = assertThrows(KitWalletApiException::class.java) { envelope.requireData() }
        assertEquals("UNKNOWN_API_ERROR", error.code)
        assertEquals("Kit Pay request failed", error.message)
    }

    private inline fun <reified T> decode(json: String): T = requireNotNull(
        moshi.adapter(T::class.java).fromJson(json),
    )

    private companion object {
        const val VERIFY_RESULT_JSON =
            "{\"verified\":null,\"user\":{\"id\":\"user\",\"name\":\"Amina\"}}"
        const val BANKING_OPERATION_WITH_NULLS_JSON =
            """{"id":"operation","reference":"BNK-1","type":"deposit","direction":"credit","status":"pending","submission_stage":null,"bank_id":"bank","beneficiary_id":null,"wallet_id":"wallet","amount":"1000.00","currency":{"code":"UGX","scale":"2"}}"""
        const val MOBILE_MONEY_OPERATION_WITH_NULLS_JSON =
            """{"id":"operation","reference":"MM-1","type":"deposit","direction":"credit","status":"pending","submission_stage":null,"bank_id":"bank","beneficiary_id":null,"wallet_id":"wallet","amount":"1000.00","currency":{"code":"UGX","scale":"2"},"failure":{"code":"PROVIDER_ERROR","message":null},"mobile_money_type":"collection","network":{"id":"network","code":"MTN","name":"MTN","currency":{"code":"UGX","scale":"2"}}}"""
        const val PROVIDER_QUOTE_WITH_NULL_DESTINATION_JSON =
            """{"id":"quote","product_id":"product","provider_code":"RUKA","service_type":"bill","account_display":null,"amount":"1000.00","fee":"0.00","total":"1000.00","currency":{"code":"UGX","scale":"2"},"expires_at":"2026-07-18T12:05:00Z"}"""
        const val PROVIDER_OPERATION_WITH_NULL_DESTINATION_JSON =
            """{"id":"operation","type":"bill_payment","status":"pending","wallet_id":"wallet","provider_code":"RUKA","product_id":"product","product_name":"Power","account_display":null,"amount":"1000.00","fee":"0.00","total":"1000.00","currency":{"code":"UGX","scale":"2"}}"""
    }
}
