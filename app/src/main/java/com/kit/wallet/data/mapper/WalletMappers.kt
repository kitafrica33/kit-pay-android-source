package com.kit.wallet.data.mapper

import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.auth.requiresProfileSetup
import com.kit.wallet.data.auth.profileNameOrPlaceholder
import com.kit.wallet.data.remote.TransactionDto
import com.kit.wallet.data.remote.UserDto
import com.kit.wallet.data.remote.WalletDto
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

object DecimalMoney {
    fun toMinor(value: String, scale: Int): Long {
        require(scale in 0..9) { "Unsupported currency scale: $scale" }
        return BigDecimal(value)
            .setScale(scale, RoundingMode.UNNECESSARY)
            .movePointRight(scale)
            .longValueExact()
    }

    fun fromMinor(value: Long, scale: Int): String {
        require(scale in 0..9) { "Unsupported currency scale: $scale" }
        return BigDecimal.valueOf(value, scale).setScale(scale).toPlainString()
    }
}

fun UserDto.toEntity(nowEpochMillis: Long): ProfileEntity = ProfileEntity(
    userId = id,
    name = profileNameOrPlaceholder(name),
    phone = phone.orEmpty(),
    tag = tag.orEmpty(),
    kycLabel = kycStatus.toKycLabel(),
    email = email,
    emailVerified = emailVerified == true,
    profileSetupRequired = profileSetupRequired == true || requiresProfileSetup(name, tag),
    updatedAtEpochMillis = nowEpochMillis,
)

fun ProfileEntity.toUiModel(): UserProfile = UserProfile(
    name = name,
    phone = phone,
    tag = tag,
    kycLabel = kycLabel,
    email = email,
    emailVerified = emailVerified,
    profileSetupRequired = profileSetupRequired || requiresProfileSetup(name, tag),
)

fun WalletDto.toEntity(nowEpochMillis: Long): WalletEntity {
    val scale = currency.scale.toInt()
    return WalletEntity(
        uuid = id,
        name = name,
        accountNumber = accountNumber,
        currencyCode = currency.code,
        currencyScale = scale,
        availableBalanceMinor = DecimalMoney.toMinor(balances.available, scale),
        ledgerBalanceMinor = DecimalMoney.toMinor(balances.ledger ?: balances.available, scale),
        status = status,
        kycStatus = kycStatus.orEmpty(),
        isPrimary = isPrimary == true,
        updatedAtEpochMillis = updatedAt?.toEpochMillisOrNull() ?: nowEpochMillis,
    )
}

fun TransactionDto.toEntity(defaultWalletUuid: String): WalletTransactionEntity {
    val scale = currency.scale.toInt()
    val absoluteMinor = abs(DecimalMoney.toMinor(amount, scale))
    val signedMinor = when (direction.lowercase()) {
        "credit", "in", "incoming", "receive" -> absoluteMinor
        else -> -absoluteMinor
    }
    return WalletTransactionEntity(
        id = id,
        walletUuid = walletId.ifBlank { defaultWalletUuid },
        reference = reference,
        amountMinor = signedMinor,
        currencyCode = currency.code,
        type = type,
        direction = direction,
        status = status,
        counterpartyName = counterparty?.name
            ?: counterparty?.phone
            ?: counterparty?.accountNumber
            ?: "Kit Pay",
        note = note,
        occurredAtEpochMillis = occurredAt.toEpochMillisOrNull() ?: 0L,
    )
}

fun WalletTransactionEntity.toUiModel(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): Transaction {
    val occurred = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(zoneId)
    val today = now.atZone(zoneId).toLocalDate()
    val date = occurred.toLocalDate()
    val dateGroup = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    return Transaction(
        id = id,
        counterparty = counterpartyName,
        note = note,
        amountMinor = amountMinor,
        time = occurred.format(DateTimeFormatter.ofPattern("h:mm a")),
        dateGroup = dateGroup,
        type = type.toUiType(direction),
        status = status.toUiStatus(),
        reference = reference,
    )
}

private fun String?.toKycLabel(): String = when (this?.lowercase()) {
    "verified", "approved" -> "KYC verified"
    "pending", "in_review" -> "KYC pending"
    "rejected" -> "KYC needs attention"
    else -> "KYC not started"
}

private fun String.toUiType(direction: String): TxType = when (lowercase()) {
    "bill", "bill_payment", "utility" -> TxType.BILL
    "airtime", "data" -> TxType.AIRTIME
    "bank_deposit", "bank_in" -> TxType.BANK_IN
    "bank_withdrawal", "bank_out" -> TxType.BANK_OUT
    "merchant", "merchant_payment", "collection" -> TxType.MERCHANT
    "request", "payment_request" -> TxType.REQUEST
    else -> if (direction.lowercase() in setOf("credit", "in", "incoming", "receive")) {
        TxType.RECEIVE
    } else {
        TxType.SEND
    }
}

private fun String.toUiStatus(): TxStatus = when (lowercase()) {
    "completed", "successful", "success", "posted" -> TxStatus.COMPLETED
    "failed", "rejected", "cancelled", "canceled", "reversed" -> TxStatus.FAILED
    else -> TxStatus.PENDING
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
