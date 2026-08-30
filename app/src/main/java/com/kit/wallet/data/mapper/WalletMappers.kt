package com.kit.wallet.data.mapper

import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.data.repository.kycVerificationStateOf
import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.media.isTrustedProfileAvatarUrl
import com.kit.wallet.data.auth.hasVerifiedLegalName
import com.kit.wallet.data.auth.requiresProfileSetup
import com.kit.wallet.data.auth.profileNameOrPlaceholder
import com.kit.wallet.data.remote.TransactionDto
import com.kit.wallet.data.remote.TransferClaimDto
import com.kit.wallet.data.remote.UserDto
import com.kit.wallet.data.remote.WalletDto
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimActor
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.AccountVerification
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

fun UserDto.toEntity(nowEpochMillis: Long): ProfileEntity {
    val verifiedLegalName = legalName?.takeIf(String::isNotBlank)
    val accountVerification = AccountVerification.fromServerValues(
        designation = verification?.designation,
        since = verification?.since,
    )
    return ProfileEntity(
        userId = id,
        // The placeholder stands in for a missing *display* name only. It is never written over
        // legalName, and legalName is never copied into it: a screen that wants the verified name
        // has to ask for the verified name.
        name = profileNameOrPlaceholder(name),
        phone = phone.orEmpty(),
        tag = tag.orEmpty(),
        kycLabel = kycStatus.toKycLabel(),
        email = email,
        emailVerified = emailVerified == true,
        profileSetupRequired = profileSetupRequired == true ||
            requiresProfileSetup(name, tag, verifiedLegalName),
        avatarUrl = avatarUrl?.takeIf(String::isNotBlank),
        legalName = verifiedLegalName,
        // A server that predates the field says nothing, so read the legal name instead of
        // assuming either answer.
        usernameRequired = usernameRequired ?: !hasVerifiedLegalName(verifiedLegalName),
        updatedAtEpochMillis = nowEpochMillis,
        verificationDesignation = accountVerification?.designation?.serverValue,
        verificationSince = accountVerification?.since,
    )
}

fun ProfileEntity.toUiModel(): UserProfile = UserProfile(
    name = name,
    phone = phone,
    tag = tag,
    kycLabel = kycLabel,
    email = email,
    emailVerified = emailVerified,
    profileSetupRequired = profileSetupRequired || requiresProfileSetup(name, tag, legalName),
    avatarUrl = avatarUrl,
    legalName = legalName,
    usernameRequired = usernameRequired,
    accountVerification = AccountVerification.fromServerValues(
        designation = verificationDesignation,
        since = verificationSince,
    ),
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
    val counterpartyVerification = AccountVerification.fromServerValues(
        designation = counterparty?.verification?.designation,
        since = counterparty?.verification?.since,
    )
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
        currencyScale = scale,
        type = type,
        direction = direction,
        status = status,
        counterpartyName = counterparty?.name
            ?: counterparty?.phone
            ?: counterparty?.accountNumber
            ?: "Kit Pay",
        counterpartyUserId = counterparty?.id?.trim()?.takeIf(String::isNotEmpty),
        counterpartyAvatarUrl = counterparty?.avatarUrl
            ?.trim()
            ?.takeIf(::isTrustedProfileAvatarUrl),
        counterpartyVerificationDesignation =
            counterpartyVerification?.designation?.serverValue,
        counterpartyVerificationSince = counterpartyVerification?.since,
        note = note,
        occurredAtEpochMillis = occurredAt.toEpochMillisOrNull() ?: 0L,
    )
}

/**
 * A held Kit → Kit transfer as the wallet API sees it.
 *
 * Anything this build cannot make sense of — an unknown status, an unparseable amount — maps to
 * null rather than to a guess. A card that offers Accept on a claim whose real state is unknown
 * would invite the user to settle the same money twice.
 */
