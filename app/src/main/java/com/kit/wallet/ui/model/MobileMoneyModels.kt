package com.kit.wallet.ui.model

data class MobileMoneyNetwork(
    val id: String,
    val code: String,
    val name: String,
    val currencyCode: String,
    val currencyScale: Int,
    val canCollect: Boolean,
    val canPayout: Boolean,
    val canVerifyAccount: Boolean,
)

data class MobileMoneyAccount(
    val id: String,
    val kind: String,
    val label: String,
    val networkCode: String,
    val networkName: String,
    val accountName: String,
    val phoneNumberMasked: String,
    val currencyCode: String,
    val currencyScale: Int,
    val status: String,
) {
    val isOwnAccount: Boolean get() = kind == "own"
}

data class MobileMoneyOperation(
    val id: String,
    val reference: String,
    val action: String,
    val accountId: String?,
    val networkCode: String,
    val networkName: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val status: String,
    val submissionStage: String?,
    val createdAt: String?,
    val failureMessage: String?,
)

data class MobileMoneyVerificationState(
    val id: String,
    val status: String,
    val phoneNumberMasked: String,
    val accountName: String?,
    val failureMessage: String?,
)
