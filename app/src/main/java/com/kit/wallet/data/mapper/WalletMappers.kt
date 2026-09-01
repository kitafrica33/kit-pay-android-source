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

/**
 * Transaction kinds that have an explicitly reviewed customer presentation.
 *
 * This deliberately mirrors the server's customer-history allowlist. Unknown kinds stay out of
 * the UI until their accounting semantics have been reviewed; a future commission or settlement
 * posting must never become visible merely because an older app cached it.
 */
private val CUSTOMER_VISIBLE_WALLET_TRANSACTION_TYPES = setOf(
    "airtime",
    "bank_deposit",
    "bank_reversal",
    "bank_transfer",
    "bank_withdrawal",
    "bill_payment",
    "internal_transfer",
    "internal_transfer_reversal",
    "merchant_escrow_release",
    "merchant_payment",
    "merchant_refund",
    "provider_reversal",
    "referral_reward",
    "referral_reward_reversal",
)

/**
 * Customer movements whose ledger counterparty is intrinsically a Kit/service account.
 *
 * The server suppresses these identities too, but this client-side boundary is deliberate:
 * aggregate totals prove the customer's amount, not that an accidentally supplied counterparty
 * is public. Never persist a service wallet's name, public ID, avatar, or verification badge in
 * the offline customer cache.
 */
private val INSTITUTIONAL_COUNTERPARTY_TRANSACTION_TYPES = setOf(
    "airtime",
    "bank_deposit",
    "bank_reversal",
    "bank_transfer",
    "bank_withdrawal",
    "bill_payment",
    "provider_reversal",
    "referral_reward",
    "referral_reward_reversal",
)

private fun String.hasInstitutionalCounterparty(): Boolean =
    trim().lowercase() in INSTITUTIONAL_COUNTERPARTY_TRANSACTION_TYPES

internal fun String.isCustomerVisibleWalletTransactionType(): Boolean =
    trim().lowercase() in CUSTOMER_VISIBLE_WALLET_TRANSACTION_TYPES

/**
 * Verifies that a customer transaction contains one authoritative aggregate movement.
 *
 * All current wallet-history and transfer responses carry `totals`. Requiring that contract
 * prevents a principal-only legacy bank row from being mistaken for the complete debit, while a
 * malformed or newly split response is dropped without poisoning the rest of the refresh.
 */
internal fun TransactionDto.hasVerifiedCustomerProjection(): Boolean {
    if (!type.isCustomerVisibleWalletTransactionType()) return false
    val normalizedDirection = direction.trim().lowercase()
    if (normalizedDirection != "credit" && normalizedDirection != "debit") return false
    val scale = currency.scale.toIntOrNull()?.takeIf { it in 0..9 } ?: return false
    val aggregate = totals ?: return false

    fun nonnegativeMinor(value: String): Long? = runCatching {
        DecimalMoney.toMinor(value.trim(), scale)
    }.getOrNull()?.takeIf { it >= 0 }

    val amountMinor = nonnegativeMinor(amount)?.takeIf { it > 0 } ?: return false
    val addedMinor = nonnegativeMinor(aggregate.added) ?: return false
    val deductedMinor = nonnegativeMinor(aggregate.deducted) ?: return false

    return when (normalizedDirection) {
        "credit" -> addedMinor == amountMinor && deductedMinor == 0L
        "debit" -> deductedMinor == amountMinor && addedMinor == 0L
        else -> false
    }
}

/**
 * Binds a customer projection to the wallet whose authenticated endpoint returned it.
 * A valid total for another wallet or currency must never enter this wallet's UI or cache.
 */
internal fun TransactionDto.hasVerifiedCustomerProjection(
    expectedWalletId: String,
    expectedCurrencyCode: String,
    expectedCurrencyScale: Int,
): Boolean =
    hasVerifiedCustomerProjection() &&
        expectedWalletId.isNotBlank() &&
        walletId == expectedWalletId &&
        expectedCurrencyCode.isNotBlank() &&
        currency.code.equals(expectedCurrencyCode, ignoreCase = true) &&
        expectedCurrencyScale in 0..9 &&
        currency.scale.toIntOrNull() == expectedCurrencyScale