fun TransferClaimDto.toUiModel(): TransferClaim? {
    val scale = currency.scale.toIntOrNull() ?: return null
    val minor = runCatching { abs(DecimalMoney.toMinor(amount, scale)) }.getOrNull() ?: return null
    val claimStatus = when (status.lowercase()) {
        "pending" -> TransferClaimStatus.PENDING
        "accepted" -> TransferClaimStatus.ACCEPTED
        "rejected" -> TransferClaimStatus.REJECTED
        "reversed" -> TransferClaimStatus.REVERSED
        "expired" -> TransferClaimStatus.EXPIRED
        else -> return null
    }
    val pending = claimStatus == TransferClaimStatus.PENDING
    return TransferClaim(
        id = id,
        transactionId = transactionId,
        status = claimStatus,
        amountMinor = minor,
        currencyCode = currency.code,
        currencyScale = scale,
        note = note?.takeIf(String::isNotBlank),
        reason = reason?.takeIf(String::isNotBlank),
        resolvedBy = when (resolvedBy?.lowercase()) {
            "sender" -> TransferClaimActor.SENDER
            "recipient" -> TransferClaimActor.RECIPIENT
            "system" -> TransferClaimActor.SYSTEM
            else -> null
        },
        senderUserId = sender?.id?.takeIf(String::isNotBlank),
        recipientUserId = recipient?.id?.takeIf(String::isNotBlank),
        senderName = sender?.name?.takeIf(String::isNotBlank),
        recipientName = recipient?.name?.takeIf(String::isNotBlank),
        expiresAtEpochMillis = expiresAt?.toEpochMillisOrNull() ?: 0L,
        // A settled claim never offers an action, whatever an older or newer service says.
        canAccept = canAccept && pending,
        canReject = canReject && pending,
        canReverse = canReverse && pending,
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
        currencyCode = currencyCode,
        currencyScale = currencyScale,
        // The unmapped backend words survive beside the display kind: reversal and refund
        // types collapse into SEND/RECEIVE for rendering, and credits share display kinds
        // with debits, so the starter checklist reads the originals to refuse them.
        rawType = type,
        rawDirection = direction,
        counterpartyUserId = counterpartyUserId,
        counterpartyAvatarUrl = counterpartyAvatarUrl,
        accountVerification = AccountVerification.fromServerValues(
            counterpartyVerificationDesignation,
            counterpartyVerificationSince,
        ),
    )
}

/**
 * The stored label for an account's identity standing.
 *
 * Derived through [kycVerificationStateOf] rather than a private list of words, so the profile row
 * cannot recognise a different set of statuses from the verification screen — a disagreement that
 * once had verified accounts labelled "not started" and prompted to verify all over again. The
 * labels round-trip back through the same reader, so a screen holding only the label still gets
 * the state right.
 */
private fun String?.toKycLabel(): String = when (kycVerificationStateOf(this)) {
    KycVerificationState.VERIFIED -> "KYC verified"
    KycVerificationState.IN_REVIEW -> "KYC pending"
    KycVerificationState.ACTION_NEEDED -> "KYC needs attention"
    KycVerificationState.NOT_STARTED -> "KYC not started"
    // Deliberately not "not started": an unreadable status is the app's ignorance, not a claim
    // about the user, and it must not be turned into one.
    KycVerificationState.UNKNOWN -> "KYC status unavailable"
}

// Trimmed and lowercased before matching: a server value with stray whitespace or
// casing must normalize to the same kind, never fall through to a default.
private fun String.toUiType(direction: String): TxType = when (trim().lowercase()) {
    "bill", "bill_payment", "utility" -> TxType.BILL
    "airtime", "data" -> TxType.AIRTIME
    "bank_deposit", "bank_in" -> TxType.BANK_IN
    "bank_withdrawal", "bank_out" -> TxType.BANK_OUT
    "merchant", "merchant_payment", "collection" -> TxType.MERCHANT
    "request", "payment_request" -> TxType.REQUEST
    else -> if (direction.trim().lowercase() in setOf("credit", "in", "incoming", "receive")) {
        TxType.RECEIVE
    } else {
        TxType.SEND
    }
}

private fun String.toUiStatus(): TxStatus = when (trim().lowercase()) {
    "completed", "successful", "success", "posted" -> TxStatus.COMPLETED
    "failed", "rejected", "cancelled", "canceled", "reversed" -> TxStatus.FAILED
    else -> TxStatus.PENDING
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