internal fun WalletTransactionEntity.isCustomerVisibleWalletTransaction(): Boolean =
    customerProjectionVerified && type.isCustomerVisibleWalletTransactionType()

/** Read-side defense for rows restored from an older or corrupted local cache. */
internal fun WalletTransactionEntity.isCustomerVisibleWalletTransaction(
    expectedWalletId: String,
    expectedCurrencyCode: String,
    expectedCurrencyScale: Int,
): Boolean =
    isCustomerVisibleWalletTransaction() &&
        walletUuid == expectedWalletId &&
        currencyCode.equals(expectedCurrencyCode, ignoreCase = true) &&
        currencyScale == expectedCurrencyScale

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

fun TransactionDto.toEntity(
    expectedWalletUuid: String,
    expectedCurrencyCode: String,
    expectedCurrencyScale: Int,
): WalletTransactionEntity {
    require(
        hasVerifiedCustomerProjection(
            expectedWalletUuid,
            expectedCurrencyCode,
            expectedCurrencyScale,
        ),
    ) {
        "Wallet transaction does not match the selected wallet's customer projection"
    }
    val scale = currency.scale.toInt()
    val normalizedDirection = direction.trim().lowercase()
    val customerAmount = when (normalizedDirection) {
        "credit", "in", "incoming", "receive" -> totals?.added
        else -> totals?.deducted
    } ?: amount
    val absoluteMinor = abs(DecimalMoney.toMinor(customerAmount, scale))
    val publicCounterparty = counterparty.takeUnless {
        type.hasInstitutionalCounterparty()
    }
    val counterpartyVerification = AccountVerification.fromServerValues(
        designation = publicCounterparty?.verification?.designation,
        since = publicCounterparty?.verification?.since,
    )
    val signedMinor = when (normalizedDirection) {
        "credit", "in", "incoming", "receive" -> absoluteMinor
        else -> -absoluteMinor
    }
    return WalletTransactionEntity(
        id = id,
        walletUuid = expectedWalletUuid,
        reference = reference,
        amountMinor = signedMinor,
        currencyCode = expectedCurrencyCode,
        currencyScale = expectedCurrencyScale,
        type = type,
        direction = direction,
        status = status,
        counterpartyName = publicCounterparty?.name
            ?: publicCounterparty?.phone
            ?: publicCounterparty?.accountNumber
            ?: "Kit Pay",
        counterpartyUserId = publicCounterparty?.id?.trim()?.takeIf(String::isNotEmpty),
        counterpartyAvatarUrl = publicCounterparty?.avatarUrl
            ?.trim()
            ?.takeIf(::isTrustedProfileAvatarUrl),
        counterpartyVerificationDesignation =
            counterpartyVerification?.designation?.serverValue,
        counterpartyVerificationSince = counterpartyVerification?.since,
        note = note,
        occurredAtEpochMillis = occurredAt.toEpochMillisOrNull() ?: 0L,
        customerProjectionVerified = true,
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
    // Also redact on read: an app that already created a schema-v16 row before this policy landed
    // must become safe immediately, without waiting for a successful network refresh.
    val hasPublicCounterparty = !type.hasInstitutionalCounterparty()
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
        counterparty = if (hasPublicCounterparty) counterpartyName else "Kit Pay",
        note = note,
        amountMinor = amountMinor,
        time = occurred.format(DateTimeFormatter.ofPattern("h:mm a")),
        dateGroup = dateGroup,
        type = type.toUiType(direction),
        status = status.toUiStatus(),
        reference = reference,
        currencyCode = currencyCode,
        currencyScale = currencyScale,
        walletId = walletUuid,
        // The unmapped backend words survive beside the display kind: reversal and refund
        // types collapse into SEND/RECEIVE for rendering, and credits share display kinds
        // with debits, so the starter checklist reads the originals to refuse them.
        rawType = type,
        rawDirection = direction,
        counterpartyUserId = counterpartyUserId.takeIf { hasPublicCounterparty },
        counterpartyAvatarUrl = counterpartyAvatarUrl.takeIf { hasPublicCounterparty },
        accountVerification = if (hasPublicCounterparty) {
            AccountVerification.fromServerValues(
                counterpartyVerificationDesignation,
                counterpartyVerificationSince,
            )
        } else {
            null
        },
        customerProjectionVerified = customerProjectionVerified,
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
